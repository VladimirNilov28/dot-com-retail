import { describe, expect, test } from "bun:test";
import { submitContactMessage } from "./contact-operations.ts";
import { contactErrors } from "../contact/model.ts";

const valid = { name: "Mari Tamm", email: "mari@example.com", subject: "ORDER", message: "Where is my order R-123?" };

describe("contact form validation", () => {
  test("accepts a valid message", () => expect(contactErrors(valid)).toEqual({}));
  test("reports each invalid field", () => {
    const errors = contactErrors({ name: " ", email: "nope", subject: "SPAM", message: "short" });
    expect(Object.keys(errors).sort()).toEqual(["email", "message", "name", "subject"]);
    expect(contactErrors({ ...valid, message: "x".repeat(4001) }).message).toContain("4000");
  });
});

describe("submitContactMessage operation", () => {
  test("variables are strict and allowlist the subject", () => {
    expect(submitContactMessage.variables({ input: { ...valid, website: "" } })).toEqual({ input: { ...valid, website: "" } });
    expect(() => submitContactMessage.variables({ input: { ...valid, subject: "SPAM" } })).toThrow();
    expect(() => submitContactMessage.variables({ input: { ...valid, userId: "1" } })).toThrow();
    expect(() => submitContactMessage.variables({ input: valid, extra: 1 })).toThrow();
  });
  test("decodes results", () => {
    expect(submitContactMessage.decode({ submitContactMessage: { accepted: false, reference: null,
      fieldErrors: [{ field: "email", message: "Enter a valid email address." }] } }).submitContactMessage.fieldErrors[0].field).toBe("email");
    expect(submitContactMessage.decode({ submitContactMessage: { accepted: true, reference: "C-ABCDEFGH", fieldErrors: [] } })
      .submitContactMessage.reference).toBe("C-ABCDEFGH");
  });
});
