import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { submitFlow } from "./flows";
import { emptyJar } from "./provider";

const id = "11111111-1111-4111-8111-111111111111";
const browser = "a".repeat(43);
let server;
let posts = 0;
let previous;
const names = ["STOREFRONT_ORIGIN", "AUTH_OAUTH_ISSUER", "AUTH_KRATOS_URL", "AUTH_BRIDGE_URL",
  "AUTH_CLIENT_ID", "AUTH_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "AUTH_DATABASE_URL", "AUTH_SEAL_KEYS"];

beforeAll(() => {
  server = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    async fetch(request) {
      if (request.method === "POST") { posts++; return Response.json({}); }
      return Response.json({
        id, type: "browser", expires_at: new Date(Date.now() + 600000).toISOString(),
        ui: { action: `${server.url.origin}/self-service/registration?flow=${id}`, method: "POST", nodes: [
          { type: "input", group: "password", attributes: { name: "method", type: "submit", value: "password" } },
          { type: "input", group: "default", attributes: { name: "csrf_token", type: "hidden", value: "fixture-csrf" } },
        ] },
      });
    },
  });
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
  for (const name of names) {
    if (previous[name] === undefined) delete process.env[name];
    else process.env[name] = previous[name];
  }
});

const client = { query: async () => ({ rowCount: 1, rows: [{ flow_id: id }] }) };
const form = (password) => new URLSearchParams({
  method: "password", csrf_token: "fixture-csrf", password,
  "traits.email": "fixture@bytecore.example", "traits.username": "fixture", "traits.dateOfBirth": "1990-06-01", altcha: "fixture-proof",
});
const state = () => ({ jar: emptyJar(), csrf: browser, returnTo: "/" });

describe("registration BFF validation before provider mutation", () => {
  test("rejects bypassed browser length checks without sending a registration to Kratos", async () => {
    for (const [value, code] of [["short", "minimum"], ["a".repeat(73), "maximum"]]) {
      const before = posts;
      const result = await submitFlow(state(), browser, id, "registration", form(value), client);
      expect(result.complete).toBe(false);
      expect(result.validation).toBe(true);
      expect(result.passwordError).toBe(code);
      expect(posts).toBe(before);
      expect(JSON.stringify(result).includes(value)).toBe(false);
    }
  });
  test("does not compare policy-compliant passwords across users or require character classes", async () => {
    const before = posts;
    for (let user = 0; user < 2; user++) {
      const submitted = form("alllowercase");
      submitted.set("traits.email", `fixture${user}@bytecore.example`);
      expect((await submitFlow(state(), browser, id, "registration", submitted, client)).complete).toBe(true);
    }
    expect(posts).toBe(before + 2);
  });
});
