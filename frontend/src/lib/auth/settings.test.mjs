import { afterAll, beforeAll, beforeEach, expect, test } from "bun:test";
import { assertSecuritySession, projectSecuritySettings, securityPayload, securitySettingsFlow } from "./settings";
import { assertCsrf } from "./http";
import { emptyJar } from "./provider";

const id = "11111111-1111-4111-8111-111111111111";
const identityId = "22222222-2222-4222-8222-222222222222";
const sessionId = "33333333-3333-4333-8333-333333333333";
const provider = { identityId, sessionId, expiresAt: new Date(Date.now() + 3600000).toISOString(), userId: "42", role: "USER", aal: "aal1" };
const png = `data:image/png;base64,${Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).toString("base64")}`;
const secret = "JBSWY3DPEHPK3PXP";
let server, previous, posts, readCount, queries, reply, postReply, responseStatus, session, sessionIdentity, denyBinding;
const names = ["STOREFRONT_ORIGIN", "AUTH_OAUTH_ISSUER", "AUTH_KRATOS_URL", "AUTH_BRIDGE_URL",
  "AUTH_CLIENT_ID", "AUTH_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "AUTH_DATABASE_URL", "AUTH_SEAL_KEYS"];
const input = (group, name, value = true) => ({ type: "input", group, attributes: { name, type: "submit", value } });
const textNode = (group, id, context) => ({ type: "text", group, attributes: { id, text: { context } } });
function flow(nodes = setupNodes()) {
  return {
    id, type: "browser", expires_at: new Date(Date.now() + 600000).toISOString(), state: "show_form",
    identity: { id: identityId, schema_id: "customer-v1", traits: { email: "private@example.test" } },
    ui: { action: `${server.url.origin}/self-service/settings?flow=${id}`, method: "POST", nodes: [
      { type: "input", group: "default", attributes: { name: "csrf_token", type: "hidden", value: "provider-csrf" } },
      ...nodes,
    ] },
  };
}
function setupNodes() {
  return [
    input("totp", "totp_code"),
    { type: "img", group: "totp", attributes: { id: "totp_qr", src: png } },
    textNode("totp", "totp_secret_key", { secret }),
    input("lookup_secret", "lookup_secret_regenerate"),
  ];
}
function enabledNodes() {
  return [input("totp", "totp_unlink"), ...["regenerate", "reveal", "disable"].map((suffix) =>
    input("lookup_secret", `lookup_secret_${suffix}`))];
}
function state() {
  return { jar: emptyJar(), csrf: "a".repeat(43), returnTo: "/account/security",
    provider: { ...provider }, tokens: { accessToken: "fixture", refreshToken: "fixture", expiresAt: Date.now() + 3600000 } };
}
const client = {
  async query(sql, values) {
    queries.push({ sql, values });
    return { rowCount: denyBinding && sql.startsWith("SELECT") ? 0 : 1, rows: [] };
  },
};

beforeAll(() => {
  server = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    async fetch(request) {
      const path = new URL(request.url).pathname;
      if (path === "/sessions/whoami") return Response.json(session);
      if (path === "/internal/session") return Response.json({
        active: true, identity_id: identityId, session_id: sessionId, expires_at: provider.expiresAt,
        user_id: "42", role: "USER", aal: session.authenticator_assurance_level,
      });
      if (request.method === "POST") {
        posts.push(await request.json());
        if (postReply?.state === "success") session.authenticator_assurance_level = "aal2";
        return Response.json(postReply ?? reply, { status: responseStatus, headers: { "Set-Cookie": "ory_kratos_session=fixture; Path=/; HttpOnly" } });
      }
      readCount++;
      return Response.json(reply);
    },
  });
  previous = Object.fromEntries(names.map((name) => [name, process.env[name]]));
  Object.assign(process.env, {
    STOREFRONT_ORIGIN: "http://127.0.0.1:3300", AUTH_OAUTH_ISSUER: "http://127.0.0.1:24444",
    AUTH_KRATOS_URL: server.url.origin, AUTH_BRIDGE_URL: server.url.origin, AUTH_CLIENT_ID: "fixture-client",
    AUTH_CLIENT_SECRET: "fixture-client-secret-not-issued-32", AUTH_BRIDGE_SECRET: "fixture-bridge-secret-not-issued-32",
    AUTH_DATABASE_URL: "postgres://fixture:fixture@127.0.0.1:25432/fixture",
    AUTH_SEAL_KEYS: JSON.stringify({ active: "fixture", keys: { fixture: Buffer.alloc(32, 1).toString("base64url") } }),
  });
});
beforeEach(() => {
  posts = []; readCount = 0; queries = []; responseStatus = 200; denyBinding = false; postReply = undefined;
  sessionIdentity = { id: identityId, schema_id: "customer-v1" };
  session = { active: true, id: sessionId, identity: sessionIdentity,
    authenticated_at: new Date().toISOString(), expires_at: provider.expiresAt, authenticator_assurance_level: "aal1" };
  reply = flow();
});
afterAll(() => {
  server.stop(true);
  for (const name of names) {
    if (previous[name] === undefined) delete process.env[name];
    else process.env[name] = previous[name];
  }
});

