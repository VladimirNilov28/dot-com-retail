import { afterAll, beforeAll, expect, test } from "bun:test";
import { completeSocialCallback, googleAuthorization, socialCallbackParameters, socialFailureReason } from "./social";
import { emptyJar } from "./provider";
import { formMethods } from "./view";

const names = ["STOREFRONT_ORIGIN", "AUTH_OAUTH_ISSUER", "AUTH_KRATOS_URL", "AUTH_BRIDGE_URL",
  "AUTH_CLIENT_ID", "AUTH_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "AUTH_DATABASE_URL", "AUTH_SEAL_KEYS"];
const flow = "11111111-1111-4111-8111-111111111111";
const nonce = "opaque-provider-state-not-issued-32";
const registrationFlow = "22222222-2222-4222-8222-222222222222";
let previous, server, callbacks = 0, callbackFailure, registrationContinuation, registrationReads = 0;
let callbackSuccess, callbackLocation, bridge, availability = { email_available: true, username_available: true };
const availabilityRequests = [];
function nativeLogin() {
  return { id: flow, type: "browser", expires_at: new Date(Date.now() + 600000).toISOString(),
    ui: { action: `${server.url.origin}/self-service/login?flow=${flow}`, method: "POST", nodes: [
      { type: "input", group: "default", attributes: { name: "csrf_token", type: "hidden", value: "fixture-csrf" } },
      { type: "input", group: "password", attributes: { name: "method", type: "submit", value: "password" } },
    ] } };
}
function nativeRegistration() {
  return { id: registrationFlow, type: "browser", expires_at: new Date(Date.now() + 600000).toISOString(),
    ui: { action: `${server.url.origin}/self-service/registration?flow=${registrationFlow}`, method: "POST",
      nodes: [{ type: "input", group: "default", attributes: { name: "csrf_token", type: "hidden", value: "fixture-csrf" } },
        { type: "input", group: "default", attributes: { name: "traits.email", type: "email", value: "verified@example.test" } },
        ...["traits.username", "traits.dateOfBirth"].map((name) => ({ type: "input", group: "default",
          attributes: { name, type: "text", required: true }, messages: [{ id: 4000002, type: "error" }] })),
        { type: "input", group: "oidc", attributes: { name: "provider", type: "submit", value: "google" } }] } };
}
beforeAll(() => {
  bridge = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch(request) {
    if (new URL(request.url).pathname !== "/internal/registration/availability") throw new Error("Unexpected bridge request");
    availabilityRequests.push({ authorization: request.headers.get("authorization"), body: await request.json() });
    return Response.json(availability);
  } });
  server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
    if (new URL(request.url).pathname.includes("/callback/")) {
      callbacks++;
      if (callbackSuccess) return Response.json(callbackSuccess, {
        headers: { "Set-Cookie": "ory_kratos_session=fixture; Path=/; HttpOnly" } });
      if (callbackLocation) return new Response(null, { status: 303, headers: { Location: callbackLocation } });
      if (registrationContinuation) return Response.json(registrationContinuation, { status: 400,
        headers: { "Set-Cookie": "ory_kratos_continuity=fixture; Path=/; HttpOnly" } });
      if (callbackFailure) return Response.json(callbackFailure, { status: 500,
        headers: { "Set-Cookie": "ory_kratos_continuity=fixture; Path=/; HttpOnly" } });
      return new Response(null, { status: 303, headers: { Location: "http://127.0.0.1:3300/auth/resume",
        "Set-Cookie": "ory_kratos_session=fixture; Path=/; HttpOnly" } });
    }
    if (new URL(request.url).pathname === "/self-service/registration/flows") {
      registrationReads++;
      expect(request.headers.get("cookie")).toContain("ory_kratos_continuity=fixture");
      return Response.json(nativeRegistration());
    }
    if (new URL(request.url).pathname === "/self-service/login/browser") return Response.json(nativeLogin());
    throw new Error("Unexpected fixture request");
  } });
  previous = Object.fromEntries(names.map((name) => [name, process.env[name]]));
  Object.assign(process.env, {
    STOREFRONT_ORIGIN: "http://127.0.0.1:3300", AUTH_OAUTH_ISSUER: "http://127.0.0.1:24444",
    AUTH_KRATOS_URL: server.url.origin, AUTH_BRIDGE_URL: bridge.url.origin, AUTH_CLIENT_ID: "fixture-client",
    AUTH_CLIENT_SECRET: "fixture-client-secret-not-issued-32", AUTH_BRIDGE_SECRET: "fixture-bridge-secret-not-issued-32",
    AUTH_DATABASE_URL: "postgres://fixture:fixture@127.0.0.1:25432/fixture",
    AUTH_SEAL_KEYS: JSON.stringify({ active: "fixture", keys: { fixture: Buffer.alloc(32, 1).toString("base64url") } }),
  });
});
afterAll(() => {
  server.stop(true);
  bridge.stop(true);
  for (const name of names) if (previous[name] === undefined) delete process.env[name]; else process.env[name] = previous[name];
});

