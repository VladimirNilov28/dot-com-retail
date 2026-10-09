import { object, text, uuid, type UUID } from "@/lib/graphql/scalars";

export type AuthErrorCode = "invalid_request" | "forbidden" | "expired" | "unavailable" | "reauthentication_required";

export class AuthError extends Error {
  constructor(readonly code: AuthErrorCode, readonly status: number = 503) {
    super(`Authentication ${code}`);
    this.name = "AuthError";
  }
}

export type FormKind = "login" | "registration" | "verification";
export function formKind(value: unknown): FormKind {
  if (value === "login" || value === "registration" || value === "verification") return value;
  throw new AuthError("invalid_request", 400);
}

export function flowIdentifier(value: unknown): UUID {
  try {
    return uuid(value);
  } catch (error) {
    if (error instanceof TypeError) throw new AuthError("invalid_request", 400);
    throw error;
  }
}
export type AccountRole = "USER" | "ADMIN" | "SUPPORT" | "CATALOG_MANAGER" | "ORDER_MANAGER" | "WAREHOUSE";

export interface ProviderSession {
  identityId: string;
  sessionId: string;
  expiresAt: string;
  userId: string;
  role: AccountRole;
  aal: "aal1" | "aal2";
}

export function assertSameProvider(expected: ProviderSession, actual: ProviderSession): void {
  if (actual.userId !== expected.userId || actual.identityId !== expected.identityId ||
      actual.sessionId !== expected.sessionId || actual.role !== expected.role ||
      actual.aal !== expected.aal || Date.parse(actual.expiresAt) <= Date.now()) {
    throw new AuthError("reauthentication_required", 401);
  }
}

export interface BrowserState {
  jar: string;
  csrf: string;
  returnTo: string;
  transactionState?: string;
  loginChallenge?: string;
  provider?: ProviderSession;
  tokens?: { accessToken: string; refreshToken: string; expiresAt: number };
  previousSessionHash?: string;
  revokeProvider?: boolean;
}

export function grantTokens(response: {
  access_token?: unknown; refresh_token?: unknown; expires_in?: unknown; scope?: unknown;
}): NonNullable<BrowserState["tokens"]> {
  if (typeof response.access_token !== "string" || !response.access_token ||
      typeof response.refresh_token !== "string" || !response.refresh_token ||
      typeof response.expires_in !== "number" || !Number.isSafeInteger(response.expires_in) ||
      response.expires_in <= 0 || response.expires_in > 86400 ||
      typeof response.scope !== "string" ||
      response.scope.split(" ").sort().join(" ") !== "offline_access openid user:read") {
    throw new AuthError("unavailable");
  }
  return { accessToken: response.access_token, refreshToken: response.refresh_token,
    expiresAt: Date.now() + response.expires_in * 1000 };
}

export interface OAuthTransaction {
  state: string;
  nonce: string;
  verifier: string;
  returnTo: string;
}

export function accountRole(value: unknown): AccountRole {
  if (value === "USER" || value === "ADMIN" || value === "SUPPORT" || value === "CATALOG_MANAGER" ||
      value === "ORDER_MANAGER" || value === "WAREHOUSE") return value;
  throw new TypeError("Unknown account role");
}

