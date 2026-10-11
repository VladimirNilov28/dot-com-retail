import "server-only";
import type { PoolClient } from "pg";
import { authConfig } from "./config";
import { AuthError, type BrowserState, type FormKind } from "./model";
import { providerClient, providerFailure, registrationAvailability } from "./provider";
import { assertFlow, bindFlow, consumeAttempt } from "./store";
import { object, text, uuid } from "@/lib/graphql/scalars";
import { registrationPasswordError, type PasswordErrorCode } from "./password";
import { googleAuthorization } from "./social";
import { googleProfileErrors } from "./view";

export interface ProviderFlow {
  id: string;
  expiresAt: string;
  state?: string;
  requestedAal?: "aal1" | "aal2";
  nodes: Record<string, unknown>[];
  messages: Record<string, unknown>[];
}

export function validateFlow(value: unknown, kind: FormKind): ProviderFlow {
  const flow = object(value);
  const ui = object(flow.ui);
  const config = authConfig();
  const action = new URL(text(ui.action));
  const id = uuid(flow.id);
  const expiresAt = text(flow.expires_at);
  const providerKind = kind === "reset" ? "settings" : kind;
  if (kind === "recovery" && flow.state === "passed_challenge") throw new AuthError("expired", 401);
  const verified = kind === "verification" && flow.state === "passed_challenge";
  const validAction = verified
    ? action.origin === config.origin && action.pathname === "/auth/resume" &&
      !action.search && text(ui.method).toUpperCase() === "GET"
    : action.origin === config.kratos && action.pathname === `/self-service/${providerKind}` &&
      action.searchParams.getAll("flow").length === 1 && action.searchParams.get("flow") === id &&
      text(ui.method).toUpperCase() === "POST";
  if (flow.type !== "browser" || !Number.isFinite(Date.parse(expiresAt)) ||
      !validAction || action.username || action.password || action.hash ||
      !Array.isArray(ui.nodes) || ui.nodes.length > 100 ||
      (ui.messages !== undefined && !Array.isArray(ui.messages))) {
    throw new AuthError("unavailable");
  }
  if (Date.parse(expiresAt) <= Date.now()) throw new AuthError("expired", 401);
  return {
    id, expiresAt, nodes: ui.nodes.map(object), messages: (ui.messages ?? []).map(object),
    ...(flow.state !== undefined ? { state: text(flow.state) } : {}),
    ...(flow.requested_aal === "aal1" || flow.requested_aal === "aal2" ? { requestedAal: flow.requested_aal } : {}),
  };
}

