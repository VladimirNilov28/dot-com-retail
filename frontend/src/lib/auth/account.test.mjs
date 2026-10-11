import { afterAll, beforeAll, beforeEach, describe, expect, mock, test } from "bun:test";
import * as store from "./store";
import { emptyJar } from "./provider";

let allowAttempt = true;
const attempts = [];
mock.module("./store", () => ({ ...store, consumeAttempt: async (...args) => { attempts.push(args); return allowAttempt; } }));
const { accountAction, challengeMatches, challengeUsable, decodeAccountStatus, maskEmail, newChallenge, recoveryMode,
  sessionFacts, stepUpView } = await import("./account");
const { securitySettingsFlow } = await import("./settings");
const { decodeBrowserState } = await import("./model");

const identityId = "22222222-2222-4222-8222-222222222222";
const sessionId = "33333333-3333-4333-8333-333333333333";
const otherSession = "44444444-4444-4444-8444-444444444444";
const flowId = "11111111-1111-4111-8111-111111111111";
const provider = { identityId, sessionId, expiresAt: new Date(Date.now() + 3600000).toISOString(), userId: "42", role: "USER", aal: "aal1" };
const names = ["STOREFRONT_ORIGIN", "AUTH_OAUTH_ISSUER", "AUTH_KRATOS_URL", "AUTH_BRIDGE_URL",
  "AUTH_CLIENT_ID", "AUTH_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "AUTH_DATABASE_URL", "AUTH_SEAL_KEYS"];
let server, previous, session, credentials, mail, emailReply, kratosPosts, revoked;

const iso = (offset = 0) => new Date(Date.now() + offset).toISOString();
const canonical = () => ({ active: true, identity_id: identityId, session_id: sessionId, expires_at: provider.expiresAt,
  user_id: "42", role: "USER", aal: session.authenticator_assurance_level });
const state = () => ({ jar: emptyJar(), csrf: "a".repeat(43), returnTo: "/account/security", provider: { ...provider },
  tokens: { accessToken: "fixture", refreshToken: "fixture", expiresAt: Date.now() + 3600000 } });
const form = (values) => new URLSearchParams({ section: "/account/profile", ...values });
const sentCode = (template) => mail.filter((item) => item.template === template).at(-1)?.code;

function settingsFlow() {
  return {
    id: flowId, type: "browser", expires_at: iso(600000), state: "show_form",
    identity: { id: identityId, schema_id: "customer-v1" },
    ui: { action: `${server.url.origin}/self-service/settings?flow=${flowId}`, method: "POST", nodes: [
      { type: "input", group: "default", attributes: { name: "csrf_token", type: "hidden", value: "provider-csrf" } },
      { type: "input", group: "password", attributes: { name: "password", type: "password" } },
      { type: "input", group: "oidc", attributes: { name: "unlink", type: "submit", value: "google" } },
      { type: "input", group: "totp", attributes: { name: "totp_code", type: "text" } },
    ] },
  };
}

beforeAll(() => {
  server = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch(request) {
    const path = new URL(request.url).pathname;
    if (path === "/sessions/whoami") return Response.json(session);
    if (path === "/sessions" && request.method === "DELETE") { revoked.push("others"); return Response.json({ count: 1 }); }
    if (path.startsWith("/sessions/") && request.method === "DELETE") {
      const id = path.split("/").at(-1);
      if (id !== otherSession) return new Response(null, { status: 404 });
      revoked.push(id);
      return new Response(null, { status: 204 });
    }
    if (path === "/sessions") return Response.json([{ ...session, id: otherSession }]);
    if (path === "/internal/session") return Response.json(canonical());
    if (path === "/internal/account/status") return Response.json({ session: canonical(), credentials,
      recovery_codes_remaining: credentials.lookup_secret ? 7 : 0,
      profile: { email: "owner@example.test", email_verified: true, username: "fixture", date_of_birth: null } });
    if (path === "/internal/account/notify") { mail.push(await request.json()); return new Response(null, { status: 204 }); }
    if (path === "/internal/account/email") {
      const body = await request.json();
      mail.push({ template: "email_changed", email: body.email });
      return emailReply === 409 ? Response.json({ error: "conflict" }, { status: 409 }) : Response.json({ email: body.email });
    }
    if (path === "/self-service/settings" && request.method === "POST") {
      kratosPosts.push(await request.json());
      return Response.json(settingsFlow());
    }
    if (path.startsWith("/self-service/settings")) return Response.json(settingsFlow());
    throw new Error(`Unexpected request ${request.method} ${path}`);
  } });
  previous = Object.fromEntries(names.map((name) => [name, process.env[name]]));
  Object.assign(process.env, {
    STOREFRONT_ORIGIN: "http://127.0.0.1:3300", AUTH_OAUTH_ISSUER: "http://127.0.0.1:24444",
    AUTH_KRATOS_URL: server.url.origin, AUTH_BRIDGE_URL: server.url.origin, AUTH_CLIENT_ID: "fixture-client",
    AUTH_CLIENT_SECRET: "fixture-client-secret-not-issued-32", AUTH_BRIDGE_SECRET: "fixture-bridge-secret-not-issued-32",
    AUTH_DATABASE_URL: `postgres://fixture:${"not-a-secret"}@127.0.0.1:25432/fixture`,
    AUTH_SEAL_KEYS: JSON.stringify({ active: "fixture", keys: { fixture: Buffer.alloc(32, 1).toString("base64url") } }),
  });
});
beforeEach(() => {
  allowAttempt = true;
  attempts.length = 0;
  mail = []; kratosPosts = []; revoked = []; emailReply = 200;
  credentials = { password: true, google: false, totp: false, lookup_secret: false };
  session = { active: true, id: sessionId, identity: { id: identityId, schema_id: "customer-v1" }, authenticated_at: iso(),
    expires_at: provider.expiresAt, authenticator_assurance_level: "aal1",
    authentication_methods: [{ method: "password", completed_at: iso() }] };
});
afterAll(() => {
  server.stop(true);
  for (const name of names) {
    if (previous[name] === undefined) delete process.env[name];
    else process.env[name] = previous[name];
  }
});

