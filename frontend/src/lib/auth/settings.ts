import "server-only";
import type { UpdateSettingsFlowBody } from "@ory/client";
import type { PoolClient } from "pg";
import { NextRequest, NextResponse } from "next/server";
import { object, text } from "@/lib/graphql/scalars";
import { authConfig } from "./config";
import { assertCsrf, authBoundary, authHeaders, boundedForm, browserCookie, redirectResponse } from "./http";
import { AuthError, accountSection, assertSameProvider, flowIdentifier, oneParameter, type AccountSection, type BrowserState,
  type ProviderSession } from "./model";
import { currentProviderSession, providerClient, providerFailure } from "./provider";
import { accountStatus, assertStepUp, notifySecurityEvent, privilegedAge, sessionFacts, stepUpView,
  type SecurityEvent } from "./account";
import { registrationPasswordError, type PasswordErrorCode } from "./password";
import { authenticatedState } from "./session";
import { assertFlow, bindFlow, withBrowser } from "./store";
import { googleAuthorization } from "./social";

export type SecurityAction = "totp_confirm" | "totp_disable" | "backup_generate" |
  "backup_confirm" | "backup_disable" | "google_connect" | "google_disconnect" | "password";
export interface SecuritySettings {
  id: string;
  expiresAt: string;
  csrf: string;
  totpEnabled: boolean;
  backupEnabled: boolean;
  actions: SecurityAction[];
  qr?: string;
  secret?: string;
  codes: { value: string; used: boolean }[];
  saved: boolean;
  invalid: boolean;
  google: "unavailable" | "disconnected" | "connected";
  socialRedirect?: string;
  savedAction?: SecurityAction;
  notice?: "last_method" | "totp_required" | "password_mismatch" | "recovery_mode" | PasswordErrorCode;
}

const backupFields = {
  backup_generate: "lookup_secret_regenerate",
  backup_confirm: "lookup_secret_confirm",
  backup_disable: "lookup_secret_disable",
} as const;
const securityActions: SecurityAction[] = ["totp_confirm", "totp_disable", ...Object.keys(backupFields) as (keyof typeof backupFields)[],
  "google_connect", "google_disconnect", "password"];

function record(value: unknown): Record<string, unknown> {
  try { return object(value); } catch { throw new AuthError("unavailable"); }
}

function assertProviderMessages(messages: unknown): asserts messages is unknown[] | undefined {
  if (messages === undefined) return;
  if (!Array.isArray(messages) || messages.length > 30) throw new AuthError("unavailable");
  if (messages.some((value) => {
    const message = record(value);
    return message.type === "error" && typeof message.id === "number" && message.id >= 5000000;
  })) throw new AuthError("unavailable");
}

function pngDataUri(value: unknown): string {
  if (typeof value !== "string" || value.length > 131072 ||
      !/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)) throw new AuthError("unavailable");
  const bytes = Buffer.from(value.slice("data:image/png;base64,".length), "base64");
  if (!bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) ||
      bytes.toString("base64") !== value.slice("data:image/png;base64,".length)) throw new AuthError("unavailable");
  return value;
}

