import { expect, test } from "bun:test";
import { verificationLinkCode } from "./verification";

test("email fragment accepts exactly one unchanged six-digit code without query transport", () => {
  expect(verificationLinkCode("#code=001234")).toBe("001234");
  for (const fragment of ["#code=12345", "#code=1234567", "#code=123456&code=123456",
    "#code=123456&flow=other", "#code=%20123456", "#code=1e0000", "#token=123456"]) {
    expect(verificationLinkCode(fragment)).toBeUndefined();
  }
});
