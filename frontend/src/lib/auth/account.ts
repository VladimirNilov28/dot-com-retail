import "server-only";
import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";
import { object } from "@/lib/graphql/scalars";
import { assertCsrf, authBoundary, authHeaders, boundedForm, browserCookie, redirectResponse } from "./http";
import { AuthError, accountSection, assertSameProvider, decodeProviderSession, flowIdentifier, oneParameter, validEmail,
  type AccountSection, type BrowserState, type EmailChallenge, type ProviderSession } from "./model";
import { bridgeRequest, providerClient, providerCookie, providerFailure } from "./provider";
import { authenticatedState } from "./session";
import { consumeAttempt, withBrowser } from "./store";

export const stepUpLifetime = 10 * 60 * 1000;
export const emailChangeLifetime = 15 * 60 * 1000;
export const privilegedAge = 15 * 60 * 1000;
const maxCodeAttempts = 5;

export interface AccountStatus {
  credentials: { password: boolean; google: boolean; totp: boolean; lookupSecret: boolean };
  recoveryCodesRemaining: number;
  profile: { email: string; emailVerified: boolean; username: string; dateOfBirth?: string; googleEmail?: string };
}

function record(value: unknown): Record<string, unknown> {
  try { return object(value); } catch { throw new AuthError("unavailable"); }
}

function flag(value: unknown): boolean {
  if (typeof value !== "boolean") throw new AuthError("unavailable");
  return value;
}

/** Validate the bridge projection; it never carries secrets, hashes, subjects or code values. */
export function decodeAccountStatus(value: unknown, expected: ProviderSession): AccountStatus {
  const input = record(value);
  assertSameProvider(expected, decodeProviderSession(input.session));
  const credentials = record(input.credentials);
  const profile = record(input.profile);
  const remaining = input.recovery_codes_remaining;
  if (typeof remaining !== "number" || !Number.isSafeInteger(remaining) || remaining < 0 || remaining > 64 ||
      !validEmail(profile.email) || typeof profile.username !== "string" || !profile.username ||
      profile.username.length > 255 || (profile.date_of_birth !== null && profile.date_of_birth !== undefined &&
        (typeof profile.date_of_birth !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(profile.date_of_birth))) ||
      (profile.google_email != null && !validEmail(profile.google_email))) {
    throw new AuthError("unavailable");
  }
  return {
    credentials: { password: flag(credentials.password), google: flag(credentials.google), totp: flag(credentials.totp),
      lookupSecret: flag(credentials.lookup_secret) },
    recoveryCodesRemaining: remaining,
    profile: { email: profile.email, emailVerified: flag(profile.email_verified), username: profile.username,
      ...(typeof profile.date_of_birth === "string" ? { dateOfBirth: profile.date_of_birth } : {}),
      ...(typeof profile.google_email === "string" ? { googleEmail: profile.google_email } : {}) },
  };
}

export async function accountStatus(state: BrowserState): Promise<AccountStatus> {
  if (!state.provider || state.provider.role !== "USER") throw new AuthError("forbidden", 403);
  return decodeAccountStatus(await bridgeRequest("account/status", { cookie: await providerCookie(state) }), state.provider);
}

function digest(salt: string, code: string): string {
  return createHash("sha256").update(`${salt}:${code}`).digest("hex");
}

export function newChallenge(sessionId: string, lifetime: number, now = Date.now()): { code: string; challenge: EmailChallenge } {
  const code = randomInt(0, 1000000).toString().padStart(6, "0");
  const salt = randomBytes(16).toString("base64url");
  return { code, challenge: { sessionId, salt, digest: digest(salt, code), expiresAt: now + lifetime, attempts: 0 } };
}

export function challengeMatches(challenge: EmailChallenge, code: string): boolean {
  if (!/^[0-9]{6}$/.test(code)) return false;
  return timingSafeEqual(Buffer.from(digest(challenge.salt, code), "hex"), Buffer.from(challenge.digest, "hex"));
}

export function challengeUsable(challenge: EmailChallenge | undefined, provider: ProviderSession, now = Date.now()):
  challenge is EmailChallenge {
  return Boolean(challenge && challenge.sessionId === provider.sessionId && challenge.expiresAt > now &&
    challenge.attempts < maxCodeAttempts);
}

export interface SessionFacts {
  authenticatedAt: number;
  methods: { method: string; completedAt: number }[];
}