/** Project only password, MFA and Google controls, never traits, credential configurations, HTML or provider URLs. */
export function projectSecuritySettings(value: unknown, state: BrowserState): SecuritySettings {
  const flow = record(value);
  const ui = record(flow.ui);
  const identity = record(flow.identity);
  const id = flowIdentifier(flow.id);
  const expiresAt = text(flow.expires_at);
  const action = new URL(text(ui.action));
  if (!state.provider || identity.id !== state.provider.identityId ||
      identity.schema_id !== "customer-v1") throw new AuthError("forbidden", 403);
  if (flow.type !== "browser" || !Number.isFinite(Date.parse(expiresAt)) ||
      action.origin !== authConfig().kratos || action.pathname !== "/self-service/settings" ||
      action.username || action.password || action.hash ||
      action.searchParams.size !== 1 || oneParameter(action.searchParams, "flow") !== id ||
      ui.method !== "POST" || !Array.isArray(ui.nodes) || ui.nodes.length > 100) throw new AuthError("unavailable");
  if (Date.parse(expiresAt) <= Date.now()) throw new AuthError("expired", 401);
  const result: SecuritySettings = {
    id, expiresAt, csrf: state.csrf, totpEnabled: false, backupEnabled: false,
    actions: [], codes: [], saved: flow.state === "success", invalid: false, google: "unavailable",
  };
  const names = new Set<string>();
  for (const raw of ui.nodes) {
    const node = record(raw);
    if (node.group === "oidc") {
      const attributes = record(node.attributes);
      if (node.type === "input" && attributes.type === "submit" && attributes.value === "google" &&
          attributes.disabled !== true && ["link", "unlink"].includes(String(attributes.name))) {
        result.google = attributes.name === "link" ? "disconnected" : "connected";
        result.actions.push(attributes.name === "link" ? "google_connect" : "google_disconnect");
      }
      assertProviderMessages(node.messages);
      if (Array.isArray(node.messages) && node.messages.some((message) => record(message).type === "error")) result.invalid = true;
      continue;
    }
    if (node.group === "password") {
      const attributes = record(node.attributes);
      if (node.type === "input" && attributes.name === "password" && attributes.disabled !== true) names.add("password");
      assertProviderMessages(node.messages);
      if (Array.isArray(node.messages) && node.messages.some((message) => record(message).type === "error")) result.invalid = true;
      continue;
    }
    if (node.group !== "totp" && node.group !== "lookup_secret") continue;
    const attributes = record(node.attributes);
    if (attributes.disabled === true) continue;
    if (node.type === "input" && typeof attributes.name === "string") {
      names.add(attributes.name);
      if (attributes.name === "totp_unlink") result.totpEnabled = true;
      if (attributes.name === "lookup_secret_disable") result.backupEnabled = true;
    }
    if (node.group === "totp" && node.type === "img" && attributes.id === "totp_qr") {
      result.qr = pngDataUri(attributes.src);
    }
    if (node.group === "totp" && node.type === "text" && attributes.id === "totp_secret_key") {
      const context = record(record(attributes.text).context);
      if (typeof context.secret !== "string" || !/^[A-Z2-7]{16,128}$/.test(context.secret)) throw new AuthError("unavailable");
      result.secret = context.secret;
    }
    if (node.group === "lookup_secret" && node.type === "text" && attributes.id === "lookup_secret_codes") {
      const secrets = record(record(attributes.text).context).secrets;
      if (!Array.isArray(secrets) || secrets.length > 24) throw new AuthError("unavailable");
      result.codes = secrets.map((rawCode) => {
        const context = record(record(rawCode).context);
        if (typeof context.secret !== "string" || !/^[A-Za-z0-9 -]{4,128}$/.test(context.secret)) throw new AuthError("unavailable");
        return { value: context.secret, used: context.used === true };
      });
    }
    assertProviderMessages(node.messages);
    if (Array.isArray(node.messages) && node.messages.some((message) => record(message).type === "error")) result.invalid = true;
  }
  // Stored codes are shown only once, while a freshly generated set awaits confirmation.
  if (!names.has("lookup_secret_confirm")) result.codes = [];
  if (names.has("password")) result.actions.push("password");
  if (names.has("totp_code")) result.actions.push("totp_confirm");
  if (result.totpEnabled) result.actions.push("totp_disable");
  for (const [action, field] of Object.entries(backupFields)) {
    if (names.has(field)) result.actions.push(action as SecurityAction);
  }
  // Kratos offers only "reveal" while codes exist; regenerating is accepted directly, so the
  // stored codes are replaced without ever being revealed again.
  if (names.has("lookup_secret_reveal") && !result.actions.includes("backup_generate")) result.actions.push("backup_generate");
  if (ui.messages !== undefined) {
    assertProviderMessages(ui.messages);
    result.invalid ||= ui.messages.some((message) => record(message).type === "error");
  }
  return result;
}

