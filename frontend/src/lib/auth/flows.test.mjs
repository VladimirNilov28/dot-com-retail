import { afterAll, beforeAll, describe, expect, mock, test } from "bun:test";
import * as store from "./store";

// Attempt counters live in Postgres; tests decide the limiter outcome explicitly.
let attempts = [];
let allowAttempt = true;
mock.module("./store", () => ({ ...store, consumeAttempt: async (...args) => { attempts.push(args); return allowAttempt; } }));
const { submitFlow, validateFlow } = await import("./flows");
import { emptyJar } from "./provider";

const id = "11111111-1111-4111-8111-111111111111";
const browser = "a".repeat(43);
let server;
let posts = 0;
let previous;
let postedVerification;
let postedRecovery;
let postedReset;
let postedSocial;
let loginAal;
const secondFactorPosts = [];
let bridge;
let availability = { email_available: true, username_available: true };
const availabilityRequests = [];
const names = ["STOREFRONT_ORIGIN", "AUTH_OAUTH_ISSUER", "AUTH_KRATOS_URL", "AUTH_BRIDGE_URL",
  "AUTH_CLIENT_ID", "AUTH_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "AUTH_DATABASE_URL", "AUTH_SEAL_KEYS"];

beforeAll(() => {
  bridge = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch(request) {
    if (new URL(request.url).pathname !== "/internal/registration/availability") throw new Error("Unexpected bridge request");
    availabilityRequests.push(await request.json());
    return Response.json(availability);
  } });
  server = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    async fetch(request) {
      const verification = new URL(request.url).pathname.includes("verification");
      const recovery = new URL(request.url).pathname.includes("recovery");
      const reset = new URL(request.url).pathname.includes("settings");
      const login = new URL(request.url).pathname.includes("login");
      if (request.method === "POST") {
        posts++;
        const body = await request.json();
        if (body.method === "oidc") {
          postedSocial = body;
          if (login) {
            const destination = new URL("https://accounts.google.com/o/oauth2/v2/auth");
            destination.searchParams.set("redirect_uri", "http://127.0.0.1:3300/self-service/methods/oidc/callback/google");
            destination.searchParams.set("state", "opaque-provider-state-not-issued-32");
            return Response.json({ error: { id: "browser_location_change_required" },
              redirect_browser_to: destination.href }, { status: 422,
              headers: { "Set-Cookie": "ory_kratos_continuity=fixture; Path=/; HttpOnly" } });
          }
          return Response.json({});
        }
        if (login && (body.method === "lookup_secret" || body.method === "totp")) {
          secondFactorPosts.push(body.method);
          return Response.json({ session: {} });
        }
        if (reset) {
          postedReset = body;
        } else if (recovery) {
          postedRecovery = body;
          if (postedRecovery.code === "001234") return Response.json({
            state: "passed_challenge", continue_with: [{ action: "show_settings_ui", flow: { id } }],
          });
        } else if (verification) postedVerification = body;
        else return Response.json({});
      }
      return Response.json({
        id, type: "browser", expires_at: new Date(Date.now() + 600000).toISOString(),
        ...(login && loginAal ? { requested_aal: loginAal } : {}),
        ...(verification || recovery ? { state: "sent_email" } : reset ? { state: request.method === "POST" ? "success" : "show_form" } : {}),
        ui: { action: `${server.url.origin}/self-service/${verification ? "verification" : recovery ? "recovery" : reset ? "settings" : login ? "login" : "registration"}?flow=${id}`, method: "POST", nodes: [
          { type: "input", group: "password", attributes: { name: "method", type: "submit", value: verification || recovery ? "code" : "password" } },
          ...(verification || recovery ? [{ type: "input", group: "code", attributes: { name: "email", type: "submit", value: "fixture@bytecore.example" } }] : []),
          { type: "input", group: "default", attributes: { name: "csrf_token", type: "hidden", value: "fixture-csrf" } },
          ...(login && loginAal ? ["totp", "lookup_secret"].map((value) =>
            ({ type: "input", group: value, attributes: { name: "method", type: "submit", value } })) : []),
          ...(!verification && !recovery && !reset ? [{ type: "input", group: "oidc",
            attributes: { name: "provider", type: "submit", value: "google" } }] : []),
        ] },
      });

    },
  });
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

test("verification preserves code bytes and sends only the explicitly allowed resend email", async () => {
  const form = new URLSearchParams({ method: "code", csrf_token: "fixture-csrf", code: "001234" });
  await submitFlow(state(), browser, id, "verification", form, client);
  expect(postedVerification.code).toBe("001234");
  expect(postedVerification.email).toBeUndefined();
  form.set("email", "fixture@bytecore.example");
  await submitFlow(state(), browser, id, "verification", form, client);
  expect(postedVerification.email).toBe("fixture@bytecore.example");
  expect(postedVerification.code).toBeUndefined();
  form.set("email", "foreign@bytecore.example");
  await expect(submitFlow(state(), browser, id, "verification", form, client)).rejects.toThrow();
});