/** Read only the timing and method facts needed for step-up from the bound provider session. */
export function sessionFacts(value: unknown, expected: ProviderSession): SessionFacts {
  const session = record(value);
  const identity = record(session.identity);
  if (session.active !== true || session.id !== expected.sessionId || identity.id !== expected.identityId) {
    throw new AuthError("reauthentication_required", 401);
  }
  const authenticatedAt = typeof session.authenticated_at === "string" ? Date.parse(session.authenticated_at) : NaN;
  if (!Number.isFinite(authenticatedAt)) throw new AuthError("unavailable");
  const raw = session.authentication_methods ?? [];
  if (!Array.isArray(raw) || raw.length > 64) throw new AuthError("unavailable");
  const methods = raw.map((item) => {
    const entry = record(item);
    const completedAt = typeof entry.completed_at === "string" ? Date.parse(entry.completed_at) : NaN;
    if (typeof entry.method !== "string" || !Number.isFinite(completedAt)) throw new AuthError("unavailable");
    return { method: entry.method, completedAt };
  });
  return { authenticatedAt, methods };
}

/** True when this session's latest second factor was a recovery code. */
export function recoveryMode(facts: SessionFacts): boolean {
  const factors = facts.methods.filter((entry) => entry.method === "totp" || entry.method === "lookup_secret");
  return factors.length > 0 && factors.reduce((latest, entry) => entry.completedAt >= latest.completedAt ? entry : latest)
    .method === "lookup_secret";
}

export interface StepUpView {
  method: "authenticator" | "email";
  fresh: boolean;
  verified: boolean;
  codeSent: boolean;
  ready: boolean;
  recoveryMode: boolean;
}

/**
 * TOTP takes priority: an enrolled authenticator can only be satisfied by a
 * fresh TOTP sign-in, never by email. Email is used without TOTP, or after a
 * recovery-code sign-in when the authenticator is presumed lost.
 */
export function stepUpView(state: BrowserState, facts: SessionFacts, totpEnabled: boolean, now = Date.now()): StepUpView {
  if (!state.provider) throw new AuthError("expired", 401);
  const recovery = recoveryMode(facts);
  const method = totpEnabled && !recovery ? "authenticator" : "email";
  const fresh = facts.authenticatedAt <= now + 5000 && now - facts.authenticatedAt < privilegedAge;
  const stepUp = state.stepUp?.sessionId === state.provider.sessionId ? state.stepUp : undefined;
  const verified = method === "authenticator"
    ? facts.methods.some((entry) => entry.method === "totp" && entry.completedAt <= now + 5000 &&
      now - entry.completedAt < stepUpLifetime)
    : (stepUp?.verifiedUntil ?? 0) > now;
  const codeSent = method === "email" && !verified && !stepUp?.verifiedUntil && challengeUsable(stepUp, state.provider, now);
  return { method, fresh, verified, codeSent, ready: fresh && verified, recoveryMode: recovery };
}

export function assertStepUp(view: StepUpView): void {
  if (!view.fresh) throw new AuthError("reauthentication_required", 401);
  if (!view.verified) throw new AuthError("step_up_required", 403);
}

export type SecurityEvent = "password_set" | "password_changed" | "totp_enabled" | "totp_disabled" |
  "recovery_codes_regenerated" | "recovery_codes_disabled" | "google_connected" | "google_disconnected" |
  "sessions_revoked" | "mfa_recovery_used";

/** Best effort: a notification failure never reverses an already applied change. */
export async function notifySecurityEvent(state: BrowserState, event: SecurityEvent, remaining?: number): Promise<void> {
  try {
    await bridgeRequest("account/notify", { cookie: await providerCookie(state), template: "security_event", event,
      ...(remaining !== undefined ? { remaining } : {}) });
  } catch (error) {
    console.error("account notification failed", event, error instanceof AuthError ? error.code : "upstream");
  }
}

export function maskEmail(email: string): string {
  const [local, domain] = email.split("@");
  const [host, ...rest] = domain.split(".");
  return `${local.slice(0, 1)}***@${host.slice(0, 1)}***${rest.length ? `.${rest.join(".")}` : ""}`;
}

export interface SessionView {
  id: string;
  current: boolean;
  authenticatedAt: string;
  expiresAt: string;
  methods: string[];
}

const methodLabels: Record<string, string> = {
  password: "Password", oidc: "Google", totp: "Authenticator", lookup_secret: "Recovery code", code: "Email code",
};

