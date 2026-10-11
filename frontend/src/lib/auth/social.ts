import "server-only";
import { timingSafeEqual } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";
import { object } from "@/lib/graphql/scalars";
import { authConfig } from "./config";
import { AuthError, assertSameProvider, flowIdentifier, oneParameter, type BrowserState } from "./model";
import { authBoundary, authHeaders, browserCookie, redirectResponse } from "./http";
import { currentProviderSession, providerClient, registrationAvailability } from "./provider";
import { assertFlow, bindFlow, withBrowser } from "./store";
import { initializeFlow, validateFlow } from "./flows";
import { assertSecuritySession, projectSecuritySettings, securitySettingsFlow } from "./settings";
import { authenticatedState } from "./session";

export const googleCallbackPath = "/self-service/methods/oidc/callback/google";

export function socialFailureReason(value: unknown): string {
  if (!value || typeof value !== "object" || !("error" in value) ||
      !value.error || typeof value.error !== "object") return "provider_callback_rejected";
  const error = object(value.error);
  const diagnostic = ["debug", "reason", "message"].flatMap((key) =>
    typeof error[key] === "string" ? [error[key]] : []).join(" ");
  for (const reason of ["invalid_client", "invalid_grant", "redirect_uri_mismatch"]) {
    if (new RegExp(`\\b${reason}\\b`).test(diagnostic)) return `token_exchange_${reason}`;
  }
  return "provider_callback_rejected";
}

export function googleAuthorization(value: unknown): URL {
  if (typeof value !== "string") throw new AuthError("unavailable");
  const url = new URL(value);
  const state = oneParameter(url.searchParams, "state");
  if (url.origin !== "https://accounts.google.com" ||
      !["/o/oauth2/v2/auth", "/o/oauth2/auth"].includes(url.pathname) ||
      url.username || url.password || url.hash ||
      oneParameter(url.searchParams, "redirect_uri") !== `${authConfig().origin}${googleCallbackPath}` ||
      !state || state.length < 16 || state.length > 4096 || /[\u0000-\u0020\u007f]/.test(state)) {
    throw new AuthError("unavailable");
  }
  return url;
}

export function socialCallbackParameters(parameters: URLSearchParams, expected: string): URLSearchParams {
  const state = oneParameter(parameters, "state");
  if (!state || Buffer.byteLength(state) !== Buffer.byteLength(expected) ||
      !timingSafeEqual(Buffer.from(state), Buffer.from(expected))) throw new AuthError("forbidden", 403);
  const code = oneParameter(parameters, "code");
  const error = oneParameter(parameters, "error");
  if (Boolean(code) === Boolean(error) || (code && (code.length > 8192 || /[\u0000-\u0020\u007f]/.test(code))) ||
      (error && error !== "access_denied")) throw new AuthError("invalid_request", 400);
  const result = new URLSearchParams({ state });
  if (code) result.set("code", code);
  if (error) result.set("error", error);
  return result;
}

