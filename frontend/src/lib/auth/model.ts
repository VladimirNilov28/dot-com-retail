import { object, text, uuid, type UUID } from "@/lib/graphql/scalars";

export type AuthErrorCode = "invalid_request" | "forbidden" | "expired" | "unavailable" | "reauthentication_required" | "rate_limited" |
  "conflict" | "step_up_required";

export class AuthError extends Error {
  constructor(readonly code: AuthErrorCode, readonly status: number = 503) {
    super(`Authentication ${code}`);
    this.name = "AuthError";
  }
}

export type FormKind = "login" | "registration" | "verification" | "recovery" | "reset";
export function formKind(value: unknown): FormKind {
  if (value === "login" || value === "registration" || value === "verification" ||
      value === "recovery" || value === "reset") return value;
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
  securityReauth?: "aal1" | "aal2";
  social?: { state: string; flow: string; kind: "login" | "registration" | "settings" };
  googleRegistration?: { flow: string; email: string; submitted: boolean; providerValidation?: boolean; username?: string; dateOfBirth?: string; usernameUnavailable?: boolean };
  /** Emailed step-up challenge; `verifiedUntil` is set once the code is proven. */
  stepUp?: EmailChallenge & { verifiedUntil?: number };
  /** Pending, not yet applied, email address change. */
  emailChange?: EmailChallenge & { email: string };
  securityReturn?: AccountSection;
}

export interface EmailChallenge {
  sessionId: string;
  salt: string;
  digest: string;
  expiresAt: number;
  attempts: number;
}

export const accountSections = ["/account/security", "/account/connections", "/account/profile", "/account/sessions"] as const;
export type AccountSection = typeof accountSections[number];
export function accountSection(value: unknown): AccountSection {
  if (value === undefined) return "/account/security";
  if (typeof value === "string" && (accountSections as readonly string[]).includes(value)) return value as AccountSection;
  throw new AuthError("invalid_request", 400);
}

export function validEmail(value: unknown): value is string {
  return typeof value === "string" && value.length >= 3 && value.length <= 254 &&
    /^[^\s@\u0000-\u001f\u007f]+@[^\s@\u0000-\u001f\u007f]+\.[^\s@\u0000-\u001f\u007f]+$/.test(value);
}

function decodeChallenge(value: unknown): EmailChallenge {
  const input = object(value);
  if (!/^[0-9a-f-]{36}$/i.test(text(input.sessionId)) || !/^[A-Za-z0-9_-]{22,64}$/.test(text(input.salt)) ||
      !/^[0-9a-f]{64}$/.test(text(input.digest)) || typeof input.expiresAt !== "number" ||
      !Number.isSafeInteger(input.expiresAt) || typeof input.attempts !== "number" ||
      !Number.isSafeInteger(input.attempts) || input.attempts < 0 || input.attempts > 100) throw new AuthError("unavailable");
  return { sessionId: text(input.sessionId), salt: text(input.salt), digest: text(input.digest),
    expiresAt: input.expiresAt, attempts: input.attempts };
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
  if (url.hash || !/^(?:\/|\/catalog(?:\/[^/]+)?|\/products\/[^/]+|\/search|\/account(?:\/(?:security|connections|profile|sessions))?|\/cart)$/.test(path) ||
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
  if (input.securityReauth !== undefined) {
    if (input.securityReauth !== "aal1" && input.securityReauth !== "aal2") throw new AuthError("unavailable");
    state.securityReauth = input.securityReauth;
  }
  if (input.social !== undefined) {
    const social = object(input.social);
    if (typeof social.state !== "string" || social.state.length < 16 || social.state.length > 4096 ||
        /[\u0000-\u0020\u007f]/.test(social.state) ||
        (social.kind !== "login" && social.kind !== "registration" && social.kind !== "settings")) throw new AuthError("unavailable");
    state.social = { state: social.state, flow: flowIdentifier(social.flow), kind: social.kind };
  }
  if (input.googleRegistration !== undefined) {
    const registration = object(input.googleRegistration);
    if (typeof registration.email !== "string" || registration.email.length > 255 ||
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(registration.email) ||
        typeof registration.submitted !== "boolean" || (registration.providerValidation !== undefined &&
          typeof registration.providerValidation !== "boolean") || (registration.usernameUnavailable !== undefined &&
          typeof registration.usernameUnavailable !== "boolean")) throw new AuthError("unavailable");
    for (const [key, limit] of [["username", 255], ["dateOfBirth", 10]] as const) {
      if (registration[key] !== undefined && (typeof registration[key] !== "string" ||
          registration[key].length > limit || /[\u0000\r\n]/.test(registration[key]))) throw new AuthError("unavailable");
    }
    state.googleRegistration = { flow: flowIdentifier(registration.flow), email: registration.email,
      submitted: registration.submitted,
      providerValidation: typeof registration.providerValidation === "boolean" ? registration.providerValidation : undefined,
      username: typeof registration.username === "string" ? registration.username : undefined,
      dateOfBirth: typeof registration.dateOfBirth === "string" ? registration.dateOfBirth : undefined,
      ...(registration.usernameUnavailable === true ? { usernameUnavailable: true } : {}) };
  }
  if (input.stepUp !== undefined) {
    const stepUp = object(input.stepUp);
    state.stepUp = decodeChallenge(stepUp);
    if (stepUp.verifiedUntil !== undefined) {
      if (typeof stepUp.verifiedUntil !== "number" || !Number.isSafeInteger(stepUp.verifiedUntil)) throw new AuthError("unavailable");
      state.stepUp.verifiedUntil = stepUp.verifiedUntil;
    }
  }
  if (input.emailChange !== undefined) {
    const change = object(input.emailChange);
    if (!validEmail(change.email)) throw new AuthError("unavailable");
    state.emailChange = { ...decodeChallenge(change), email: change.email };
  }
  if (input.securityReturn !== undefined) {
    try { state.securityReturn = accountSection(input.securityReturn); } catch { throw new AuthError("unavailable"); }
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