test("safe projection includes only genuine provider setup data and explicit MFA actions", () => {
  const result = projectSecuritySettings(reply, state());
  expect(result.secret === secret && result.qr === png).toBe(true);
  expect(result.actions).toEqual(["totp_confirm", "backup_generate"]);
  expect(result.totpEnabled).toBe(false);
  expect("identity" in result || "jar" in result || "traits" in result).toBe(false);
  for (const unsafe of ["https://qr.example.test/secret", "data:image/svg+xml;base64,PHN2Zz4=", "data:image/png;base64,AAAA"]) {
    const invalid = flow([{ type: "img", group: "totp", attributes: { id: "totp_qr", src: unsafe } }]);
    expect(() => projectSecuritySettings(invalid, state())).toThrow();
  }
});

test("rejects foreign identities, staff schemas, wrong endpoint, duplicate IDs and expired/API flows", () => {
  for (const change of [
    { identity: { id: sessionId, schema_id: "customer-v1" } },
    { identity: { id: identityId, schema_id: "default" } },
    { type: "api" },
    { expires_at: new Date(0).toISOString() },
    { ui: { ...reply.ui, action: `${server.url.origin}/self-service/login?flow=${id}` } },
    { ui: { ...reply.ui, action: `${reply.ui.action}&flow=${id}` } },
  ]) expect(() => projectSecuritySettings({ ...reply, ...change }, state())).toThrow();
});

test("projects generated/revealed backup codes without provider messages or context leakage", () => {
  const result = projectSecuritySettings(flow([
    ...enabledNodes(), input("lookup_secret", "lookup_secret_confirm"),
    textNode("lookup_secret", "lookup_secret_codes", { secrets: [
      { text: "not rendered", context: { secret: "fixture-one", used: false } },
      { context: { secret: "fixture-two", used: true } },
    ] }),
  ]), state());
  expect(result.codes.length).toBe(2);
  expect(result.codes[1].used).toBe(true);
  expect(result.totpEnabled && result.backupEnabled).toBe(true);
  expect(result.actions.includes("backup_confirm")).toBe(true);
  expect(JSON.stringify(result).includes("not rendered")).toBe(false);
});

test("strictly allowlists native TOTP/lookup submissions and never trusts posted provider CSRF", () => {
  const settings = projectSecuritySettings(flow([...setupNodes(), ...enabledNodes(), input("lookup_secret", "lookup_secret_confirm")]), state());
  expect(securityPayload(new URLSearchParams({ action: "totp_confirm", totp_code: "001234" }), settings, "provider-csrf"))
    .toEqual({ method: "totp", csrf_token: "provider-csrf", totp_code: "001234" });
  for (const action of ["totp_disable", "backup_disable", "backup_confirm"]) {
    expect(() => securityPayload(new URLSearchParams({ action }), settings, "fixture")).toThrow();
    expect(securityPayload(new URLSearchParams({ action, acknowledge: "yes" }), settings, "fixture").method)
      .toBe(action === "totp_disable" ? "totp" : "lookup_secret");
  }
  for (const body of [
    "action=totp_confirm&totp_code=12345", "action=totp_confirm&totp_code=123456&totp_code=123456",
    "action=backup_generate&csrf_token=injected", "action=backup_generate&traits.email=attacker",
    "action=totp_disable&acknowledge=yes&totp_code=123456", "action=password",
  ]) expect(() => securityPayload(new URLSearchParams(body), settings, "fixture")).toThrow();
});

