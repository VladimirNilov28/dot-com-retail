import { expect, test } from "bun:test";
import { adminPolicy, moveId, optionalInt, orderRoles, unverifiedClaim } from "./policy";

test("admin policy needs a staff role and aal2 unless locally opted in", () => {
  expect(adminPolicy("USER", "aal2", true)).toBe("role");
  expect(adminPolicy("ORDER_MANAGER", "aal2", false)).toBe("role");
  expect(adminPolicy("ADMIN", "aal1", false)).toBe("assurance");
  expect(adminPolicy("CATALOG_MANAGER", "aal2", false)).toBe("ok");
  expect(adminPolicy("ADMIN", "aal1", true)).toBe("ok");
});
test("order staff area admits order managers and admins only", () => {
  expect(adminPolicy("ORDER_MANAGER", "aal2", false, orderRoles)).toBe("ok");
  expect(adminPolicy("ADMIN", "aal2", false, orderRoles)).toBe("ok");
  expect(adminPolicy("CATALOG_MANAGER", "aal2", false, orderRoles)).toBe("role");
  expect(adminPolicy("USER", "aal2", true, orderRoles)).toBe("role");
  expect(adminPolicy("ORDER_MANAGER", "aal1", false, orderRoles)).toBe("assurance");
});
test("optional integers keep absent distinct from zero and reject junk", () => {
  expect(optionalInt("", 10)).toBeNull();
  expect(optionalInt(null, 10)).toBeNull();
  expect(optionalInt("7", 10)).toBe(7);
  expect(() => optionalInt("0", 10)).toThrow();
  expect(() => optionalInt("11", 10)).toThrow();
  expect(() => optionalInt("1.5", 10)).toThrow();
});
test("moveId swaps neighbours and refuses out-of-range moves", () => {
  expect(moveId(["a", "b", "c"], "b", -1)).toEqual(["b", "a", "c"]);
  expect(moveId(["a", "b", "c"], "c", 1)).toBeNull();
  expect(moveId(["a", "b"], "x", 1)).toBeNull();
});
test("unverified claim reader tolerates malformed tokens", () => {
  const token = `h.${Buffer.from(JSON.stringify({ aal: "aal2", n: 1 })).toString("base64url")}.s`;
  expect(unverifiedClaim(token, "aal")).toBe("aal2");
  expect(unverifiedClaim(token, "n")).toBeUndefined();
  expect(unverifiedClaim("garbage", "aal")).toBeUndefined();
  expect(unverifiedClaim(undefined, "aal")).toBeUndefined();
});