test("recovery redeems exact code into only a bound provider settings flow", async () => {
  const result = await submitFlow(state(), browser, id, "recovery",
    new URLSearchParams({ method: "code", csrf_token: "fixture-csrf", code: "001234" }), client);
  expect(postedRecovery.code).toBe("001234");
  expect(postedRecovery.email).toBeUndefined();
  expect(result).toEqual({ complete: true, settingsFlow: id });
  const resend = await submitFlow(state(), browser, id, "recovery",
    new URLSearchParams({ method: "code", csrf_token: "fixture-csrf", email: "fixture@bytecore.example", code: "001234" }), client);
  expect(resend.complete).toBe(false);
  expect(postedRecovery.code).toBeUndefined();
  await expect(submitFlow(state(), browser, id, "recovery",
    new URLSearchParams({ method: "code", csrf_token: "fixture-csrf", email: "foreign@bytecore.example" }), client)).rejects.toThrow();
});

test("reset enforces shared byte policy before provider mutation and forwards no traits", async () => {
  const before = posts;
  const invalid = await submitFlow(state(), browser, id, "reset", form("short"), client);
  expect(invalid.passwordError).toBe("minimum");
  expect(posts).toBe(before);
  expect((await submitFlow(state(), browser, id, "reset", form("alllowercase"), client)).complete).toBe(true);
  expect(Object.keys(postedReset).sort()).toEqual(["csrf_token", "method", "password"]);
});

test("Google entry starts authorization with an empty form: no profile fields, CAPTCHA or traits", async () => {
  const entry = new URLSearchParams({ method: "oidc", provider: "google", csrf_token: "fixture-csrf" });
  expect(await submitFlow(state(), browser, id, "registration", entry, client)).toEqual({ complete: true, socialComplete: true });
  expect(postedSocial.traits).toEqual({});
  expect(postedSocial.transient_payload).toBeUndefined();
  for (const extra of [{ "traits.username": "x" }, { altcha: "x" }]) {
    await expect(submitFlow(state(), browser, id, "registration", new URLSearchParams({ ...Object.fromEntries(entry), ...extra }), client))
      .rejects.toMatchObject({ code: "invalid_request" });
  }
});

test("fresh Google login explicitly requests the provider account chooser", async () => {
  const current = state();
  const result = await submitFlow(current, browser, id, "login",
    new URLSearchParams({ method: "oidc", provider: "google", csrf_token: "fixture-csrf" }), client);
  expect(postedSocial.upstream_parameters).toEqual({ prompt: "select_account" });
  expect(new URL(result.socialRedirect).searchParams.get("prompt")).toBe("select_account");
});

test("provider-authenticated registration continuation completes through canonical handoff, not a second redirect", async () => {
  availability = { email_available: true, username_available: true };
  const submitted = form("must-not-forward");
  submitted.set("method", "oidc");
  submitted.set("provider", "google");
  submitted.delete("password");
  submitted.delete("traits.email");
  const bound = { ...state(), googleRegistration: { flow: id, email: "verified@example.test", submitted: false } };
  const result = await submitFlow(bound, browser, id, "registration", submitted, client);
  expect(result).toEqual({ complete: true, socialComplete: true });
  expect(postedSocial.traits).toEqual({ username: "fixture", dateOfBirth: "1990-06-01" });
  expect(postedSocial.transient_payload.altcha).toBe("fixture-proof");
  expect(postedSocial.password).toBeUndefined();
  expect(postedSocial.traits.email).toBeUndefined();
});

test("Google registration rejects posted identity claims and preserves missing profile values before mutation", async () => {
  const current = { ...state(), googleRegistration: { flow: id, email: "verified@example.test", submitted: false } };
  const submitted = new URLSearchParams({ method: "oidc", provider: "google", csrf_token: "fixture-csrf",
    "traits.username": "chosen-name", "traits.dateOfBirth": "", altcha: "fixture-proof" });
  const before = posts;
  expect((await submitFlow(current, browser, id, "registration", submitted, client)).validation).toBe(true);
  expect(posts).toBe(before);
  expect(current.googleRegistration).toMatchObject({ email: "verified@example.test", submitted: true,
    username: "chosen-name", dateOfBirth: "" });
  for (const name of ["traits.email", "password", "spring_user_id", "role"]) {
    const injected = new URLSearchParams(submitted);
    injected.set(name, "attacker@example.test");
    await expect(submitFlow(current, browser, id, "registration", injected, client)).rejects.toMatchObject({ code: "invalid_request" });
  }
  expect(posts).toBe(before);
});

