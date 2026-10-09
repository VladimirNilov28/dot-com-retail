import "server-only";
import { getPublicSuffix } from "tough-cookie";
import { AuthError } from "./model";

export interface AuthConfig {
  origin: string;
  issuer: string;
  kratos: string;
  bridge: string;
  clientId: string;
  clientSecret: string;
  bridgeSecret: string;
  databaseUrl: string;
  secure: boolean;
  sessionCookie: string;
  flowCookie: string;
  activeKey: string;
  keys: Map<string, Uint8Array>;
}

function required(environment: NodeJS.ProcessEnv, name: string): string {
  const value = environment[name];
  if (!value || value.startsWith("replace-with")) throw new AuthError("unavailable");
  return value;
}

function origin(value: string, development: boolean): string {
  const url = new URL(value);
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/" ||
      (url.protocol !== "https:" && !(development && url.protocol === "http:" &&
        ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)))) throw new AuthError("unavailable");
  return url.origin;
}

export function authOrigin(environment: NodeJS.ProcessEnv = process.env): string {
  const site = required(environment, "STOREFRONT_ORIGIN");
  return origin(site, !site.startsWith("https://"));
}

export function authConfig(environment: NodeJS.ProcessEnv = process.env): AuthConfig {
  const siteOrigin = authOrigin(environment);
  const secure = new URL(siteOrigin).protocol === "https:";
  const issuer = origin(required(environment, "AUTH_OAUTH_ISSUER"), !secure);
  const kratos = origin(required(environment, "AUTH_KRATOS_URL"), !secure);
  const bridge = origin(required(environment, "AUTH_BRIDGE_URL"), !secure);
  if (new URL(issuer).protocol !== new URL(siteOrigin).protocol) throw new AuthError("unavailable");
  if (new URL(issuer).hostname !== new URL(siteOrigin).hostname) {
    const site = getPublicSuffix(new URL(siteOrigin).hostname);
    const provider = getPublicSuffix(new URL(issuer).hostname);
    if (!secure || !site || !provider || site !== provider) throw new AuthError("unavailable");
  }
  const clientSecret = required(environment, "AUTH_CLIENT_SECRET");
  const bridgeSecret = required(environment, "AUTH_BRIDGE_SECRET");
  if (clientSecret.length < 32 || bridgeSecret.length < 32) throw new AuthError("unavailable");
  let ring: unknown;
  try {
    ring = JSON.parse(required(environment, "AUTH_SEAL_KEYS"));
  } catch (error) {
    if (error instanceof SyntaxError) throw new AuthError("unavailable");
    throw error;
  }
  if (!ring || typeof ring !== "object" || !("active" in ring) || typeof ring.active !== "string" ||
      !("keys" in ring) || !ring.keys || typeof ring.keys !== "object" || Array.isArray(ring.keys)) {
    throw new AuthError("unavailable");
  }
  const keys = new Map<string, Uint8Array>();
  for (const [id, key] of Object.entries(ring.keys)) {
    if (!/^[A-Za-z0-9_-]{1,32}$/.test(id) || typeof key !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(key)) {
      throw new AuthError("unavailable");
    }
    const decoded = Buffer.from(key, "base64url");
    if (decoded.length !== 32) throw new AuthError("unavailable");
    keys.set(id, decoded);
  }
  if (!keys.has(ring.active)) throw new AuthError("unavailable");
  const databaseUrl = required(environment, "AUTH_DATABASE_URL");
  const database = new URL(databaseUrl);
  if (!["postgres:", "postgresql:"].includes(database.protocol) || !database.username ||
      !database.password || !database.hostname || database.pathname === "/" || database.hash ||
      (secure && (database.searchParams.get("sslmode") !== "verify-full" ||
        environment.NODE_TLS_REJECT_UNAUTHORIZED === "0"))) throw new AuthError("unavailable");
  return {
    origin: siteOrigin, issuer, kratos, bridge,
    clientId: required(environment, "AUTH_CLIENT_ID"), clientSecret, bridgeSecret,
    databaseUrl, secure,
    sessionCookie: secure ? "__Host-bc_session" : "bc_session",
    flowCookie: secure ? "__Host-bc_auth" : "bc_auth",
    activeKey: ring.active, keys,
  };
}