export function safeReturnTo(value: unknown): string {
  if (value === undefined || value === null) return "/";
  if (typeof value !== "string" || value.length > 2048 || !value.startsWith("/") ||
      value.startsWith("//") || value !== value.trim()) throw new AuthError("invalid_request", 400);
  const url = new URL(value, "https://storefront.invalid");
  let decoded = value.split(/[?#]/, 1)[0];
  try {
    for (let iteration = 0; iteration < 8; iteration++) {
      if (/[\\\u0000-\u001f\u007f]/.test(decoded)) throw new AuthError("invalid_request", 400);
      if (!/%[0-9a-f]{2}/i.test(decoded)) break;
      const next = decodeURIComponent(decoded);
      if (next === decoded) break;
      decoded = next;
      if (iteration === 7) throw new AuthError("invalid_request", 400);
    }
  } catch (error) {
    if (error instanceof URIError) throw new AuthError("invalid_request", 400);
    throw error;
  }
  const path = decoded.split(/[?#]/, 1)[0];
  if (path.startsWith("//") || path.split("/").some((part) => part === "." || part === "..")) {
    throw new AuthError("invalid_request", 400);
  }
  if (url.hash || !/^(?:\/|\/catalog(?:\/[^/]+)?|\/products\/[^/]+|\/search|\/account|\/cart)$/.test(path) ||
      Array.from(url.searchParams).some(([key, item]) => /[\\\u0000-\u001f\u007f]/.test(key + item)) ||
      Array.from(url.searchParams.keys()).some((key) =>
        ["access_token", "refresh_token", "id_token", "client_secret", "password", "code", "csrf_token"].includes(key))) {
    throw new AuthError("invalid_request", 400);
  }
  return url.pathname + url.search;
}

export function oneParameter(parameters: URLSearchParams, name: string): string | undefined {
  const values = parameters.getAll(name);
  if (values.length > 1) throw new AuthError("invalid_request", 400);
  return values[0];
}

export function opaqueIdentifier(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(value)) throw new AuthError("expired", 401);
  return value;
}

export function authCookie(header: string | null, name: string): string | undefined {
  let selected: string | undefined;
  for (const part of header?.split(";") ?? []) {
    const separator = part.indexOf("=");
    const candidate = (separator < 0 ? part : part.slice(0, separator)).trim();
    if (candidate !== name) continue;
    if (separator < 0 || selected !== undefined) throw new AuthError("expired", 401);
    selected = opaqueIdentifier(part.slice(separator + 1).trim());
  }
  return selected;
}

export function hasSessionCookie(header: string | null): boolean {
  return (header?.split(";") ?? []).some((part) =>
    ["bc_session", "__Host-bc_session"].includes(part.split("=", 1)[0].trim()));
}

export function decodeProviderSession(value: unknown): ProviderSession {
  const input = object(value);
  if (input.active !== true) throw new AuthError("expired", 401);
  const identityId = text(input.identity_id);
  const sessionId = text(input.session_id);
  const expiresAt = text(input.expires_at);
  const userId = text(input.user_id);
  if (!/^[0-9a-f-]{36}$/i.test(identityId) || !/^[0-9a-f-]{36}$/i.test(sessionId) ||
      !/^[1-9][0-9]{0,18}$/.test(userId) || !Number.isFinite(Date.parse(expiresAt)) ||
      (input.aal !== "aal1" && input.aal !== "aal2")) throw new AuthError("unavailable");
  return { identityId, sessionId, expiresAt, userId, role: accountRole(input.role), aal: input.aal };
}

export function decodeBrowserState(value: unknown): BrowserState {
  const input = object(value);
  const jar = text(input.jar);
  const csrf = opaqueIdentifier(input.csrf);
  const state: BrowserState = { jar, csrf, returnTo: safeReturnTo(text(input.returnTo)) };
  if (input.transactionState !== undefined) state.transactionState = opaqueIdentifier(input.transactionState);
  if (input.loginChallenge !== undefined) {
    const challenge = text(input.loginChallenge);
    if (!challenge || challenge.length > 8192) throw new AuthError("unavailable");
    state.loginChallenge = challenge;
  }
  if (input.provider !== undefined) {
    const provider = object(input.provider);
    state.provider = decodeProviderSession({
      active: true, identity_id: provider.identityId, session_id: provider.sessionId,
      expires_at: provider.expiresAt, user_id: provider.userId, role: provider.role, aal: provider.aal,
    });
  }
  if (input.previousSessionHash !== undefined) {
    const previous = text(input.previousSessionHash);
    if (!/^[0-9a-f]{64}$/.test(previous)) throw new AuthError("unavailable");
    state.previousSessionHash = previous;
  }
  if (input.revokeProvider !== undefined) {
    if (typeof input.revokeProvider !== "boolean") throw new AuthError("unavailable");
    state.revokeProvider = input.revokeProvider;
  }
  if (input.tokens !== undefined) {
    const tokens = object(input.tokens);
    if (typeof tokens.expiresAt !== "number" || !Number.isSafeInteger(tokens.expiresAt)) {
      throw new AuthError("unavailable");
    }
    state.tokens = { accessToken: text(tokens.accessToken), refreshToken: text(tokens.refreshToken), expiresAt: tokens.expiresAt };
  }
  return state;
}

export function decodeTransaction(value: unknown): OAuthTransaction {
  const input = object(value);
  return {
    state: opaqueIdentifier(input.state), nonce: opaqueIdentifier(input.nonce),
    verifier: opaqueIdentifier(input.verifier), returnTo: safeReturnTo(text(input.returnTo)),
  };
}
