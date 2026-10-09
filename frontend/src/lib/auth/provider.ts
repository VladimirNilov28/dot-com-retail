import "server-only";
import { Configuration, FrontendApi } from "@ory/client";
import axios, { isAxiosError } from "axios";
import { wrapper } from "axios-cookiejar-support";
import { CookieJar } from "tough-cookie";
import { object, text } from "@/lib/graphql/scalars";
import { authConfig } from "./config";
import { AuthError, decodeProviderSession, type BrowserState, type ProviderSession } from "./model";

export function emptyJar(): string {
  const serialized = new CookieJar().serializeSync();
  if (!serialized) throw new AuthError("unavailable");
  return JSON.stringify(serialized);
}

export function providerClient(state: BrowserState) {
  const config = authConfig();
  const jar = CookieJar.deserializeSync(state.jar);
  const client = wrapper(axios.create({
    jar, withCredentials: true, maxRedirects: 0, timeout: 8000,
    maxContentLength: 131072, maxBodyLength: 65536,
    headers: { Accept: "application/json", Origin: config.origin },
  }));
  return {
    api: new FrontendApi(new Configuration({ basePath: config.kratos }), config.kratos, client),
    jar,
    save() {
      const serialized = jar.serializeSync();
      if (!serialized) throw new AuthError("unavailable");
      state.jar = JSON.stringify(serialized);
    },
  };
}

export async function bridgeRequest(path: string, body: Record<string, unknown>): Promise<unknown> {
  const config = authConfig();
  try {
    const response = await fetch(`${config.bridge}/internal/${path}`, {
      method: "POST", redirect: "error", cache: "no-store", signal: AbortSignal.timeout(12000),
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${config.bridgeSecret}` },
      body: JSON.stringify(body),
    });
    if (response.status === 403) {
      const error = object(await response.json());
      if (error.error === "second_factor_required" || error.error === "verification_required") {
        throw new AuthError("reauthentication_required", 401);
      }
      throw new AuthError("forbidden", 403);
    }
    if (!response.ok) throw new AuthError("unavailable");
    return response.status === 204 ? null : await response.json();
  } catch (error) {
    if (error instanceof AuthError) throw error;
    throw new AuthError("unavailable");
  }
}

export async function providerCookie(state: BrowserState): Promise<string> {
  return CookieJar.deserializeSync(state.jar).getCookieString(authConfig().kratos);
}

export async function currentProviderSession(state: BrowserState): Promise<ProviderSession> {
  return decodeProviderSession(await bridgeRequest("session", { cookie: await providerCookie(state) }));
}

export function providerRedirect(value: unknown): URL {
  const config = authConfig();
  const url = new URL(text(value));
  if (url.origin !== config.issuer || url.pathname !== "/oauth2/auth" || url.username || url.password || url.hash) {
    throw new AuthError("unavailable");
  }
  return url;
}

export function providerFailure(error: unknown): { status: number; body: unknown } {
  if (!isAxiosError<unknown>(error) || !error.response) throw new AuthError("unavailable");
  return { status: error.response.status, body: error.response.data };
}