test("callback diagnostics classify only allowlisted provider failures, never raw response fields", async () => {
  const failure = { error: { debug: 'oauth2: cannot fetch token: 401; {"error":"invalid_client","client_secret":"private-value"}',
    reason: "untrusted provider diagnostic" } };
  expect(socialFailureReason(failure)).toBe("token_exchange_invalid_client");
  expect(socialFailureReason({ error: { debug: 'oauth2: "invalid_grant"' } })).toBe("token_exchange_invalid_grant");
  expect(socialFailureReason({ error: { reason: "private-value" } })).toBe("provider_callback_rejected");
  const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/account", social: { state: nonce, flow, kind: "login" } };
  callbackFailure = failure;
  try {
    await expect(completeSocialCallback(state, "b".repeat(43),
      new URLSearchParams({ state: nonce, code: "fixture-code" }), { query: async () => ({ rowCount: 1 }) }))
      .rejects.toMatchObject({ code: "unavailable" });
    expect(state.jar.includes("ory_kratos_continuity")).toBe(true);
  } finally { callbackFailure = undefined; }
});

test("Google redirect allows only actual provider origin with exact BFF callback and opaque state", () => {
  const url = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  url.searchParams.set("redirect_uri", "http://127.0.0.1:3300/self-service/methods/oidc/callback/google");
  url.searchParams.set("state", nonce);
  expect(googleAuthorization(url.href).origin).toBe("https://accounts.google.com");
  for (const change of [
    (x) => x.hostname = "accounts.google.com.attacker.test", (x) => x.protocol = "http:",
    (x) => x.pathname = "/untrusted", (x) => x.hash = "#fragment",
    (x) => x.searchParams.set("redirect_uri", "https://attacker.test/callback"),
    (x) => x.searchParams.append("state", nonce), (x) => x.searchParams.delete("state"),
  ]) { const altered = new URL(url); change(altered); expect(() => googleAuthorization(altered.href)).toThrow(); }
});
test("callback carries exact state/code or cancellation, excluding raw provider diagnostics", () => {
  expect(socialCallbackParameters(new URLSearchParams({ state: nonce, code: "fixture-code", error_description: "private" }), nonce).toString())
    .toBe(`state=${nonce}&code=fixture-code`);
  for (const query of [
    `state=${nonce}&state=${nonce}&code=x`, `state=foreign&code=x`,
    `state=${nonce}&code=x&error=access_denied`, `state=${nonce}&error=unknown`,
  ]) expect(() => socialCallbackParameters(new URLSearchParams(query), nonce)).toThrow();
});
test("provider callback preserves encrypted jar continuity and refuses replay before upstream work", async () => {
  const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/account", social: { state: nonce, flow, kind: "login" } };
  const client = { query: async () => ({ rowCount: 1 }) };
  const query = new URLSearchParams({ state: nonce, code: "fixture-code" });
  const response = await completeSocialCallback(state, "b".repeat(43), query, client);
  expect(response.headers.get("location")).toBe("http://127.0.0.1:3300/auth/resume");
  expect(state.jar.includes("ory_kratos_session")).toBe(true);
  expect(state.social).toBeUndefined();
  const before = callbacks;
  await expect(completeSocialCallback(state, "b".repeat(43), query, client)).rejects.toThrow();
  expect(callbacks).toBe(before);
});
test("Google entry carries no profile fields; the bound completion requests real missing traits without password/email or privileged fields", () => {
  const input = (name, group, type, value = "") => ({ type: "input", group, attributes: { name, type, value, required: true } });
  const nodes = [input("csrf_token", "default", "hidden", "fixture"),
    input("traits.username", "password", "text"), input("traits.dateOfBirth", "password", "date"),
    input("traits.email", "password", "email"), input("password", "password", "password"),
    input("method", "password", "submit", "password"), input("provider", "oidc", "submit", "google")];
  const methods = formMethods(nodes, "registration");
  const social = methods.find((method) => method.name === "oidc");
  expect(social.provider).toBe("google");
  expect(social.fields.map((field) => field.name)).toEqual(["csrf_token"]);
  const completion = formMethods(nodes, "registration", { flow, email: "verified@example.test", submitted: false });
  expect(completion.map((method) => method.name)).toEqual(["oidc"]);
  expect(completion[0].fields.filter((field) => !field.readOnly).map((field) => field.name))
    .toEqual(["csrf_token", "traits.username", "traits.dateOfBirth"]);
  expect(formMethods(nodes.filter((node) => node.group !== "oidc"), "registration").some((method) => method.name === "oidc")).toBe(false);
});