function providerCsrf(value: unknown): string {
  const nodes = record(record(value).ui).nodes;
  if (!Array.isArray(nodes)) throw new AuthError("unavailable");
  const matches = nodes.map(record).filter((node) => node.type === "input" && node.group === "default" &&
    record(node.attributes).name === "csrf_token");
  if (matches.length !== 1) throw new AuthError("unavailable");
  const attributes = record(matches[0].attributes);
  if (attributes.type !== "hidden" || typeof attributes.value !== "string" ||
      !attributes.value || attributes.value.length > 4096) throw new AuthError("unavailable");
  return attributes.value;
}

export function securityPayload(form: URLSearchParams, settings: SecuritySettings, csrf: string): UpdateSettingsFlowBody & { csrf_token: string } {
  for (const name of form.keys()) {
    if (!["_csrf", "flow", "action", "totp_code", "acknowledge", "section", "password", "password_confirm"].includes(name) ||
        form.getAll(name).length !== 1) throw new AuthError("invalid_request", 400);
  }
  const action = oneParameter(form, "action") as SecurityAction;
  if (!securityActions.includes(action) || !settings.actions.includes(action)) throw new AuthError("invalid_request", 400);
  const acknowledgement = oneParameter(form, "acknowledge");
  if (["totp_disable", "backup_disable", "backup_confirm", "google_disconnect"].includes(action) && acknowledgement !== "yes") {
    throw new AuthError("invalid_request", 400);
  }
  if (action === "totp_confirm") {
    const code = oneParameter(form, "totp_code");
    if (!code || !/^[0-9]{6}$/.test(code)) throw new AuthError("invalid_request", 400);
    return { method: "totp", csrf_token: csrf, totp_code: code };
  }
  if (form.has("totp_code")) throw new AuthError("invalid_request", 400);
  if (action === "password") {
    const password = oneParameter(form, "password");
    if (!password || password.length > 4096) throw new AuthError("invalid_request", 400);
    return { method: "password", csrf_token: csrf, password };
  }
  if (form.has("password") || form.has("password_confirm")) throw new AuthError("invalid_request", 400);
  if (action === "google_connect") return { method: "oidc", csrf_token: csrf, link: "google",
    upstream_parameters: { prompt: "select_account" } };
  if (action === "google_disconnect") return { method: "oidc", csrf_token: csrf, unlink: "google" };
  if (action === "totp_disable") return { method: "totp", csrf_token: csrf, totp_unlink: true };
  return { method: "lookup_secret", csrf_token: csrf, [backupFields[action]]: true };
}

export function assertSecuritySession(value: unknown, expected: ProviderSession, settings?: SecuritySettings): void {
  const session = record(value);
  const identity = record(session.identity);
  if (session.active !== true || session.id !== expected.sessionId || identity.id !== expected.identityId ||
      identity.schema_id !== "customer-v1" || session.authenticator_assurance_level !== expected.aal ||
      typeof session.expires_at !== "string" || !(Date.parse(session.expires_at) > Date.now())) {
    throw new AuthError("reauthentication_required", 401);
  }
  if (settings) {
    const authenticatedAt = typeof session.authenticated_at === "string" ? Date.parse(session.authenticated_at) : NaN;
    if (!Number.isFinite(authenticatedAt) || authenticatedAt > Date.now() + 5000 ||
        Date.now() - authenticatedAt >= privilegedAge ||
        ((settings.totpEnabled || settings.backupEnabled) && session.authenticator_assurance_level !== "aal2")) {
      throw new AuthError("reauthentication_required", 401);
    }
  }
}

function settingsFailure(error: unknown): never {
  if (error instanceof AuthError) throw error;
  const failure = providerFailure(error);
  if ([401, 403, 422].includes(failure.status)) throw new AuthError("reauthentication_required", 401);
  if ([404, 410].includes(failure.status)) throw new AuthError("expired", 401);
  throw new AuthError("unavailable");
}

const recoveryActions: SecurityAction[] = ["totp_confirm", "totp_disable", "backup_generate", "backup_confirm", "backup_disable"];