export async function completeSocialCallback(state: BrowserState, browser: string, parameters: URLSearchParams,
  client: Parameters<typeof assertFlow>[0]): Promise<NextResponse> {
  const pending = state.social;
  if (!pending) throw new AuthError("expired", 401);
  const query = socialCallbackParameters(parameters, pending.state);
  await assertFlow(client, browser, pending.flow, pending.kind);
  const security = pending.kind === "settings" ? await securitySettingsFlow(state, browser, client, pending.flow) : undefined;
  delete state.social;
  const provider = providerClient(state);
  let stage = "provider_callback";
  let restarted = false;
  async function continueFlow(kind: "login" | "registration", id: string) {
    stage = kind === "registration" ? "registration_continuation" : "login_continuation";
    const result = kind === "login" ? (await provider.api.getLoginFlow({ id })).data
      : (await provider.api.getRegistrationFlow({ id })).data;
    const validated = validateFlow(result, kind);
    if (validated.id !== id) throw new AuthError("unavailable");
    if (kind === "registration") {
      const emailNodes = validated.nodes.filter((node) => node.type === "input" &&
        object(node.attributes).name === "traits.email");
      const email = emailNodes.length === 1 ? object(emailNodes[0].attributes).value : undefined;
      if (typeof email !== "string" || email.length > 255 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        throw new AuthError("unavailable");
      }
      const previous = state.googleRegistration;
      if (previous && previous.email !== email) throw new AuthError("forbidden", 403);
      const username = previous?.submitted ? previous.username?.trim() || undefined : undefined;
      const available = await registrationAvailability(email, username);
      // A verified Google email already used by another account must be
      // authenticated with that account and linked explicitly, never registered.
      if (!available.email) return await recoverConflict();
      const next: NonNullable<BrowserState["googleRegistration"]> = { ...previous, flow: id, email,
        submitted: previous?.submitted ?? false, providerValidation: (previous?.submitted ?? false) && available.username };
      delete next.usernameUnavailable;
      if (!available.username) next.usernameUnavailable = true;
      state.googleRegistration = next;
    }
    await bindFlow(browser, id, kind, validated.expiresAt, client);
    return redirectResponse(`${kind === "registration" ? "/register" : "/login"}?flow=${id}` +
      `&returnTo=${encodeURIComponent(state.returnTo)}`);
  }
  async function restart(notice: "cancelled" | "failed") {
    provider.save();
    restarted = true;
    const flow = await initializeFlow(state, browser, "login", client);
    return redirectResponse(`/login?flow=${flow.id}&social=${notice}&returnTo=${encodeURIComponent(state.returnTo)}`);
  }
  async function recoverConflict() {
    delete state.googleRegistration;
    provider.save();
    restarted = true;
    const flow = await initializeFlow(state, browser, "login", client);
    return redirectResponse(`/login?flow=${flow.id}&social=conflict&returnTo=${encodeURIComponent(state.returnTo)}`);
  }
  try {
    if (security) {
      if (!state.provider) throw new AuthError("expired", 401);
      assertSecuritySession((await provider.api.toSession()).data, state.provider, security);
    }
    const response = await provider.http.get(`${authConfig().kratos}${googleCallbackPath}?${query}`, {
      validateStatus: (status) => status >= 200 && status < 600,
    });
    provider.save();
    if (response.status >= 500) {
      console.error("authentication social stage", stage, socialFailureReason(response.data), response.status);
      throw new AuthError("unavailable");
    }
    if (security) {
      stage = "settings_callback";
      if (!state.provider) throw new AuthError("expired", 401);
      assertSameProvider(state.provider, await currentProviderSession(state));
      if (response.status === 422 || response.status === 401 || response.status === 403) {
        throw new AuthError("reauthentication_required", 401);
      }
      let success = false;
      if (response.status === 200) {
        const completed = object(response.data);
        if (completed.id !== pending.flow || object(completed.identity).id !== state.provider.identityId ||
            completed.state !== "success") throw new AuthError("unavailable");
        success = true;
      } else if (response.status === 400) {
        const failed = projectSecuritySettings(response.data, state);
        if (failed.id !== pending.flow) throw new AuthError("forbidden", 403);
      } else if ([302, 303].includes(response.status) && typeof response.headers.location === "string") {
        const target = new URL(response.headers.location);
        if (target.origin !== authConfig().origin || target.pathname !== "/account/security" ||
            target.username || target.password || target.hash ||
            Array.from(target.searchParams.keys()).some((key) => key !== "flow")) throw new AuthError("unavailable");
        success = !query.has("error");
      } else throw new AuthError("unavailable");
      restarted = true;
      const next = await securitySettingsFlow(state, browser, client);
      if (success && next.google !== "connected") throw new AuthError("unavailable");
      return redirectResponse(`/account/security?flow=${next.id}&${success ? "saved=1" :
        `google=${query.has("error") ? "cancelled" : "failed"}`}`);
    }
    if (query.has("error")) {
      // Kratos must consume/verify its own state before cancellation recovery.
      return restart([302, 303].includes(response.status) ? "cancelled" : "failed");
    }
    if (response.status === 200 && response.data && typeof response.data === "object") {
      const body = object(response.data);
      const identity = body.identity ?? (body.session && typeof body.session === "object" ? object(body.session).identity : undefined);
      if (!identity || typeof identity !== "object") throw new AuthError("unavailable");
      flowIdentifier(object(identity).id);
      if (pending.kind === "registration" && object(identity).schema_id !== "customer-v1") throw new AuthError("forbidden", 403);
      // Only the existing bridge/Hydra handoff can establish a storefront
      // session; it checks canonical identity, verification and required AAL.
      return redirectResponse("/auth/resume");
    }
    if (response.status === 400 && response.data && typeof response.data === "object" &&
        typeof response.data.id === "string" && response.data.ui &&
        typeof response.data.ui.action === "string") {
      const action = new URL(response.data.ui.action);
      if (action.origin === authConfig().kratos && action.pathname === "/self-service/registration") {
        const registration = validateFlow(response.data, "registration");
        return await continueFlow("registration", registration.id);
      }
      if (action.origin === authConfig().kratos && action.pathname === "/self-service/login") {
        validateFlow(response.data, "login");
        return await recoverConflict();
      }
    }
    const location = response.headers.location ?? (response.status === 422 &&
      response.data && typeof response.data === "object" ? response.data.redirect_browser_to : undefined);
    if (![302, 303, 422].includes(response.status) || typeof location !== "string") {
      console.error("authentication social reason", "provider_callback_rejected", response.status);
      return restart("failed");
    }
    const target = new URL(location);
    stage = "callback_destination";
    const config = authConfig();
    if (target.origin === config.kratos && target.pathname === "/self-service/login/browser" &&
        oneParameter(target.searchParams, "aal") === "aal2" &&
        oneParameter(target.searchParams, "return_to") === `${config.origin}/auth/resume` &&
        Array.from(target.searchParams.keys()).every((key) => key === "aal" || key === "return_to") &&
        !target.username && !target.password && !target.hash) {
      provider.save();
      restarted = true;
      const flow = await initializeFlow(state, browser, "login", client, "aal2");
      return redirectResponse(`/login?flow=${flow.id}&returnTo=${encodeURIComponent(state.returnTo)}`);
    }
    if (target.origin !== config.origin || target.username || target.password || target.hash) {
      throw new AuthError("unavailable");
    }
    if (target.pathname === "/auth/resume" && !target.search) return redirectResponse(target);
    if (["/login", "/register"].includes(target.pathname) &&
        Array.from(target.searchParams.keys()).every((key) => key === "flow" ||
          (target.pathname === "/login" && key === "no_org_ui" && oneParameter(target.searchParams, key) === "true"))) {
      const kind = target.pathname === "/register" ? "registration" : "login";
      if (kind === "login" && target.searchParams.has("no_org_ui")) {
        // Discard provider duplicate-credential auto-link continuity. Require a
        // fresh existing-account login, then deliberate linking from settings.
        return await recoverConflict();
      }
      const id = flowIdentifier(oneParameter(target.searchParams, "flow"));
      return await continueFlow(kind, id);
    }
    if (target.pathname === "/auth/error") {
      return restart("failed");
    }
    throw new AuthError("unavailable");
  } catch (error) {
    console.error("authentication social stage", stage, error instanceof AuthError ? error.code : "transport_or_invalid_response");
    throw error;
  } finally { if (!restarted) provider.save(); }
}