describe("step-up policy", () => {
  const facts = (methods, authenticatedAt = Date.now()) => ({ authenticatedAt,
    methods: methods.map(([method, ago]) => ({ method, completedAt: Date.now() - ago })) });

  test("an enrolled authenticator can never be satisfied by an email code", () => {
    const current = { ...state(), stepUp: { ...newChallenge(sessionId, 600000).challenge, verifiedUntil: Date.now() + 600000 } };
    const view = stepUpView(current, facts([["password", 0]]), true);
    expect(view.method).toBe("authenticator");
    expect(view.ready).toBe(false);
    expect(stepUpView(current, facts([["password", 0], ["totp", 1000]]), true).ready).toBe(true);
    expect(stepUpView(current, facts([["password", 0], ["totp", 11 * 60000]]), true).ready).toBe(false);
  });

  test("email step-up is bound to the provider session and expires", () => {
    const current = { ...state(), stepUp: { ...newChallenge(otherSession, 600000).challenge, verifiedUntil: Date.now() + 600000 } };
    expect(stepUpView(current, facts([["password", 0]]), false).ready).toBe(false);
    current.stepUp = { ...newChallenge(sessionId, 600000).challenge, verifiedUntil: Date.now() - 1 };
    expect(stepUpView(current, facts([["password", 0]]), false).verified).toBe(false);
  });

  test("stale sign-ins are never fresh even when verified", () => {
    const current = { ...state(), stepUp: { ...newChallenge(sessionId, 600000).challenge, verifiedUntil: Date.now() + 600000 } };
    const view = stepUpView(current, facts([["password", 16 * 60000]], Date.now() - 16 * 60000), false);
    expect(view.fresh).toBe(false);
    expect(view.ready).toBe(false);
  });

  test("a recovery-code sign-in switches the authenticator account to email verification", () => {
    const recovered = facts([["password", 2000], ["lookup_secret", 1000]]);
    expect(recoveryMode(recovered)).toBe(true);
    expect(stepUpView(state(), recovered, true)).toMatchObject({ method: "email", recoveryMode: true });
    expect(recoveryMode(facts([["password", 3000], ["lookup_secret", 2000], ["totp", 1000]]))).toBe(false);
    expect(recoveryMode(facts([["password", 0]]))).toBe(false);
  });

  test("session facts reject foreign sessions and malformed methods", () => {
    expect(() => sessionFacts({ ...session, id: otherSession }, provider)).toThrow();
    expect(() => sessionFacts({ ...session, authentication_methods: [{ method: "password" }] }, provider)).toThrow();
  });
});

