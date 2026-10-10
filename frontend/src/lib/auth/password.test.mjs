import { describe, expect, test } from "bun:test";
import { passwordLengthFeedback, registrationPasswordPolicy } from "./password";
import { passwordStrength, strengthLabels } from "./password-strength";

describe("deployed Kratos password guidance", () => {
  test("counts UTF-8 bytes, not UTF-16 code units or arbitrary composition rules", () => {
    expect(registrationPasswordPolicy).toEqual({ minBytes: 8, maxBytes: 72 });
    expect(passwordLengthFeedback("short").minimumMet).toBe(false);
    expect(passwordLengthFeedback("alllowercase").minimumMet).toBe(true);
    expect(passwordLengthFeedback("two words").minimumMet).toBe(true);
    expect(passwordLengthFeedback("\u{1f600}\u{1f600}")).toEqual({ bytes: 8, minimumMet: true, maximumMet: true });
    expect(passwordLengthFeedback("a".repeat(72)).maximumMet).toBe(true);
    expect(passwordLengthFeedback("a".repeat(73)).maximumMet).toBe(false);
    expect(passwordLengthFeedback("\u{1f600}".repeat(19)).maximumMet).toBe(false);
  });
  test("strength is separate from passing local policy length checks", () => {
    expect(passwordLengthFeedback("password").minimumMet).toBe(true);
    expect(strengthLabels[passwordStrength("password")]).toBe("Weak");
    expect(passwordStrength("Cedar kettle orbit unplanned 947")).toBeGreaterThanOrEqual(3);
  });
});
