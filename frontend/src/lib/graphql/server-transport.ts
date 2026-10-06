import { execute, type Result, type TransportOptions } from "./transport";
import type { Operation } from "./operations";
import { failure } from "./errors";

export interface HiveConfig { endpoint: string; origin: string }
export type RequestContext =
  | { kind: "public" }
  | { kind: "guest"; cookieHeader?: string }
  | { kind: "authenticated"; accessToken: string; guestCookieHeader?: string };

const cookieNames = new Set(["retail_guest_cart", "retail_guest_orders"]);

export function hiveConfig(environment: Record<string, string | undefined>): HiveConfig {
  const endpoint = environment.HIVE_GRAPHQL_URL;
  const origin = environment.STOREFRONT_ORIGIN;
  if (!endpoint || !origin) throw new TypeError("Hive endpoint and storefront origin are required");
  const url = new URL(endpoint);
  const site = new URL(origin);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.hash || url.search ||
      url.pathname !== "/graphql" || site.origin !== origin ||
      !["http:", "https:"].includes(site.protocol)) throw new TypeError("Invalid Hive configuration");
  if (site.protocol !== "https:" && !["localhost", "127.0.0.1", "[::1]"].includes(site.hostname)) {
    throw new TypeError("Non-local storefronts require HTTPS");
  }
  return { endpoint: url.href, origin };
}

export function guestCookies(header?: string | null): string {
  const selected = new Map<string, string>();
  for (const part of header?.split(";") ?? []) {
    const separator = part.indexOf("=");
    if (separator < 1) {
      if (cookieNames.has(part.trim())) throw new TypeError("Malformed guest cookie");
      continue;
    }
    const name = part.slice(0, separator).trim();
    if (!cookieNames.has(name)) continue;
    const value = part.slice(separator + 1).trim();
    if (selected.has(name) || !/^[A-Za-z0-9_-]{1,512}$/.test(value)) throw new TypeError("Invalid guest cookie");
    selected.set(name, value);
  }
  return Array.from(selected, ([name, value]) => `${name}=${value}`).join("; ");
}

export function responseCookies(headers: Headers, secure: boolean): string[] {
  return headers.getSetCookie().map((cookie) => {
    const [pair, ...attributes] = cookie.split(";").map((part) => part.trim());
    const separator = pair.indexOf("=");
    const name = pair.slice(0, separator);
    const value = pair.slice(separator + 1);
    const normalized = new Map<string, string>();
    for (const attribute of attributes) {
      const equals = attribute.indexOf("=");
      const key = (equals < 0 ? attribute : attribute.slice(0, equals)).trim().toLowerCase();
      const value = equals < 0 ? "" : attribute.slice(equals + 1).trim().toLowerCase();
      if (normalized.has(key)) throw new TypeError("Ambiguous response cookie attributes");
      normalized.set(key, value);
    }
    if (separator < 1 || !cookieNames.has(name) || !/^[A-Za-z0-9_-]{0,512}$/.test(value) ||
        normalized.has("domain") || normalized.get("path") !== "/graphql" ||
        normalized.get("httponly") !== "" || normalized.get("samesite") !== "lax" ||
        (secure && normalized.get("secure") !== "")) {
      throw new TypeError("Invalid guest response cookie");
    }
    return cookie;
  });
}

export async function requestHive<Data, Variables>(
  operation: Operation<Data, Variables>,
  variables: Variables,
  config: HiveConfig,
  context: RequestContext,
  options: Pick<TransportOptions, "fetch" | "signal" | "timeoutMs"> = {},
): Promise<{ result: Result<Data>; setCookies: string[] }> {
  const headers = new Headers();
  const setCookies: string[] = [];
  if (operation.access === "authenticated" && context.kind !== "authenticated") {
    return { result: { status: "failure", error: failure("unauthenticated", false) }, setCookies };
  }
  if (operation.access === "guest" && context.kind === "public") {
    return { result: { status: "failure", error: failure("validation", false) }, setCookies };
  }
  if (operation.access === "guest") {
    headers.set("Origin", config.origin);
    headers.set("X-Guest-Cart-Request", "1");
    try {
      const cookie = guestCookies(context.kind === "guest" ? context.cookieHeader :
        context.kind === "authenticated" ? context.guestCookieHeader : undefined);
      if (cookie) headers.set("Cookie", cookie);
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
      return { result: { status: "failure", error: failure("validation", false) }, setCookies };
    }
  }
  if (context.kind === "authenticated" && operation.access !== "public") {
    if (!/^[A-Za-z0-9._~+/-]+=*$/.test(context.accessToken)) {
      return { result: { status: "failure", error: failure("validation", false) }, setCookies };
    }
    headers.set("Authorization", `Bearer ${context.accessToken}`);
  }
  const result = await execute(operation, variables, {
    ...options, endpoint: config.endpoint, headers,
    onResponse(response) {
      if (operation.access === "public") {
        if (response.headers.has("Set-Cookie")) throw new TypeError("Public read returned credentials");
        return;
      }
      setCookies.push(...responseCookies(response.headers, config.origin.startsWith("https://")));
    },
  });
  return { result, setCookies: result.status === "failure" && result.error.kind === "protocol" ? [] : setCookies };
}
