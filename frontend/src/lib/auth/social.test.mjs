import { afterAll, beforeAll, expect, test } from "bun:test";
import { completeSocialCallback, googleAuthorization, socialCallbackParameters } from "./social";
import { emptyJar } from "./provider";
import { formMethods } from "./view";

const names = ["STOREFRONT_ORIGIN", "AUTH_OAUTH_ISSUER", "AUTH_KRATOS_URL", "AUTH_BRIDGE_URL",
  "AUTH_CLIENT_ID", "AUTH_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "AUTH_DATABASE_URL", "AUTH_SEAL_KEYS"];
const flow = "11111111-1111-4111-8111-111111111111";
const nonce = "opaque-provider-state-not-issued-32";
let previous, server, callbacks = 0;
beforeAll(() => {
  server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(request) {
    if (new URL(request.url).pathname.includes("/callback/")) {
      callbacks++;
      return new Response(null, { status: 303, headers: { Location: "http://127.0.0.1:3300/auth/resume",
        "Set-Cookie": "ory_kratos_session=fixture; Path=/; HttpOnly" } });
    }
    throw new Error("Unexpected fixture request");
  } });
  previous = Object.fromEntries(names.map((name) => [name, process.env[name]]));
  Object.assign(process.env, {
    STOREFRONT_ORIGIN: "http://127.0.0.1:3300", AUTH_OAUTH_ISSUER: "http://127.0.0.1:24444",
    AUTH_KRATOS_URL: server.url.origin, AUTH_BRIDGE_URL: "http://127.0.0.1:24446", AUTH_CLIENT_ID: "fixture-client",
    AUTH_CLIENT_SECRET: "fixture-client-secret-not-issued-32", AUTH_BRIDGE_SECRET: "fixture-bridge-secret-not-issued-32",
    AUTH_DATABASE_URL: "postgres://fixture:fixture@127.0.0.1:25432/fixture",
    AUTH_SEAL_KEYS: JSON.stringify({ active: "fixture", keys: { fixture: Buffer.alloc(32, 1).toString("base64url") } }),
  });
});
afterAll(() => {
  server.stop(true);
  for (const name of names) if (previous[name] === undefined) delete process.env[name]; else process.env[name] = previous[name];
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
test("social registration requests real missing traits without requiring password/email or privileged fields", () => {
  const input = (name, group, type, value = "") => ({ type: "input", group, attributes: { name, type, value, required: true } });
  const nodes = [input("csrf_token", "default", "hidden", "fixture"),
    input("traits.username", "password", "text"), input("traits.dateOfBirth", "password", "date"),
    input("traits.email", "password", "email"), input("password", "password", "password"),
    input("method", "password", "submit", "password"), input("provider", "oidc", "submit", "google")];
  const methods = formMethods(nodes, "registration");
  const social = methods.find((method) => method.name === "oidc");
  expect(social.provider).toBe("google");
  expect(social.fields.map((field) => field.name)).toEqual(["csrf_token", "traits.username", "traits.dateOfBirth"]);
  expect(formMethods(nodes.filter((node) => node.group !== "oidc"), "registration").some((method) => method.name === "oidc")).toBe(false);
});
