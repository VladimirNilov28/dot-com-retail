import "server-only";
import { timingSafeEqual } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";
import { object } from "@/lib/graphql/scalars";
import { authConfig } from "./config";
import { AuthError, flowIdentifier, oneParameter, type BrowserState } from "./model";
import { authBoundary, authHeaders, browserCookie, redirectResponse } from "./http";
import { providerClient } from "./provider";
import { assertFlow, bindFlow, withBrowser } from "./store";
import { initializeFlow, validateFlow } from "./flows";

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
  try {
    const response = await provider.http.get(`${authConfig().kratos}${googleCallbackPath}?${query}`, {
      validateStatus: (status) => status >= 200 && status < 600,
    });
    provider.save();
    if (response.status >= 500) {
      console.error("authentication social stage", stage, socialFailureReason(response.data), response.status);
      throw new AuthError("unavailable");
    }
    if (query.has("error")) {
      // Kratos must consume/verify its own state before cancellation recovery.
      return restart([302, 303].includes(response.status) ? "cancelled" : "failed");
    }
    if (response.status === 400 && response.data && typeof response.data === "object" &&
        typeof response.data.id === "string" && response.data.ui &&
        typeof response.data.ui.action === "string") {
      const action = new URL(response.data.ui.action);
      if (action.origin === authConfig().kratos && action.pathname === "/self-service/registration") {
        const registration = validateFlow(response.data, "registration");
        return await continueFlow("registration", registration.id);
      }
    }
    if (![302, 303].includes(response.status) || typeof response.headers.location !== "string") {
      console.error("authentication social reason", "provider_callback_rejected", response.status);
      return restart("failed");
    }
    const target = new URL(response.headers.location);
    stage = "callback_destination";
    const config = authConfig();
    if (target.origin !== config.origin || target.username || target.password || target.hash) {
      throw new AuthError("unavailable");
    }
    if (target.pathname === "/auth/resume" && !target.search) return redirectResponse(target);
    if (["/login", "/register"].includes(target.pathname) &&
        Array.from(target.searchParams.keys()).every((key) => key === "flow")) {
      const kind = target.pathname === "/register" ? "registration" : "login";
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
    return withBrowser(browserCookie(request), async (state, client) => {
      return completeSocialCallback(state, browserCookie(request), request.nextUrl.searchParams, client);
    });
  }, "social_callback", request);
}
