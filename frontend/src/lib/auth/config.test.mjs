import { describe, expect, test } from "bun:test";
import { authConfig, authOrigin } from "./config";

const keys = JSON.stringify({ active: "fixture", keys: { fixture: Buffer.alloc(32, 1).toString("base64url") } });
const fixture = {
  STOREFRONT_ORIGIN: "http://127.0.0.1:3300",
  AUTH_OAUTH_ISSUER: "http://127.0.0.1:24444",
  AUTH_KRATOS_URL: "http://127.0.0.1:24433",
  AUTH_BRIDGE_URL: "http://127.0.0.1:24446",
  AUTH_CLIENT_ID: "fixture-client",
  AUTH_CLIENT_SECRET: "fixture-client-secret-not-issued-32",
  AUTH_BRIDGE_SECRET: "fixture-bridge-secret-not-issued-32",
  AUTH_DATABASE_URL: "postgresql://fixture:fixture@127.0.0.1:25432/fixture",
  AUTH_SEAL_KEYS: keys,
};
const production = {
  ...fixture, STOREFRONT_ORIGIN: "https://shop.example.com",
  AUTH_OAUTH_ISSUER: "https://auth.example.com",
  AUTH_KRATOS_URL: "https://identity.example.com",
  AUTH_BRIDGE_URL: "https://bridge.example.com",
  AUTH_DATABASE_URL: `${fixture.AUTH_DATABASE_URL}?sslmode=verify-full`,
};

describe("BFF deployment configuration", () => {
  test("allows only explicitly configured loopback development HTTP", () => {
    expect(authConfig(fixture).sessionCookie).toBe("bc_session");
    for (const value of ["http://remote.example", "http://127.0.0.1:3300/path",
      "http://127.0.0.1:3300?origin=other", "http://user:password@127.0.0.1:3300"]) {
      expect(() => authConfig({ ...fixture, STOREFRONT_ORIGIN: value })).toThrow();
    }
    expect(() => authConfig({ ...fixture, AUTH_OAUTH_ISSUER: "http://localhost:24444" })).toThrow();
  });

  test("requires same-site HTTPS, verified database TLS and host-only secure cookie names", () => {
    const config = authConfig(production);
    expect(config.secure).toBe(true);
    expect(config.sessionCookie).toBe("__Host-bc_session");
    expect(config.flowCookie).toBe("__Host-bc_auth");
    expect(authConfig({ ...production, STOREFRONT_ORIGIN: "HTTPS://shop.example.com" }).secure).toBe(true);
    for (const changes of [
      { AUTH_OAUTH_ISSUER: "https://provider.other.example" },
      { AUTH_OAUTH_ISSUER: "http://127.0.0.1:24444" },
      { AUTH_BRIDGE_URL: "http://127.0.0.1:24446" },
      { AUTH_DATABASE_URL: fixture.AUTH_DATABASE_URL },
      { NODE_TLS_REJECT_UNAUTHORIZED: "0" },
    ]) expect(() => authConfig({ ...production, ...changes })).toThrow();
  });

  test("requires a usable active encryption key and separate server secrets", () => {
    for (const AUTH_SEAL_KEYS of ["not-json", "[]", JSON.stringify({ active: "missing", keys: {} }),
      JSON.stringify({ active: "fixture", keys: { fixture: "short" } })]) {
      expect(() => authConfig({ ...fixture, AUTH_SEAL_KEYS })).toThrow();
    }
    expect(() => authConfig({ ...fixture, AUTH_CLIENT_SECRET: "short" })).toThrow();
    expect(() => authConfig({ ...fixture, AUTH_BRIDGE_SECRET: "replace-with-secret" })).toThrow();
    expect(authOrigin({ STOREFRONT_ORIGIN: fixture.STOREFRONT_ORIGIN })).toBe(fixture.STOREFRONT_ORIGIN);
  });
});
