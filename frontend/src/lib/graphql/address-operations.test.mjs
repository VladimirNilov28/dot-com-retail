import { describe, expect, test } from "bun:test";
import { addMyAddress, addressErrors, deleteMyAddress, updateMyAddress } from "./address-operations.ts";

const valid = { firstName: "Mari", lastName: "Tamm", addressLine1: "Narva mnt 1", addressLine2: "", city: "Tallinn",
  postalCode: "10117", country: "EE", mobile: "+372 5555 5555" };

describe("saved address validation", () => {
  test("accepts a complete supported address", () => expect(addressErrors(valid, ["EE"])).toEqual({}));
  test("reports missing required fields", () => {
    const errors = addressErrors({ ...valid, firstName: " ", city: "" }, ["EE"]);
    expect(Object.keys(errors).sort()).toEqual(["city", "firstName"]);
  });
  test("rejects unsupported destinations and long values", () => {
    expect(addressErrors({ ...valid, country: "US" }, ["EE"]).country).toContain("deliver");
    expect(addressErrors({ ...valid, postalCode: "12345678901" }, ["EE"]).postalCode).toContain("10");
    expect(addressErrors({ ...valid, mobile: "call me" }, ["EE"]).mobile).toBeDefined();
  });
  test("operation variables are strict", () => {
    expect(addMyAddress.variables({ input: { city: "Tallinn" } })).toEqual({ input: { city: "Tallinn" } });
    expect(() => addMyAddress.variables({ input: { city: "x", userId: "1" } })).toThrow();
    expect(() => updateMyAddress.variables({ id: "abc", input: {} })).toThrow();
    expect(deleteMyAddress.variables({ id: "42" })).toEqual({ id: "42" });
  });
});