test("session check rejects stale reauth, lower AAL, foreign session and non-customer identity", () => {
  const enabled = projectSecuritySettings(flow(enabledNodes()), state());
  expect(() => assertSecuritySession(session, provider, enabled)).toThrow();
  const elevated = { ...provider, aal: "aal2" };
  const fresh = { ...session, authenticator_assurance_level: "aal2" };
  expect(() => assertSecuritySession(fresh, elevated, enabled)).not.toThrow();
  for (const change of [
    { id: identityId }, { identity: { ...sessionIdentity, id: sessionId } },
    { identity: { ...sessionIdentity, schema_id: "default" } }, { active: false },
    { authenticated_at: new Date(Date.now() - 900001).toISOString() },
    { authenticated_at: new Date(Date.now() + 60000).toISOString() },
    { authenticated_at: undefined }, { expires_at: new Date(0).toISOString() },
  ]) expect(() => assertSecuritySession({ ...fresh, ...change }, elevated, enabled)).toThrow();
});

test("same-origin browser CSRF protection rejects foreign, missing and duplicated tokens", () => {
  const origin = process.env.STOREFRONT_ORIGIN;
  expect(() => assertCsrf(new Request(`${origin}/auth/security`, { headers: { Origin: origin } }),
    new URLSearchParams({ _csrf: state().csrf }), state().csrf)).not.toThrow();
  for (const [source, body] of [
    ["https://foreign.example", `_csrf=${state().csrf}`], [origin, ""],
    [origin, `_csrf=${state().csrf}&_csrf=${state().csrf}`],
  ]) expect(() => assertCsrf(new Request(`${origin}/auth/security`, { headers: { Origin: source } }),
    new URLSearchParams(body), state().csrf)).toThrow();
});

test("native browser settings are created and read only with bound browser/identity/session", async () => {
  const created = await securitySettingsFlow(state(), "b".repeat(43), client);
  expect(created.id).toBe(id);
  expect(queries.some((query) => query.sql.startsWith("INSERT") && query.values[2] === "settings")).toBe(true);
  await securitySettingsFlow(state(), "b".repeat(43), client, id);
  expect(queries.some((query) => query.sql.startsWith("SELECT") && query.values[2] === "settings")).toBe(true);
  denyBinding = true;
  const reads = readCount;
  await expect(securitySettingsFlow(state(), "c".repeat(43), client, id)).rejects.toThrow();
  expect(readCount).toBe(reads);
  denyBinding = false;
  session.id = identityId;
  await expect(securitySettingsFlow(state(), "b".repeat(43), client, id)).rejects.toThrow();
  expect(readCount).toBe(reads);
});

test("submits provider CSRF from bound flow, persists jar, and maps invalid codes without reflecting messages", async () => {
  reply.ui.messages = [{ type: "error", text: "private provider message" }];
  responseStatus = 400;
  const current = state();
  const result = await securitySettingsFlow(current, "b".repeat(43), client, id,
    new URLSearchParams({ action: "totp_confirm", totp_code: "001234" }));
  expect(posts.length).toBe(1);
  expect(posts[0].csrf_token === "provider-csrf" && posts[0].totp_code === "001234").toBe(true);
  expect(current.jar.includes("ory_kratos_session")).toBe(true);
  expect(result.invalid).toBe(true);
  expect(JSON.stringify(result).includes("private provider message")).toBe(false);
});

test("mutation refuses stale reauthentication before posting any credential changes", async () => {
  session.authenticated_at = new Date(Date.now() - 900001).toISOString();
  await expect(securitySettingsFlow(state(), "b".repeat(43), client, id,
    new URLSearchParams({ action: "backup_generate" }))).rejects.toThrow();
  expect(posts.length).toBe(0);
});

test("native success with rotated action ID creates a new strict bound flow instead of relaxing validation", async () => {
  postReply = { ...flow(enabledNodes()), state: "success",
    ui: { ...flow(enabledNodes()).ui, action: `${server.url.origin}/self-service/settings?flow=${identityId}` } };
  const current = state();
  const result = await securitySettingsFlow(current, "b".repeat(43), client, id,
    new URLSearchParams({ action: "totp_confirm", totp_code: "001234" }));
  expect(result.saved).toBe(true);
  expect(current.provider.aal).toBe("aal2");
  expect(queries.some((query) => query.sql.startsWith("INSERT") && query.values[2] === "settings")).toBe(true);
  expect(() => projectSecuritySettings(postReply, current)).toThrow();
});

test("provider service diagnostics are unavailable, never incorrect authenticator code", () => {
  const invalid = flow();
  invalid.ui.messages = [{ id: 5000001, type: "error", text: "private provider failure" }];
  expect(() => projectSecuritySettings(invalid, state())).toThrow();
});