describe("one-time codes", () => {
  test("codes are random, hashed, compared in constant time and bound to session, expiry and attempts", () => {
    const { code, challenge } = newChallenge(sessionId, 1000, 0);
    expect(code).toMatch(/^[0-9]{6}$/);
    expect(JSON.stringify(challenge).includes(code)).toBe(false);
    expect(challengeMatches(challenge, code)).toBe(true);
    expect(challengeMatches(challenge, "abcdef")).toBe(false);
    expect(challengeUsable(challenge, provider, 999)).toBe(true);
    expect(challengeUsable(challenge, provider, 1000)).toBe(false);
    expect(challengeUsable(challenge, { ...provider, sessionId: otherSession }, 0)).toBe(false);
    expect(challengeUsable({ ...challenge, attempts: 5 }, provider, 0)).toBe(false);
  });

  test("browser state round-trips challenges and drops malformed ones", () => {
    const { challenge } = newChallenge(sessionId, 1000);
    const encoded = JSON.parse(JSON.stringify({ ...state(), stepUp: challenge, emailChange: { ...challenge, email: "new@example.test" } }));
    const decoded = decodeBrowserState(encoded);
    expect(decoded.stepUp).toEqual(challenge);
    expect(decoded.emailChange.email).toBe("new@example.test");
    expect(() => decodeBrowserState({ ...encoded, stepUp: { ...challenge, digest: "short" } })).toThrow();
  });

  test("account status projection is strict and masks nothing sensitive", () => {
    const value = { session: canonical(), credentials, recovery_codes_remaining: 0,
      profile: { email: "owner@example.test", email_verified: true, username: "fixture", date_of_birth: "1990-06-01" } };
    expect(decodeAccountStatus(value, provider).profile.dateOfBirth).toBe("1990-06-01");
    expect(() => decodeAccountStatus({ ...value, credentials: { ...credentials, totp: "yes" } }, provider)).toThrow();
    expect(() => decodeAccountStatus({ ...value, session: { ...canonical(), identity_id: sessionId } }, provider)).toThrow();
    expect(maskEmail("owner@example.test")).toBe("o***@e***.test");
  });
});

describe("account actions", () => {
  test("email step-up code is single-use, attempt-limited and expires", async () => {
    const current = state();
    expect(await accountAction(current, form({ action: "step_up_send" }))).toEqual({ stepup: "sent" });
    const code = sentCode("step_up_code");
    expect(current.stepUp.digest).not.toContain(code);
    expect(await accountAction(current, form({ action: "step_up_verify", code: code === "000000" ? "000001" : "000000" })))
      .toEqual({ stepup: "invalid" });
    expect(await accountAction(current, form({ action: "step_up_verify", code }))).toEqual({ stepup: "verified" });
    expect(await accountAction(current, form({ action: "step_up_verify", code }))).toEqual({ stepup: "expired" });

    const exhausted = state();
    await accountAction(exhausted, form({ action: "step_up_send" }));
    const wrong = sentCode("step_up_code") === "000000" ? "000001" : "000000";
    for (let index = 0; index < 4; index++) await accountAction(exhausted, form({ action: "step_up_verify", code: wrong }));
    expect(await accountAction(exhausted, form({ action: "step_up_verify", code: wrong }))).toEqual({ stepup: "limited" });
    expect(exhausted.stepUp).toBeUndefined();

    const expired = state();
    await accountAction(expired, form({ action: "step_up_send" }));
    expired.stepUp.expiresAt = Date.now() - 1;
    expect(await accountAction(expired, form({ action: "step_up_verify", code: sentCode("step_up_code") }))).toEqual({ stepup: "expired" });
  });

  test("email step-up is refused for authenticator accounts and stale sessions", async () => {
    credentials.totp = true;
    await expect(accountAction(state(), form({ action: "step_up_send" }))).rejects.toMatchObject({ code: "invalid_request" });
    credentials.totp = false;
    session.authenticated_at = iso(-16 * 60000);
    await expect(accountAction(state(), form({ action: "step_up_send" }))).rejects.toMatchObject({ code: "reauthentication_required" });
    expect(mail).toEqual([]);
  });

  test("step-up delivery is rate limited per identity", async () => {
    allowAttempt = false;
    expect(await accountAction(state(), form({ action: "step_up_send" }))).toEqual({ stepup: "limited" });
    expect(attempts[0].slice(0, 2)).toEqual(["step_up_send", identityId]);
    expect(mail).toEqual([]);
  });

  async function verified() {
    const current = state();
    await accountAction(current, form({ action: "step_up_send" }));
    await accountAction(current, form({ action: "step_up_verify", code: sentCode("step_up_code") }));
    return current;
  }

  test("email change requires step-up, proves the new address and only then applies it", async () => {
    await expect(accountAction(state(), form({ action: "email_request", email: "new@example.test" })))
      .rejects.toMatchObject({ code: "step_up_required" });
    const current = await verified();
    expect(await accountAction(current, form({ action: "email_request", email: "OWNER@example.test" }))).toEqual({ email: "same" });
    expect(await accountAction(current, form({ action: "email_request", email: "not-an-email" }))).toEqual({ email: "invalid" });
    expect(await accountAction(current, form({ action: "email_request", email: "new@example.test" }))).toEqual({ email: "sent" });
    const request = mail.find((item) => item.template === "email_change_code");
    expect(request.new_email).toBe("new@example.test");
    expect(mail.some((item) => item.template === "email_changed")).toBe(false);
    expect(await accountAction(current, form({ action: "email_verify", code: request.code }))).toEqual({ email: "changed" });
    expect(mail.at(-1)).toEqual({ template: "email_changed", email: "new@example.test" });
    expect(current.emailChange).toBeUndefined();
    expect(await accountAction(current, form({ action: "email_verify", code: request.code }))).toEqual({ email: "expired" });
  });

  test("an email conflict is reported without applying or retaining the change", async () => {
    const current = await verified();
    await accountAction(current, form({ action: "email_request", email: "taken@example.test" }));
    emailReply = 409;
    expect(await accountAction(current, form({ action: "email_verify", code: sentCode("email_change_code") }))).toEqual({ email: "conflict" });
    expect(current.emailChange).toBeUndefined();
  });

  test("a recovery-code session cannot change the email address", async () => {
    session.authentication_methods.push({ method: "lookup_secret", completed_at: iso() });
    credentials.totp = true;
    const current = await verified();
    expect(await accountAction(current, form({ action: "email_request", email: "new@example.test" }))).toEqual({ email: "recovery_mode" });
    expect(mail.some((item) => item.template === "email_change_code")).toBe(false);
  });

  test("session management revokes only other sessions and never the current one", async () => {
    await expect(accountAction(state(), form({ action: "session_revoke", session: sessionId }))).rejects.toMatchObject({ code: "invalid_request" });
    expect(await accountAction(state(), form({ action: "session_revoke", session: otherSession }))).toEqual({ sessions: "revoked" });
    expect(await accountAction(state(), form({ action: "session_revoke", session: flowId }))).toEqual({ sessions: "missing" });
    expect(await accountAction(state(), form({ action: "sessions_revoke_others" }))).toEqual({ sessions: "revoked_all" });
    expect(revoked).toEqual([otherSession, "others"]);
    expect(mail.at(-1)).toMatchObject({ template: "security_event", event: "sessions_revoked" });
  });

  test("unknown fields, duplicated fields and non-customer sessions are rejected", async () => {
    await expect(accountAction(state(), form({ action: "step_up_send", role: "ADMIN" }))).rejects.toMatchObject({ code: "invalid_request" });
    const duplicated = form({ action: "step_up_send" });
    duplicated.append("action", "email_cancel");
    await expect(accountAction(state(), duplicated)).rejects.toMatchObject({ code: "invalid_request" });
    await expect(accountAction({ ...state(), provider: { ...provider, role: "ADMIN" } }, form({ action: "step_up_send" })))
      .rejects.toMatchObject({ code: "forbidden" });
  });
});