const securityEvents: Partial<Record<SecurityAction, SecurityEvent>> = {
  totp_confirm: "totp_enabled", totp_disable: "totp_disabled", backup_confirm: "recovery_codes_regenerated",
  backup_disable: "recovery_codes_disabled", google_disconnect: "google_disconnected",
};

async function freshSettingsFlow(provider: ReturnType<typeof providerClient>, state: BrowserState, browser: string,
  client: PoolClient): Promise<SecuritySettings> {
  const next = (await provider.api.createBrowserSettingsFlow({
    returnTo: `${authConfig().origin}/account/security`,
  })).data;
  const projected = projectSecuritySettings(next, state);
  await bindFlow(browser, projected.id, "settings", projected.expiresAt, client);
  return projected;
}

export async function securitySettingsFlow(
  state: BrowserState, browser: string, client: PoolClient, id?: string, form?: URLSearchParams,
): Promise<SecuritySettings> {
  if (!state.provider || !state.tokens || state.provider.role !== "USER") throw new AuthError("forbidden", 403);
  if (id) await assertFlow(client, browser, flowIdentifier(id), "settings");
  const provider = providerClient(state);
  let stage = "session";
  try {
    const session = (await provider.api.toSession()).data;
    assertSecuritySession(session, state.provider);
    stage = "flow";
    const response = id ? await provider.api.getSettingsFlow({ id })
      : await provider.api.createBrowserSettingsFlow({ returnTo: `${authConfig().origin}/account/security` });
    let flow: unknown = response.data;
    let projected = projectSecuritySettings(flow, state);
    if (id && projected.id !== id) throw new AuthError("forbidden", 403);
    if (!id) await bindFlow(browser, projected.id, "settings", projected.expiresAt, client);
    stage = "status";
    let status = await accountStatus(state);
    if (form) {
      assertSecuritySession(session, state.provider, projected);
      const payload = securityPayload(form, projected, providerCsrf(flow));
      const action = oneParameter(form, "action") as SecurityAction;
      if (payload.method === "oidc" && state.social) throw new AuthError("invalid_request", 400);
      // Server-side step-up: TOTP when enrolled, otherwise a proven email code.
      const view = stepUpView(state, sessionFacts(session, state.provider), projected.totpEnabled);
      assertStepUp(view);
      // A recovery-code session may only replace the lost authenticator and its codes.
      if (view.recoveryMode && !recoveryActions.includes(action)) return { ...projected, notice: "recovery_mode" };
      if (action === "google_disconnect" && !status.credentials.password) return { ...projected, notice: "last_method" };
      if (action === "backup_generate" && !projected.totpEnabled) return { ...projected, notice: "totp_required" };
      if (action === "password") {
        const password = oneParameter(form, "password") ?? "";
        if (password !== oneParameter(form, "password_confirm")) return { ...projected, notice: "password_mismatch" };
        const weak = registrationPasswordError(password);
        if (weak) return { ...projected, notice: weak };
      }
      const hadPassword = status.credentials.password;
      stage = "mutation";
      try {
        flow = (await provider.api.updateSettingsFlow({ flow: projected.id, updateSettingsFlowBody: payload })).data;
      } catch (error) {
        const failure = providerFailure(error);
        if (failure.status === 422 && payload.method === "oidc" && "link" in payload) {
          const destination = googleAuthorization(record(failure.body).redirect_browser_to);
          if (state.social) throw new AuthError("invalid_request", 400);
          state.social = { state: text(oneParameter(destination.searchParams, "state")), flow: projected.id, kind: "settings" };
          return { ...projected, socialRedirect: destination.href };
        }
        if (failure.status !== 400) settingsFailure(error);
        flow = failure.body;
      }
      const completed = record(flow);
      if (completed.state === "success") {
        if (completed.id !== projected.id || record(completed.identity).id !== state.provider.identityId) {
          throw new AuthError("forbidden", 403);
        }
        stage = "elevated_session";
        provider.save();
        const fresh = await currentProviderSession(state);
        // Kratos enrollment can elevate this same session from aal1 to aal2.
        assertSameProvider({ ...state.provider, aal: fresh.aal }, fresh);
        state.provider = fresh;
        // Kratos success responses can carry the old flow ID with a new
        // action ID. Create and bind a fresh flow, never relax action binding.
        projected = await freshSettingsFlow(provider, state, browser, client);
        if (action === "totp_disable" && projected.actions.includes("backup_disable")) {
          // Recovery codes only recover an authenticator; never leave them as a lone second factor.
          stage = "recovery_codes_cleanup";
          const cleanup = (await provider.api.getSettingsFlow({ id: projected.id })).data;
          await provider.api.updateSettingsFlow({ flow: projected.id, updateSettingsFlowBody:
            { method: "lookup_secret", csrf_token: providerCsrf(cleanup), lookup_secret_disable: true } });
          projected = await freshSettingsFlow(provider, state, browser, client);
        }
        projected.saved = true;
        projected.savedAction = action;
        status = await accountStatus(state);
        const event = action === "password" ? hadPassword ? "password_changed" : "password_set" : securityEvents[action];
        if (event) await notifySecurityEvent(state, event);
      } else {
        const updated = projectSecuritySettings(flow, state);
        if (updated.id !== projected.id) throw new AuthError("forbidden", 403);
        projected = updated;
      }
    }
    if (projected.google === "unavailable" && status.credentials.google) projected.google = "connected";
    return projected;
  } catch (error) {
    console.error("authentication settings stage", stage,
      error instanceof AuthError ? error.code : "provider_response");
    settingsFailure(error);
  } finally {
    provider.save();
  }
}