test("native callback 400 registration form continues missing-trait onboarding in the same bound provider jar", async () => {
  const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/account", social: { state: nonce, flow, kind: "login" } };
  const queries = [];
  const before = registrationReads;
  registrationContinuation = nativeRegistration();
  try {
    const response = await completeSocialCallback(state, "b".repeat(43),
      new URLSearchParams({ state: nonce, code: "fixture-code" }),
      { query: async (sql, values) => { queries.push({ sql, values }); return { rowCount: 1 }; } });
    const destination = new URL(response.headers.get("location"));
    expect(destination.pathname).toBe("/register");
    expect(destination.searchParams.get("flow")).toBe(registrationFlow);
    expect(destination.searchParams.get("returnTo")).toBe("/account");
    expect(registrationReads).toBe(before + 1);
    expect(queries.some(({ sql, values }) => sql.startsWith("INSERT INTO browser_flows") &&
      values[0] === registrationFlow && values[2] === "registration")).toBe(true);
    expect(state.social).toBeUndefined();
    expect(state.jar.includes("ory_kratos_continuity")).toBe(true);
    expect(state.googleRegistration).toEqual({ flow: registrationFlow, email: "verified@example.test", submitted: false,
      providerValidation: false });
  } finally { registrationContinuation = undefined; }
});

test("verified Google email owned by another account routes to explicit sign-in before the completion form", async () => {
  const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/cart", social: { state: nonce, flow, kind: "login" } };
  const bindings = [];
  availability = { email_available: false, username_available: true };
  registrationContinuation = nativeRegistration();
  availabilityRequests.length = 0;
  try {
    const response = await completeSocialCallback(state, "b".repeat(43),
      new URLSearchParams({ state: nonce, code: "fixture-code" }),
      { query: async (sql, values) => { if (sql.startsWith("INSERT")) bindings.push(values); return { rowCount: 1 }; } });
    const destination = new URL(response.headers.get("location"));
    expect(destination.pathname).toBe("/login");
    expect(destination.searchParams.get("social")).toBe("conflict");
    expect(destination.searchParams.get("returnTo")).toBe("/cart");
    expect(bindings.some((values) => values[0] === registrationFlow)).toBe(false);
    expect(state.googleRegistration).toBeUndefined();
    expect(availabilityRequests).toEqual([{ authorization: "Bearer fixture-bridge-secret-not-issued-32",
      body: { email: "verified@example.test" } }]);
  } finally { registrationContinuation = undefined; availability = { email_available: true, username_available: true }; }
});

