import { describe, expect, test } from "bun:test";
import { formMessages, formMethods } from "./view";

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
      .toEqual(["Check the details below and try again."]);
    expect(formMessages([{ id: 4000006, type: "error", text: "arbitrary <script>markup</script>" }]))
      .toEqual(["Email or password is incorrect. Please try again."]);
  });
});