describe("credential changes through settings", () => {
  const client = { query: async () => ({ rowCount: 1, rows: [] }) };
  const browser = "b".repeat(43);
  const submit = (current, values) => securitySettingsFlow(current, browser, client, flowId, new URLSearchParams(values));

  test("no credential change reaches Kratos without step-up", async () => {
    await expect(submit(state(), { action: "password", password: "Fresh-passphrase-1", password_confirm: "Fresh-passphrase-1" }))
      .rejects.toMatchObject({ code: "step_up_required" });
    expect(kratosPosts).toEqual([]);
  });

  test("password confirmation and policy are checked before the provider", async () => {
    const current = await (async () => {
      const value = state();
      await accountAction(value, form({ action: "step_up_send" }));
      await accountAction(value, form({ action: "step_up_verify", code: sentCode("step_up_code") }));
      return value;
    })();
    expect((await submit(current, { action: "password", password: "Fresh-passphrase-1", password_confirm: "different" })).notice)
      .toBe("password_mismatch");
    expect((await submit(current, { action: "password", password: "short", password_confirm: "short" })).notice).toBe("minimum");
    expect(kratosPosts).toEqual([]);
    await submit(current, { action: "password", password: "Fresh-passphrase-1", password_confirm: "Fresh-passphrase-1" });
    expect(kratosPosts).toEqual([{ method: "password", csrf_token: "provider-csrf", password: "Fresh-passphrase-1" }]);
  });

  test("Google cannot be disconnected when it is the last sign-in method", async () => {
    credentials = { password: false, google: true, totp: false, lookup_secret: false };
    const current = state();
    await accountAction(current, form({ action: "step_up_send" }));
    await accountAction(current, form({ action: "step_up_verify", code: sentCode("step_up_code") }));
    expect((await submit(current, { action: "google_disconnect", acknowledge: "yes" })).notice).toBe("last_method");
    expect(kratosPosts).toEqual([]);
  });

  test("a recovery-code session may only replace the authenticator", async () => {
    session.authentication_methods.push({ method: "lookup_secret", completed_at: iso() });
    const current = state();
    await accountAction(current, form({ action: "step_up_send" }));
    await accountAction(current, form({ action: "step_up_verify", code: sentCode("step_up_code") }));
    expect((await submit(current, { action: "password", password: "Fresh-passphrase-1", password_confirm: "Fresh-passphrase-1" })).notice)
      .toBe("recovery_mode");
    expect(kratosPosts).toEqual([]);
  });
});