export function projectSession(value: unknown, current: boolean): SessionView {
  const session = record(value);
  const id = flowIdentifier(session.id);
  if (session.active !== true || typeof session.authenticated_at !== "string" || typeof session.expires_at !== "string" ||
      !Number.isFinite(Date.parse(session.authenticated_at)) || !Number.isFinite(Date.parse(session.expires_at))) {
    throw new AuthError("unavailable");
  }
  const raw = Array.isArray(session.authentication_methods) ? session.authentication_methods.slice(0, 16) : [];
  const methods = [...new Set(raw.map((entry) => methodLabels[String(record(entry).method)]).filter(Boolean))];
  return { id, current, authenticatedAt: session.authenticated_at, expiresAt: session.expires_at, methods };
}

export interface AccountView {
  csrf: string;
  status: AccountStatus;
  stepUp: StepUpView;
  maskedEmail: string;
  emailChange?: { email: string };
  sessions?: SessionView[];
}

export async function accountView(state: BrowserState, sessions = false): Promise<AccountView> {
  if (!state.provider || !state.tokens || state.provider.role !== "USER") throw new AuthError("forbidden", 403);
  const provider = providerClient(state);
  try {
    const current = (await provider.api.toSession()).data;
    const facts = sessionFacts(current, state.provider);
    const status = await accountStatus(state);
    const view: AccountView = {
      csrf: state.csrf, status, stepUp: stepUpView(state, facts, status.credentials.totp),
      maskedEmail: maskEmail(status.profile.email),
      ...(challengeUsable(state.emailChange, state.provider) ? { emailChange: { email: state.emailChange.email } } : {}),
    };
    if (sessions) {
      const others = (await provider.api.listMySessions({ perPage: 50 })).data;
      if (!Array.isArray(others)) throw new AuthError("unavailable");
      view.sessions = [projectSession(current, true), ...others.filter((item) => record(item).id !== state.provider?.sessionId)
        .map((item) => projectSession(item, false))];
    }
    return view;
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    if ([401, 403].includes(failure.status)) throw new AuthError("reauthentication_required", 401);
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}

export async function loadAccount(browser: string, sessions = false): Promise<AccountView> {
  await authenticatedState(browser);
  return withBrowser(browser, async (state, _client, row) => {
    if (!row.authenticated) throw new AuthError("expired", 401);
    return accountView(state, sessions);
  });
}

type Outcome = Record<string, string>;
const formFields = ["_csrf", "action", "section", "code", "email", "session"];

async function sessionContext(state: BrowserState) {
  if (!state.provider || state.provider.role !== "USER") throw new AuthError("forbidden", 403);
  const provider = providerClient(state);
  try {
    const facts = sessionFacts((await provider.api.toSession()).data, state.provider);
    return { provider, facts };
  } catch (error) {
    provider.save();
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    if ([401, 403].includes(failure.status)) throw new AuthError("reauthentication_required", 401);
    throw new AuthError("unavailable");
  }
}

export async function accountAction(state: BrowserState, form: URLSearchParams, now = Date.now()): Promise<Outcome> {
  for (const name of form.keys()) {
    if (!formFields.includes(name) || form.getAll(name).length !== 1) throw new AuthError("invalid_request", 400);
  }
  const action = oneParameter(form, "action");
  const { provider, facts } = await sessionContext(state);
  const session = state.provider as ProviderSession;
  try {
    if (action === "session_revoke") {
      const id = flowIdentifier(oneParameter(form, "session"));
      if (id === session.sessionId) throw new AuthError("invalid_request", 400);
      try {
        await provider.api.disableMySession({ id });
      } catch (error) {
        if (providerFailure(error).status === 404) return { sessions: "missing" };
        throw error;
      }
      return { sessions: "revoked" };
    }
    if (action === "sessions_revoke_others") {
      await provider.api.disableMyOtherSessions();
      provider.save();
      await notifySecurityEvent(state, "sessions_revoked");
      return { sessions: "revoked_all" };
    }
    const status = await accountStatus(state);
    const view = stepUpView(state, facts, status.credentials.totp, now);
    if (action === "step_up_send") {
      if (view.method !== "email") throw new AuthError("invalid_request", 400);
      if (!view.fresh) throw new AuthError("reauthentication_required", 401);
      if (!await consumeAttempt("step_up_send", session.identityId, 3, 900)) return { stepup: "limited" };
      const { code, challenge } = newChallenge(session.sessionId, stepUpLifetime, now);
      try {
        await bridgeRequest("account/notify", { cookie: await providerCookie(state), template: "step_up_code", code });
      } catch (error) {
        console.error("account step-up delivery failed", error instanceof AuthError ? error.code : "upstream");
        return { stepup: "unavailable" };
      }
      state.stepUp = challenge;
      return { stepup: "sent" };
    }
    if (action === "step_up_verify") {
      if (view.method !== "email") throw new AuthError("invalid_request", 400);
      const code = oneParameter(form, "code")?.trim() ?? "";
      const challenge = state.stepUp;
      if (!challengeUsable(challenge, session, now) || challenge.verifiedUntil) {
        delete state.stepUp;
        return { stepup: "expired" };
      }
      challenge.attempts += 1;
      if (!await consumeAttempt("step_up_verify", session.identityId, 10, 3600)) return { stepup: "limited" };
      if (!challengeMatches(challenge, code)) {
        if (challenge.attempts >= maxCodeAttempts) { delete state.stepUp; return { stepup: "limited" }; }
        return { stepup: "invalid" };
      }
      // Single use: the proven code is replaced by an unusable digest.
      state.stepUp = { ...challenge, digest: randomBytes(32).toString("hex"), verifiedUntil: now + stepUpLifetime };
      return { stepup: "verified" };
    }
    if (action === "email_request" || action === "email_use_google") {
      assertStepUp(view);
      if (view.recoveryMode) return { email: "recovery_mode" };
      // The Google address comes only from trusted server-side provider data, never the form.
      if (action === "email_use_google" && (!status.credentials.google || !status.profile.googleEmail)) {
        return { email: "invalid" };
      }
      const email = action === "email_use_google" ? status.profile.googleEmail ?? "" : oneParameter(form, "email")?.trim() ?? "";
      if (!validEmail(email)) return { email: "invalid" };
      if (email.toLowerCase() === status.profile.email.toLowerCase()) return { email: "same" };
      if (!await consumeAttempt("email_change_send", session.identityId, 3, 3600)) return { email: "limited" };
      const { code, challenge } = newChallenge(session.sessionId, emailChangeLifetime, now);
      try {
        await bridgeRequest("account/notify", { cookie: await providerCookie(state), template: "email_change_code",
          code, new_email: email });
      } catch (error) {
        console.error("account email-change delivery failed", error instanceof AuthError ? error.code : "upstream");
        return { email: "unavailable" };
      }
      state.emailChange = { ...challenge, email };
      return { email: "sent" };
    }
    if (action === "email_verify") {
      const code = oneParameter(form, "code")?.trim() ?? "";
      const change = state.emailChange;
      if (!challengeUsable(change, session, now)) { delete state.emailChange; return { email: "expired" }; }
      change.attempts += 1;
      if (!await consumeAttempt("email_change_verify", session.identityId, 10, 3600)) return { email: "limited" };
      if (!challengeMatches(change, code)) {
        if (change.attempts >= maxCodeAttempts) { delete state.emailChange; return { email: "limited" }; }
        return { email: "invalid" };
      }
      try {
        await bridgeRequest("account/email", { cookie: await providerCookie(state), email: change.email });
      } catch (error) {
        if (error instanceof AuthError && error.code === "conflict") { delete state.emailChange; return { email: "conflict" }; }
        console.error("account email change failed", error instanceof AuthError ? error.code : "upstream");
        return { email: "unavailable" };
      }
      delete state.emailChange;
      return { email: "changed" };
    }
    if (action === "email_cancel") { delete state.emailChange; return { email: "cancelled" }; }
    throw new AuthError("invalid_request", 400);
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    if ([401, 403].includes(failure.status)) throw new AuthError("reauthentication_required", 401);
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}

export function sectionLocation(section: AccountSection, outcome: Outcome): string {
  const query = new URLSearchParams(outcome).toString();
  return query ? `${section}?${query}` : section;
}

/** POST /auth/account: step-up, email change and session management actions. */
export async function handleAccount(request: NextRequest): Promise<NextResponse> {
  let section: AccountSection = "/account/security";
  return authBoundary(async () => {
    if (request.method !== "POST") {
      return NextResponse.json({ error: "invalid_request" }, { status: 405, headers: { ...authHeaders, Allow: "POST" } });
    }
    const form = await boundedForm(request);
    section = accountSection(oneParameter(form, "section"));
    const browser = browserCookie(request, true);
    try {
      await authenticatedState(browser);
      const outcome = await withBrowser(browser, async (state, _client, row) => {
        if (!row.authenticated) throw new AuthError("expired", 401);
        assertCsrf(request, form, state.csrf);
        return accountAction(state, form);
      });
      return redirectResponse(sectionLocation(section, outcome));
    } catch (error) {
      if (error instanceof AuthError && error.code === "reauthentication_required") {
        return redirectResponse(sectionLocation(section, { reauth: "required" }));
      }
      if (error instanceof AuthError && error.code === "step_up_required") {
        return redirectResponse(sectionLocation(section, { stepup: "required" }));
      }
      throw error;
    }
  }, "account", request, async () => section);
}
