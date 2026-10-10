import { describe, expect, test } from "bun:test";
import { authFieldId, captchaFailure, formMessages, formMethods, temporaryAuthMessage } from "./view";

const input = (name, value, group = "default", type = "text", messages = []) => ({
  type: "input", group, attributes: { name, value, type, required: true }, messages,
});
const csrf = input("csrf_token", "provider-csrf-control", "default", "hidden");
const password = input("method", "password", "password", "submit");

describe("safe provider form projection", () => {
  test("retains required controls and actual schema fields but never echoes credential values", () => {
    const methods = formMethods([
      csrf, input("traits.email", "customer@bytecore.example"),
      input("traits.username", "customer"), input("traits.dateOfBirth", "1990-06-01"),
      input("password", "must-not-be-rendered", "password", "password"), password,
    ], "registration");
    expect(methods).toHaveLength(1);
    expect(methods[0].fields.find((field) => field.name === "csrf_token").value).toBe("provider-csrf-control");
    expect(methods[0].fields.find((field) => field.name === "traits.username").value).toBe("customer");
    expect(methods[0].fields.find((field) => field.name === "traits.dateOfBirth").type).toBe("date");
    expect(methods[0].fields.find((field) => field.name === "password").value).toBe("");
    expect(JSON.stringify(methods)).not.toContain("must-not-be-rendered");
  });
  test("keeps real enrolled factor controls separate rather than requiring both", () => {
    const methods = formMethods([
      csrf, input("totp_code", "must-not-echo-otp", "totp"),
      input("method", "totp", "totp", "submit"),
      input("lookup_secret", "must-not-echo-recovery", "lookup_secret"),
      input("method", "lookup_secret", "lookup_secret", "submit"),
    ], "login");
    expect(methods.map((method) => method.name)).toEqual(["totp", "lookup_secret"]);
    expect(methods[0].fields.some((field) => field.name === "lookup_secret")).toBe(false);
    expect(methods[1].fields.some((field) => field.name === "totp_code")).toBe(false);
    expect(JSON.stringify(methods)).not.toContain("must-not-echo");
  });
  test("projects sent-email verification without duplicating its hidden method or turning resend into an email field", () => {
    const methods = formMethods([
      input("method", "code", "code", "hidden"), input("code", "must-not-echo", "code"),
      input("method", "code", "code", "submit"), csrf,
      { ...input("email", "customer@bytecore.example", "code", "submit"), attributes: {
        name: "email", value: "customer@bytecore.example", type: "submit",
      } },
    ], "verification");
    expect(methods).toHaveLength(1);
    expect(methods[0].label).toBe("Verify email");
    expect(methods[0].fields.map((field) => field.name)).toEqual(["code", "csrf_token"]);
    expect(methods[0].fields[0].value).toBe("");
  });
  test("fails explicitly for missing provider CSRF or an unsupported required node", () => {
    expect(() => formMethods([password], "login")).toThrow();
    expect(() => formMethods([csrf, input("identity.role", "ADMIN"), password], "registration")).toThrow();
    expect(() => formMethods([csrf, input("method", "profile", "profile", "submit")], "registration")).toThrow();
  });
  test("does not display raw infrastructure diagnostics or arbitrary provider markup", () => {
    expect(formMessages([{ id: 5000001, type: "error", text: "http://private-service failed: credential" }]))
      .toEqual([temporaryAuthMessage]);
    expect(formMessages([{ id: 4000006, type: "error", text: "arbitrary <script>markup</script>" }]))
      .toEqual(["Email or password is incorrect. Please try again."]);
  });
  test("maps actual Kratos policy IDs without rendering raw text or untrusted reasons", () => {
    for (const [id, context, fragment] of [
      [4000032, { min_length: 8 }, "at least 8 UTF-8 bytes"],
      [4000033, { max_length: 72 }, "no more than 72 UTF-8 bytes"],
      [4000031, {}, "unlike your email"],
      [4000034, { breaches: 100 }, "known to be breached"],
      [4000005, { reason: "http://internal/?password=private-value" }, "rejected by the account security policy"],
    ]) {
      const messages = [{ id, context, type: "error", text: "private-value" }];
      const expected = id === 4000005 ? "does not meet the security policy" : fragment;
      expect(formMessages(messages, "registration")[0]).toContain(expected);
      const field = formMethods([csrf, input("password", "private-value", "password", "password", messages), password], "registration")[0].fields;
      expect(field.find((value) => value.name === "password").error).toContain(expected);
      expect(JSON.stringify(field)).not.toContain("private-value");
    }
    expect(formMessages([{ id: 4000032, context: { min_length: "secret" }, type: "error" }])[0])
      .toBe("This password was rejected. Choose another.");
  });
  test("breach mapping never implies that another user chose the password", () => {
    const message = formMessages([{ id: 4000034, type: "error", context: { breaches: 100 } }], "registration")[0];
    expect(message).toBe("This password is known to be breached. Choose another.");
    expect(message).not.toMatch(/unique|another user|already used/i);
  });
  test("infrastructure failures are form-level, never invalid-password errors", () => {
    for (const message of [
      { id: 5000001, type: "error", text: "private failure" },
      { id: 4000001, type: "error", text: "Authentication is unavailable. Try again." },
    ]) {
      expect(formMessages([message], "registration")).toEqual([temporaryAuthMessage]);
      expect(formMethods([csrf, input("password", "", "password", "password", [message]), password], "registration")[0]
        .fields.find((value) => value.name === "password").error).toBeUndefined();
    }
  });
  test("rejects malformed field messages rather than silently dropping validation", () => {
    expect(() => formMethods([csrf, input("password", "", "password", "password", {}), password], "registration"))
      .toThrow("Invalid provider messages");
  });
  test("login credential/account/policy rejections stay generic", () => {
    for (const id of [4000006, 4000037, 4010010, 4000034, 4000031]) {
      expect(formMessages([{ id, type: "error", text: "account exists", context: { reason: "private" } }], "login"))
        .toEqual(["Email or password is incorrect. Please try again."]);
    }
  });
  test("preserves safe CAPTCHA/expiry recovery and stable field targets", () => {
    expect(formMessages([{ id: 4000001, type: "error", text: "The security check expired. Try again." }], "registration"))
      .toEqual(["The security check expired. Try again."]);
    expect(formMessages([{ id: 4040001, type: "error" }], "registration")[0]).toContain("form has expired");
    expect(authFieldId("password", "traits.email")).toBe("auth-password-traits-email");
    expect(captchaFailure(401)).toBe("expired");
    expect(captchaFailure(503)).toBe("service");
    expect(captchaFailure(400)).toBe("captcha");
    expect(formMessages([{ id: 4000038, type: "error", text: "private" }])[0]).toContain("security check failed");
    expect(formMessages([{ id: 4000040, type: "error", text: "private" }])).toEqual(["Enter a valid email address."]);
  });
});