export async function handleSocialCallback(request: NextRequest): Promise<NextResponse> {
  return authBoundary(async () => {
    if (request.method !== "GET") return NextResponse.json({ error: "invalid_request" },
      { status: 405, headers: { ...authHeaders, Allow: "GET" } });
    if (request.headers.get("host") !== new URL(authConfig().origin).host) throw new AuthError("forbidden", 403);
    if (request.cookies.has(authConfig().sessionCookie)) {
      const browser = browserCookie(request, true);
      let isSettings = false;
      try {
        isSettings = await withBrowser(browser, async (state) => state.social?.kind === "settings");
      } catch (error) {
        if (!(error instanceof AuthError) || error.code !== "expired") throw error;
      }
      if (isSettings) {
        try {
          await authenticatedState(browser);
          return await withBrowser(browser, async (state, client) =>
            completeSocialCallback(state, browser, request.nextUrl.searchParams, client));
        } catch (error) {
          if (error instanceof AuthError && ["reauthentication_required", "expired"].includes(error.code)) {
            return redirectResponse("/account/security?reauth=required");
          }
          throw error;
        }
      }
    }
    const browser = browserCookie(request);
    return withBrowser(browser, async (state, client) =>
      completeSocialCallback(state, browser, request.nextUrl.searchParams, client));
  }, "social_callback", request);
}