test("bound Google completion checks canonical conflicts before the second provider authorization", async () => {
  const submitted = new URLSearchParams({ method: "oidc", provider: "google", csrf_token: "fixture-csrf",
    "traits.username": "taken-name", "traits.dateOfBirth": "2000-01-02", altcha: "fixture-proof" });
  const bound = () => ({ ...state(), googleRegistration: { flow: id, email: "verified@example.test", submitted: false } });
  try {
    availability = { email_available: true, username_available: false };
    let current = bound();
    const before = posts;
    expect(await submitFlow(current, browser, id, "registration", submitted, client))
      .toMatchObject({ complete: false, validation: true });
    expect(posts).toBe(before);
    expect(current.googleRegistration).toMatchObject({ username: "taken-name", dateOfBirth: "2000-01-02",
      submitted: true, providerValidation: false, usernameUnavailable: true });
    expect(availabilityRequests.at(-1)).toEqual({ email: "verified@example.test", username: "taken-name" });

    availability = { email_available: false, username_available: true };
    current = bound();
    expect(await submitFlow(current, browser, id, "registration", submitted, client))
      .toEqual({ complete: true, socialConflict: true });
    expect(posts).toBe(before);
    expect(current.googleRegistration).toBeUndefined();

    availability = { email_available: true, username_available: true };
    current = bound();
    current.googleRegistration.usernameUnavailable = true;
    submitted.set("traits.username", "free-name");
    await submitFlow(current, browser, id, "registration", submitted, client);
    expect(posts).toBe(before + 1);
    expect(current.googleRegistration.usernameUnavailable).toBeUndefined();
    expect(postedSocial.upstream_parameters).toEqual({ login_hint: "verified@example.test" });
    expect(postedSocial.traits).toEqual({ username: "free-name", dateOfBirth: "2000-01-02" });
  } finally { availability = { email_available: true, username_available: true }; }
});

test("duplicate OAuth initiation is rejected before another provider submission", async () => {
  const current = { ...state(), social: { flow: id, kind: "login", state: "already-pending-provider-state-32" } };
  const before = posts;
  await expect(submitFlow(current, browser, id, "login",
    new URLSearchParams({ method: "oidc", provider: "google", csrf_token: "fixture-csrf" }), client))
    .rejects.toMatchObject({ code: "invalid_request" });
  expect(posts).toBe(before);
});

test("native Google login 422 is a validated browser-location change with bound state and encrypted continuity", async () => {
  const submitted = new URLSearchParams({ method: "oidc", provider: "google", csrf_token: "fixture-csrf" });
  const browserState = state();
  const result = await submitFlow(browserState, browser, id, "login", submitted, client);
  expect(result.complete).toBe(true);
  expect(new URL(result.socialRedirect).origin).toBe("https://accounts.google.com");
  expect(browserState.social).toEqual({ state: "opaque-provider-state-not-issued-32", flow: id, kind: "login" });
  expect(browserState.jar.includes("ory_kratos_continuity")).toBe(true);
});

test("already-consumed recovery flows cannot be resubmitted or mislabeled as an outage", () => {
  try {
    validateFlow({ id, type: "browser", state: "passed_challenge",
      expires_at: new Date(Date.now() + 600000).toISOString(), ui: {
        action: `${server.url.origin}/self-service/recovery?flow=${id}`, method: "POST", nodes: [],
      } }, "recovery");
    throw new Error("Expected consumed-flow rejection");
  } catch (error) {
    expect(error.code).toBe("expired");
    expect(error.status).toBe(401);
  }
});

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

describe("second factor and recovery abuse limits", () => {
  const lookup = new URLSearchParams({ method: "lookup_secret", csrf_token: "fixture-csrf", lookup_secret: "fixture-code" });
  test("a recovery code is never accepted as a first-factor login", async () => {
    loginAal = "aal1";
    secondFactorPosts.length = 0;
    await expect(submitFlow(state(), browser, id, "login", lookup, client)).rejects.toMatchObject({ code: "invalid_request" });
    expect(secondFactorPosts).toEqual([]);
    loginAal = undefined;
  });

  test("second-factor attempts are limited per browser before reaching the provider", async () => {
    loginAal = "aal2";
    secondFactorPosts.length = 0;
    attempts = [];
    allowAttempt = false;
    for (const body of [lookup, new URLSearchParams({ method: "totp", csrf_token: "fixture-csrf", totp_code: "123456" })]) {
      await expect(submitFlow(state(), browser, id, "login", body, client)).rejects.toMatchObject({ code: "rate_limited", status: 429 });
    }
    expect(secondFactorPosts).toEqual([]);
    expect(attempts.map(([scope, subject]) => [scope, subject])).toEqual([["second_factor", browser], ["second_factor", browser]]);
    allowAttempt = true;
    await submitFlow(state(), browser, id, "login", lookup, client);
    expect(secondFactorPosts).toEqual(["lookup_secret"]);
    loginAal = undefined;
  });

  test("recovery email requests are limited per normalized address and per browser", async () => {
    attempts = [];
    allowAttempt = false;
    postedRecovery = undefined;
    await expect(submitFlow(state(), browser, id, "recovery", new URLSearchParams({ method: "code", csrf_token: "fixture-csrf",
      email: "fixture@bytecore.example" }), client)).rejects.toMatchObject({ code: "rate_limited" });
    expect(postedRecovery).toBeUndefined();
    expect(attempts[0].slice(0, 2)).toEqual(["recovery_email", "fixture@bytecore.example"]);
    allowAttempt = true;
  });
});