test("canonical username conflict after provider round trip becomes a retained field error, not a generic rejection", async () => {
  const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/account", social: { state: nonce, flow: registrationFlow, kind: "registration" },
    googleRegistration: { flow: registrationFlow, email: "verified@example.test", submitted: true, providerValidation: false,
      username: "taken-name", dateOfBirth: "2000-01-02" } };
  availability = { email_available: true, username_available: false };
  registrationContinuation = nativeRegistration();
  availabilityRequests.length = 0;
  try {
    const response = await completeSocialCallback(state, "b".repeat(43),
      new URLSearchParams({ state: nonce, code: "fixture-code" }), { query: async () => ({ rowCount: 1 }) });
    expect(new URL(response.headers.get("location")).pathname).toBe("/register");
    expect(availabilityRequests[0].body).toEqual({ email: "verified@example.test", username: "taken-name" });
    expect(state.googleRegistration).toEqual({ flow: registrationFlow, email: "verified@example.test", submitted: true,
      providerValidation: false, username: "taken-name", dateOfBirth: "2000-01-02", usernameUnavailable: true });
    const google = formMethods(nativeRegistration().ui.nodes, "registration", state.googleRegistration)[0];
    expect(google.label).toBe("Complete registration");
    expect(google.fields.find((field) => field.name === "traits.username"))
      .toMatchObject({ value: "taken-name", error: "This username is already taken. Choose another." });
    expect(google.fields.find((field) => field.name === "traits.dateOfBirth")).toMatchObject({ value: "2000-01-02", error: undefined });
  } finally { registrationContinuation = undefined; availability = { email_available: true, username_available: true }; }
});

test("callback registration continuation cannot bind a malformed or foreign flow action", async () => {
  const before = registrationReads;
  const continuation = nativeRegistration();
  continuation.ui.action = `${server.url.origin}/self-service/registration?flow=${flow}`;
  registrationContinuation = continuation;
  let bindings = 0;
  try {
    const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/account", social: { state: nonce, flow, kind: "login" } };
    await expect(completeSocialCallback(state, "b".repeat(43),
      new URLSearchParams({ state: nonce, code: "fixture-code" }),
      { query: async (sql) => { if (sql.startsWith("INSERT")) bindings++; return { rowCount: 1 }; } }))
      .rejects.toMatchObject({ code: "unavailable" });
    expect(bindings).toBe(0);
    expect(registrationReads).toBe(before);
  } finally { registrationContinuation = undefined; }
});

test("native completed registration JSON proceeds only to the canonical assurance-checked handoff", async () => {
  callbackSuccess = { identity: { id: registrationFlow, schema_id: "customer-v1" }, continue_with: [] };
  const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/cart", social: { state: nonce, flow, kind: "registration" } };
  try {
    const response = await completeSocialCallback(state, "b".repeat(43),
      new URLSearchParams({ state: nonce, code: "fixture-code" }), { query: async () => ({ rowCount: 1 }) });
    expect(response.headers.get("location")).toBe("http://127.0.0.1:3300/auth/resume");
    expect(state.provider).toBeUndefined();
    expect(state.tokens).toBeUndefined();
    expect(state.returnTo).toBe("/cart");
    expect(state.jar.includes("ory_kratos_session")).toBe(true);
  } finally { callbackSuccess = undefined; }
});

test("email-conflict continuation is discarded in favor of fresh existing-account authentication and explicit settings linking", async () => {
  for (const json of [false, true]) {
    const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/account",
      social: { state: nonce, flow, kind: "registration" } };
    if (json) registrationContinuation = nativeLogin();
    else callbackLocation = `http://127.0.0.1:3300/login?flow=${registrationFlow}&no_org_ui=true`;
    try {
      const response = await completeSocialCallback(state, "b".repeat(43),
        new URLSearchParams({ state: nonce, code: "fixture-code" }), { query: async () => ({ rowCount: 1 }) });
      const destination = new URL(response.headers.get("location"));
      expect(destination.pathname).toBe("/login");
      expect(destination.searchParams.get("social")).toBe("conflict");
      expect(destination.searchParams.get("flow")).toBe(flow);
      expect(state.social).toBeUndefined();
      expect(state.tokens).toBeUndefined();
    } finally { registrationContinuation = undefined; callbackLocation = undefined; }
  }
});

test("Google's AAL2 continuation creates a bound second-factor flow rather than bypassing assurance", async () => {
  callbackLocation = `${server.url.origin}/self-service/login/browser?aal=aal2&return_to=${encodeURIComponent("http://127.0.0.1:3300/auth/resume")}`;
  const state = { csrf: "a".repeat(43), jar: emptyJar(), returnTo: "/cart", social: { state: nonce, flow, kind: "login" } };
  try {
    const response = await completeSocialCallback(state, "b".repeat(43),
      new URLSearchParams({ state: nonce, code: "fixture-code" }), { query: async () => ({ rowCount: 1 }) });
    expect(new URL(response.headers.get("location")).pathname).toBe("/login");
    expect(state.tokens).toBeUndefined();
  } finally { callbackLocation = undefined; }
});
