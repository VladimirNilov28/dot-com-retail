import { describe, expect, test } from "bun:test";
import { AuthError, authCookie, decodeBrowserState, flowIdentifier, formKind, grantTokens, hasSessionCookie,
  oneParameter, opaqueIdentifier, safeReturnTo } from "./model.ts";

describe("storefront authentication return contract", () => {
  test.each([
    "/", "/catalog", "/catalog/all-products", "/catalog/components", "/account",
    "/products/actual-product?variant=9007199254740993",
    "/search?q=cpu%20gpu", "/search?q=100%25", "/search?q=%2520",
  ])("preserves supported route and query %s", (route) => {
    expect(safeReturnTo(route)).toBe(route);
  });

  describe("initial and refreshed BFF grant contract", () => {
    const grant = { access_token: "fixture-access", refresh_token: "fixture-refresh", expires_in: 45,
      scope: "openid user:read offline_access" };
    test("accepts only the bounded agreed grant and retains no ID token", () => {
      expect(Object.keys(grantTokens(grant)).sort()).toEqual(["accessToken", "expiresAt", "refreshToken"]);
    });
    test("rejects invalid lifetimes, missing rotation and expanded or repeated scopes", () => {
      for (const expires_in of [0, -1, 1.5, NaN, Infinity, 86401, "45"]) {
        expect(() => grantTokens({ ...grant, expires_in })).toThrow(AuthError);
      }
      for (const scope of [undefined, "", "openid offline_access user:read user:write",
        "openid offline_access user:read user:read"]) {
        expect(() => grantTokens({ ...grant, scope })).toThrow(AuthError);
      }
      expect(() => grantTokens({ ...grant, refresh_token: undefined })).toThrow(AuthError);
    });
  });

  test.each([
    "", "https://attacker.invalid/", "//attacker.invalid/", "/\\attacker.invalid/",
    "/%2fattacker.invalid/", "/%252fattacker.invalid/", "/catalog/%2e%2e/api",
    "/products/../api", "/auth/callback", "/graphql", "/_next/static/thing",
    "/login", "/register", "/verify", "/account#token", "/account?access_token=credential",
    "/search?q=%0d%0aLocation%3A", "/account?password=credential", " /account",
  ])("rejects unsafe or internal route %s", (route) => {
    expect(() => safeReturnTo(route)).toThrow(AuthError);
  });

  test("defaults only an absent requested route", () => {
    expect(safeReturnTo(undefined)).toBe("/");
    expect(() => safeReturnTo([])).toThrow(AuthError);
  });

  test("does not silently choose one repeated transaction parameter", () => {
    expect(() => oneParameter(new URLSearchParams("state=a&state=b"), "state")).toThrow(AuthError);
    expect(oneParameter(new URLSearchParams("state=a"), "state")).toBe("a");
  });

  test("preauthentication retains the previous session coordinate before receiving tokens", () => {
    const state = decodeBrowserState({ jar: "{}", csrf: "a".repeat(43), returnTo: "/account",
      previousSessionHash: "b".repeat(64) });
    expect(state.previousSessionHash).toBe("b".repeat(64));
    expect(state.tokens).toBeUndefined();
    expect(() => decodeBrowserState({ ...state, previousSessionHash: "a".repeat(43) })).toThrow(AuthError);
  });

  test("client flow identifiers and types are invalid requests, not provider outages", () => {
    expect(flowIdentifier("01234567-89ab-cdef-0123-456789abcdef")).toBe("01234567-89ab-cdef-0123-456789abcdef");
    for (const id of [undefined, [], "", "not-a-flow", "a".repeat(36)]) {
      expect(() => flowIdentifier(id)).toThrow(AuthError);
    }
    for (const kind of ["login", "registration", "verification"]) expect(formKind(kind)).toBe(kind);
    expect(() => formKind("settings")).toThrow(AuthError);
  });

  test("accepts only an opaque bounded browser identifier", () => {
    expect(opaqueIdentifier("a".repeat(43))).toHaveLength(43);
    for (const value of ["jwt.token.value", "", "a".repeat(44), "a".repeat(42), null]) {
      expect(() => opaqueIdentifier(value)).toThrow(AuthError);
    }
  });

  test("rejects repeated, malformed and empty authentication cookies before parsing deduplication", () => {
    const id = "a".repeat(43);
    expect(authCookie(`other=ignored; bc_session=${id}`, "bc_session")).toBe(id);
    expect(authCookie("other=ignored", "bc_session")).toBeUndefined();
    expect(hasSessionCookie("retail_guest_cart=ignored")).toBe(false);
    for (const cookie of [`bc_session=${id}; bc_session=${id}`, "bc_session=", "bc_session", "bc_session=jwt.token"]) {
      expect(hasSessionCookie(cookie)).toBe(true);
      expect(() => authCookie(cookie, "bc_session")).toThrow(AuthError);
    }
  });
});