export async function initializeFlow(
  state: BrowserState, browser: string, kind: FormKind, client: PoolClient, aal: "aal1" | "aal2" = "aal1", refresh = false,
): Promise<ProviderFlow> {
  const provider = providerClient(state);
  try {
    const config = authConfig();
    if (kind === "registration") delete state.googleRegistration;
    const flow = kind === "login"
      ? (await provider.api.createBrowserLoginFlow({
        aal, refresh: refresh || aal === "aal2", returnTo: `${config.origin}/auth/resume`,
      })).data
      : kind === "registration"
        ? (await provider.api.createBrowserRegistrationFlow({
          returnTo: `${config.origin}/verify`, afterVerificationReturnTo: `${config.origin}/auth/resume`,
          identitySchema: "customer-v1",
        })).data
        : kind === "recovery"
          ? (await provider.api.createBrowserRecoveryFlow({ returnTo: `${config.origin}/reset` })).data
          : kind === "reset"
            ? (await provider.api.createBrowserSettingsFlow({ returnTo: `${config.origin}/reset` })).data
            : (await provider.api.createBrowserVerificationFlow({ returnTo: `${config.origin}/auth/resume` })).data;
    const validated = validateFlow(flow, kind);
    await bindFlow(browser, validated.id, kind, validated.expiresAt, client);
    return validated;
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    if (failure.status === 400 || failure.status === 410) throw new AuthError("expired", 401);
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}

export async function readFlow(
  state: BrowserState, browser: string, id: string, kind: FormKind, client: PoolClient,
): Promise<ProviderFlow> {
  await assertFlow(client, browser, uuid(id), kind);
  const provider = providerClient(state);
  try {
    const flow = kind === "login" ? (await provider.api.getLoginFlow({ id })).data
      : kind === "registration" ? (await provider.api.getRegistrationFlow({ id })).data
        : kind === "recovery" ? (await provider.api.getRecoveryFlow({ id })).data
          : kind === "reset" ? (await provider.api.getSettingsFlow({ id })).data
            : (await provider.api.getVerificationFlow({ id })).data;
    return validateFlow(flow, kind);
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    console.error("authentication flow read reason", kind, failure.status,
      failure.status === 403 ? "provider_csrf_binding" : [400, 404, 410].includes(failure.status) ? "flow_unusable" : "provider_unavailable");
    if ([400, 403, 404, 410].includes(failure.status)) throw new AuthError("expired", 401);
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}

export type Submission = { complete: true; verificationFlow?: string; settingsFlow?: string; socialRedirect?: string; socialComplete?: true; socialConflict?: true } |
  { complete: false; validation: boolean; flow: ProviderFlow; passwordError?: PasswordErrorCode };

function field(form: URLSearchParams, name: string, maximum: number, optional = false): string {
  const values = form.getAll(name);
  if (values.length > 1 || (!optional && values.length !== 1) ||
      (values[0] && (values[0].length > maximum || /[\u0000\r\n]/.test(values[0])))) {
    throw new AuthError("invalid_request", 400);
  }
  return values[0] ?? "";
}

async function limitSecondFactor(browser: string): Promise<void> {
  if (!await consumeAttempt("second_factor", browser, 10, 900)) throw new AuthError("rate_limited", 429);
}

export async function submitFlow(
  state: BrowserState, browser: string, id: string, kind: FormKind, form: URLSearchParams, client: PoolClient,
): Promise<Submission> {
  const flow = await readFlow(state, browser, id, kind, client);
  if (state.social) throw new AuthError("invalid_request", 400);
  const method = field(form, "method", 32);
  if (!flow.nodes.some((node) => node.type === "input" &&
      ((object(node.attributes).name === "method" && object(node.attributes).value === method) ||
        (method === "oidc" && node.group === "oidc" && object(node.attributes).name === "provider" &&
          object(node.attributes).value === "google")))) {
    throw new AuthError("invalid_request", 400);
  }
  const csrf_token = field(form, "csrf_token", 1024);
  const provider = providerClient(state);
  try {
    let result: unknown;
    if (method === "oidc") {
      if (field(form, "provider", 32) !== "google") throw new AuthError("invalid_request", 400);
      if (kind === "login") {
        result = (await provider.api.updateLoginFlow({ flow: id, updateLoginFlowBody: { method: "oidc", provider: "google", csrf_token } })).data;
      } else if (kind === "registration") {
        for (const name of form.keys()) {
          if (!["flow", "_csrf", "method", "provider", "csrf_token", "traits.username", "traits.dateOfBirth", "altcha"].includes(name) ||
              form.getAll(name).length !== 1) throw new AuthError("invalid_request", 400);
        }
        const username = field(form, "traits.username", 255, true);
        const dateOfBirth = field(form, "traits.dateOfBirth", 10, true);
        const bound = state.googleRegistration?.flow === id ? state.googleRegistration : undefined;
        if (bound) {
          state.googleRegistration = { ...bound, submitted: true, providerValidation: false, username, dateOfBirth };
          delete state.googleRegistration.usernameUnavailable;
        }
        if (Object.keys(googleProfileErrors(username, dateOfBirth)).length) {
          return { complete: false, validation: true, flow };
        }
        let upstream: { login_hint: string } | undefined;
        if (bound && state.googleRegistration) {
          // Check canonical conflicts before the provider's required second
          // authorization, so a known conflict cannot surface only afterward.
          const available = await registrationAvailability(bound.email, username.trim());
          if (!available.email) {
            delete state.googleRegistration;
            return { complete: true, socialConflict: true };
          }
          if (!available.username) {
            state.googleRegistration.usernameUnavailable = true;
            return { complete: false, validation: true, flow };
          }
          upstream = { login_hint: bound.email };
        }
        result = (await provider.api.updateRegistrationFlow({ flow: id, updateRegistrationFlowBody: {
          method: "oidc", provider: "google", csrf_token,
          traits: { username, dateOfBirth },
          transient_payload: { altcha: field(form, "altcha", 16384) },
          ...(upstream ? { upstream_parameters: upstream } : {}),
        } })).data;
      } else throw new AuthError("invalid_request", 400);
    } else if (kind === "login") {
      if (method === "password") {
        result = (await provider.api.updateLoginFlow({ flow: id, updateLoginFlowBody: {
          method, csrf_token, identifier: field(form, "identifier", 255), password: field(form, "password", 4096),
        } })).data;
      } else if (method === "totp") {
        await limitSecondFactor(browser);
        result = (await provider.api.updateLoginFlow({ flow: id, updateLoginFlowBody: {
          method, csrf_token, totp_code: field(form, "totp_code", 64),
        } })).data;
      } else if (method === "lookup_secret") {
        // Recovery codes are only a second factor after primary sign-in; never a first factor.
        if (flow.requestedAal !== "aal2") throw new AuthError("invalid_request", 400);
        await limitSecondFactor(browser);
        result = (await provider.api.updateLoginFlow({ flow: id, updateLoginFlowBody: {
          method, csrf_token, lookup_secret: field(form, "lookup_secret", 128),
        } })).data;
      } else throw new AuthError("invalid_request", 400);
    } else if (kind === "registration") {
      if (method !== "password") throw new AuthError("invalid_request", 400);
      const password = field(form, "password", 4096);
      const passwordError = registrationPasswordError(password);
      if (passwordError) {
        console.error("authentication password reason", passwordError);
        return { complete: false, validation: true, flow, passwordError };
      }
      result = (await provider.api.updateRegistrationFlow({ flow: id, updateRegistrationFlowBody: {
        method, csrf_token, password,
        traits: {
          email: field(form, "traits.email", 255), username: field(form, "traits.username", 255),
          dateOfBirth: field(form, "traits.dateOfBirth", 10),
        },
        transient_payload: { altcha: field(form, "altcha", 16384) },
      } })).data;
    } else if (kind === "reset") {
      if (method !== "password") throw new AuthError("invalid_request", 400);
      const password = field(form, "password", 4096);
      const passwordError = registrationPasswordError(password);
      if (passwordError) return { complete: false, validation: true, flow, passwordError };
      const updated = (await provider.api.updateSettingsFlow({ flow: id, updateSettingsFlowBody: {
        method, csrf_token, password,
      } })).data;
      if (updated.state !== "success") {
        return { complete: false, validation: true, flow: validateFlow(updated, kind) };
      }
      return { complete: true };
    } else {
      if (method !== "code") throw new AuthError("invalid_request", 400);
      const code = field(form, "code", 128, true);
      const email = field(form, "email", 255, true);
      if (email && !flow.nodes.some((node) => node.type === "input" &&
          object(node.attributes).name === "email" && object(node.attributes).type === "submit" &&
          object(node.attributes).value === email) && flow.state === "sent_email") {
        throw new AuthError("invalid_request", 400);
      }
      if (kind === "recovery" && email && (!await consumeAttempt("recovery_email", email.trim().toLowerCase(), 3, 900) ||
          !await consumeAttempt("recovery_browser", browser, 6, 900))) throw new AuthError("rate_limited", 429);
      const body: { method: "code"; csrf_token: string; email?: string; code?: string } =
        { method: "code", csrf_token, ...(email ? { email } : { code }) };
      result = kind === "recovery"
        ? (await provider.api.updateRecoveryFlow({ flow: id, updateRecoveryFlowBody: {
          ...body, transient_payload: { storefront_flow_id: id },
        } })).data
        : (await provider.api.updateVerificationFlow({ flow: id, updateVerificationFlowBody: body })).data;
      if (kind === "verification") {
        const verification = validateFlow(result, kind);
        return verification.state === "passed_challenge" ? { complete: true } :
          { complete: false, validation: verification.messages.some((message) => message.type === "error"), flow: verification };
      }
      const recovered = object(result);
      if (recovered.state !== "passed_challenge") {
        const pending = validateFlow(result, kind);
        return { complete: false, validation: pending.messages.some((message) => message.type === "error"), flow: pending };
      }
    }
    const body = object(result);
    const continuations = Array.isArray(body.continue_with) ? body.continue_with : [];
    for (const item of continuations) {
      const continuation = object(item);
      if (kind === "recovery" && continuation.action === "show_settings_ui") {
        const settingsFlow = uuid(object(continuation.flow).id);
        const settings = validateFlow((await provider.api.getSettingsFlow({ id: settingsFlow })).data, "reset");
        await bindFlow(browser, settings.id, "reset", settings.expiresAt, client);
        return { complete: true, settingsFlow };
      }
      if (continuation.action === "show_verification_ui") {
        const target = object(continuation.flow);
        const verificationFlow = uuid(target.id);
        const verification = validateFlow((await provider.api.getVerificationFlow({ id: verificationFlow })).data, "verification");
        await bindFlow(browser, verification.id, "verification", verification.expiresAt, client);
        return { complete: true, verificationFlow };
      }
    }
    return { complete: true, ...(method === "oidc" ? { socialComplete: true as const } : {}) };
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    console.error("provider form response", kind, failure.status);
    if (failure.status === 400) {
      const rejected = validateFlow(failure.body, kind);
      if (rejected.id !== flow.id) throw new AuthError("invalid_request", 400);
      if (kind === "registration" && method === "oidc" && state.googleRegistration?.flow === id) {
        state.googleRegistration.providerValidation = true;
      }
      return { complete: false, validation: true, flow: rejected };
    }
    if (failure.status === 410) throw new AuthError("expired", 401);
    if (failure.status === 422) {
      const redirect = object(failure.body).redirect_browser_to;
      const config = authConfig();
      if (typeof redirect !== "string") throw new AuthError("unavailable");
      const url = new URL(redirect);
      if (method === "oidc" && url.origin === "https://accounts.google.com") {
        const destination = googleAuthorization(redirect);
        state.social = { state: text(destination.searchParams.get("state")), flow: id,
          kind: kind === "registration" ? "registration" : "login" };
        return { complete: true, socialRedirect: destination.href };
      }
      console.error("provider completion destination", kind, url.pathname, Array.from(url.searchParams.keys()).join(","));
      if (kind === "recovery" && url.origin === config.origin && url.pathname === "/reset" &&
          url.searchParams.getAll("flow").length === 1 &&
          Array.from(url.searchParams.keys()).every((key) => key === "flow") &&
          !url.hash && !url.username && !url.password) {
        const settingsFlow = uuid(url.searchParams.get("flow"));
        const settings = validateFlow((await provider.api.getSettingsFlow({ id: settingsFlow })).data, "reset");
        await bindFlow(browser, settings.id, "reset", settings.expiresAt, client);
        return { complete: true, settingsFlow };
      }
      if (kind === "login" && url.origin === config.kratos && url.pathname === "/self-service/login/browser" &&
          url.searchParams.getAll("aal").length === 1 && url.searchParams.get("aal") === "aal2" &&
          url.searchParams.getAll("return_to").length === 1 &&
          url.searchParams.get("return_to") === `${config.origin}/auth/resume` &&
          Array.from(url.searchParams.keys()).every((key) => key === "aal" || key === "return_to") &&
          !url.hash && !url.username && !url.password) {
        // Save the provisional provider jar, then let the existing bridge
        // verify assurance and initialize its own bound second-factor flow.
        return { complete: true };
      }
      if (url.origin === config.origin && url.pathname === "/auth/resume" &&
          !url.search && !url.hash && !url.username && !url.password) {
        if (kind === "login" || (kind === "registration" && method === "oidc")) {
          return { complete: true, ...(method === "oidc" ? { socialComplete: true as const } : {}) };
        }
        if (kind === "verification") {
          const verified = validateFlow((await provider.api.getVerificationFlow({ id })).data, kind);
          if (verified.state === "passed_challenge") return { complete: true };
        }
      }
      if (url.origin === config.origin && url.pathname === "/verify" && url.searchParams.get("flow")) {
        const id = uuid(url.searchParams.get("flow"));
        const verification = validateFlow((await provider.api.getVerificationFlow({ id })).data, "verification");
        await bindFlow(browser, id, "verification", verification.expiresAt, client);
        return { complete: true, verificationFlow: id };
      }
    }
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}