export async function loadSecuritySettings(browser: string, id?: string): Promise<SecuritySettings> {
  await authenticatedState(browser);
  return withBrowser(browser, async (state, client, row) => {
    if (!row.authenticated) throw new AuthError("expired", 401);
    return securitySettingsFlow(state, browser, client, id);
  });
}

const settingsSections: AccountSection[] = ["/account/security", "/account/connections"];

function settingsSection(value: unknown): AccountSection {
  const section = accountSection(value);
  if (!settingsSections.includes(section)) throw new AuthError("invalid_request", 400);
  return section;
}

/** Wire both GET and POST of /auth/security to this handler. */
export async function handleSecuritySettings(request: NextRequest): Promise<NextResponse> {
  let section: AccountSection = "/account/security";
  return authBoundary(async () => {
    try {
      if (request.method !== "GET" && request.method !== "POST") {
        return NextResponse.json({ error: "invalid_request" }, { status: 405, headers: { ...authHeaders, Allow: "GET, POST" } });
      }
      const browser = browserCookie(request, true);
      if (request.method === "GET") {
        section = settingsSection(oneParameter(request.nextUrl.searchParams, "section"));
        const settings = await loadSecuritySettings(browser, oneParameter(request.nextUrl.searchParams, "flow"));
        return redirectResponse(`${section}?flow=${settings.id}${settings.saved ? "&saved=1" : ""}`);
      }
      const form = await boundedForm(request);
      section = settingsSection(oneParameter(form, "section"));
      await authenticatedState(browser);
      const settings = await withBrowser(browser, async (state, client, row) => {
        if (!row.authenticated) throw new AuthError("expired", 401);
        assertCsrf(request, form, state.csrf);
        return securitySettingsFlow(state, browser, client, flowIdentifier(oneParameter(form, "flow")), form);
      });
      if (settings.socialRedirect) return redirectResponse(settings.socialRedirect);
      const query = new URLSearchParams({ flow: settings.id });
      if (settings.savedAction) query.set("saved", settings.savedAction);
      if (settings.notice) query.set("notice", settings.notice);
      if (settings.invalid) query.set("invalid", "1");
      return redirectResponse(`${section}?${query}`);
    } catch (error) {
      if (error instanceof AuthError && error.code === "reauthentication_required") {
        return redirectResponse(`${section}?reauth=required`);
      }
      if (error instanceof AuthError && error.code === "step_up_required") {
        return redirectResponse(`${section}?stepup=required`);
      }
      throw error;
    }
  }, "security_settings", request, async () => section);
}
