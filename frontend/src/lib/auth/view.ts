import { object } from "@/lib/graphql/scalars";
import type { FormKind } from "./model";
import type { BrowserState } from "./model";

export interface AuthField {
  name: string;
  label: string;
  type: "text" | "email" | "password" | "date" | "hidden";
  required: boolean;
  value: string;
  error?: string;
  autocomplete?: string;
  readOnly?: boolean;
}

export interface AuthMethod {
  name: "password" | "totp" | "lookup_secret" | "code" | "oidc";
  label: string;
  fields: AuthField[];
  resendEmail?: string;
  provider?: "google";
}

/** Only the password form and the Google completion step carry a CAPTCHA; the Google entry button does not. */
export function methodNeedsCaptcha(kind: string, method: AuthMethod): boolean {
  return kind === "registration" && (method.name !== "oidc" || method.fields.some((field) => field.name === "traits.username"));
}

const labels: Record<string, string> = {
  csrf_token: "", identifier: "Email", "traits.email": "Email",
  "traits.username": "Username", "traits.dateOfBirth": "Date of birth",
  password: "Password", totp_code: "Authenticator code", lookup_secret: "Recovery code",
  email: "Email", code: "Verification code",
};
const sensitive = new Set(["password", "totp_code", "lookup_secret", "code"]);
export const temporaryAuthMessage = "Authentication is temporarily unavailable. Wait a moment and try again.";
const safeHookMessages = new Set([
  "Complete a fresh security check.",
  "The security check is invalid. Try again.",
  "The security check does not belong to this registration.",
  "The security check expired. Try again.",
  "This security check was already used. Try again.",
  "The security check is invalid or expired. Try again.",
  "This email or username cannot be registered. Try signing in.",
  "Complete the required account fields.",
  "The form is invalid or expired. Start again.",
  "Authentication is unavailable. Try again.",
]);

function temporaryMessage(message: Record<string, unknown>): boolean {
  return (typeof message.id === "number" && message.id >= 5000000) ||
    message.text === "Authentication is unavailable. Try again.";
}

export function authFieldId(method: string, field: string): string {
  return `auth-${method}-${field.replaceAll(".", "-")}`;
}

export function captchaFailure(status: number): "expired" | "service" | "captcha" {
  return [401, 403, 410].includes(status) ? "expired" : status >= 500 ? "service" : "captcha";
}

export function formMessages(values: unknown, kind?: FormKind): string[] {
  if (values === undefined) return [];
  if (!Array.isArray(values) || values.length > 30) throw new TypeError("Invalid provider messages");
  return values.map((value) => {
    const message = object(value);
    if (message.type === "error") {
      if (temporaryMessage(message)) return temporaryAuthMessage;
      if ([4000006, 4000037, 4010010].includes(Number(message.id))) return "Email or password is incorrect. Please try again.";
      if (kind === "login" && [4000005, 4000031, 4000032, 4000033, 4000034].includes(Number(message.id))) {
        return "Email or password is incorrect. Please try again.";
      }
      if (message.id === 4000031) return "Use a password unlike your email address.";
      if (message.id === 4000034) return "This password is known to be breached. Choose another.";
      if (message.id === 4000032 || message.id === 4000033) {
        const context = message.context && typeof message.context === "object" ? object(message.context) : {};
        const limit = context[message.id === 4000032 ? "min_length" : "max_length"];
        if (typeof limit === "number" && Number.isSafeInteger(limit) && limit > 0 && limit <= 4096) {
          return `Use ${message.id === 4000032 ? "at least" : "no more than"} ${limit} UTF-8 bytes.`;
        }
        return "This password was rejected. Choose another.";
      }
      if (message.id === 4000005) return "This password does not meet the security policy.";
      if (message.id === 4000002) return "Complete this required field.";
      if (message.id === 4000040) return "Enter a valid email address.";
      if (message.id === 4000004) return "Enter a valid value for this field.";
      if (message.id === 4000038) return "The security check failed. Complete a fresh check and try again.";
      if ([4000007, 4000027, 4000028].includes(Number(message.id))) return "This email or username cannot be registered. Try signing in.";
      if ([4060006, 4070006].includes(Number(message.id)) || (kind !== "verification" &&
          [4000008, 4000012, 4000016].includes(Number(message.id)))) return "The code is incorrect, expired or already used. Try again.";
      if (message.id === 4060001) return "This recovery proof is invalid or already used. Request a new code.";
      if (message.id === 4060002) return "This recovery was already completed. Sign in or start a new recovery request.";
      if (message.id === 4060003) return "This recovery could not continue. Start a new recovery request.";
      if (message.id === 4060005) return "This recovery form has expired. Request a new code.";
      if (message.id === 4070001) return "This verification link is invalid or already used. Request a new code.";
      if (message.id === 4070002) return "This verification was already completed. Try signing in.";
      if (message.id === 4070003) return "This verification could not continue. Start a new verification form.";
      if ([4010001, 4040001, 4070005].includes(Number(message.id))) return "This form has expired. Start a new secure flow.";
      if (typeof message.text === "string" && safeHookMessages.has(message.text)) return message.text;
      console.error("authentication validation reason", "unrecognized_provider_validation");
      return "Check the details below and try again.";
    }
    if (message.type === "info" || message.type === "success") {
      if (kind === "recovery" && [1060001, 1060003].includes(Number(message.id))) {
        return "If this address has an account, a recovery email has been sent. Use the latest email in this browser.";
      }
      if (message.id === 1080001 || message.id === 1080003) return "Use the latest email for this verification form. Resending replaces the previous code.";
      if (message.id === 1080002) return "Your email is verified.";
      return "";
    }
    throw new TypeError("Invalid provider message type");
  }).filter(Boolean);
}

export function googleProfileErrors(username: string, dateOfBirth: string): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!username.trim()) errors["traits.username"] = "Enter a username.";
  const parsed = new Date(`${dateOfBirth}T00:00:00Z`);
  if (!dateOfBirth) errors["traits.dateOfBirth"] = "Enter your date of birth.";
  else if (!/^\d{4}-\d{2}-\d{2}$/.test(dateOfBirth) || !Number.isFinite(parsed.getTime()) ||
      parsed.toISOString().slice(0, 10) !== dateOfBirth || dateOfBirth > new Date().toISOString().slice(0, 10)) {
    errors["traits.dateOfBirth"] = "Enter a valid date of birth, not a future date.";
  }
  return errors;
}

export function formMethods(nodes: Record<string, unknown>[], kind: FormKind,
  googleRegistration?: BrowserState["googleRegistration"]): AuthMethod[] {
  const allowed = kind === "login" ? ["password", "totp", "lookup_secret", "oidc"] :
    kind === "registration" ? ["password", "oidc"] : kind === "reset" ? ["password"] : ["code"];
  const methods: AuthMethod[] = [];
  for (const node of nodes) {
    if (node.type !== "input") continue;
    const attributes = object(node.attributes);
    const google = node.group === "oidc" && attributes.name === "provider" && attributes.value === "google";
    if ((!google && attributes.name !== "method") || attributes.type !== "submit" ||
        !allowed.includes(google ? "oidc" : String(attributes.value))) continue;
    const name = google ? "oidc" : attributes.value;
    if (name !== "password" && name !== "totp" && name !== "lookup_secret" && name !== "code" && name !== "oidc") continue;
    const fields: AuthField[] = [];
    for (const candidate of nodes) {
      if (candidate.type !== "input" || (candidate.group !== "default" && candidate.group !== node.group &&
          !(google && googleRegistration && ["traits.username", "traits.dateOfBirth"].includes(String(object(candidate.attributes).name))))) continue;
      const input = object(candidate.attributes);
      if (input.name === "method" || input.type === "submit") continue;
      if (google && !(googleRegistration ? ["csrf_token", "traits.username", "traits.dateOfBirth"] : ["csrf_token"])
        .includes(String(input.name))) continue;
      if (typeof input.name !== "string" || !(input.name in labels)) {
        if (input.required === true) throw new TypeError("Unsupported required provider field");
        continue;
      }
      const type = input.type === "hidden" ? "hidden" : input.name === "password" ? "password" :
        input.name === "traits.dateOfBirth" ? "date" :
          ["identifier", "email", "traits.email"].includes(input.name) ? "email" : "text";
      const messages = candidate.messages === undefined ? [] : candidate.messages;
      if (!Array.isArray(messages) || messages.length > 30) throw new TypeError("Invalid provider messages");
      const errorMessages = formMessages(messages.filter((message) => !temporaryMessage(object(message))), kind);
      const error = errorMessages.length ? errorMessages.join(" ") : undefined;
      fields.push({
        name: input.name, label: input.name === "code" && kind === "recovery" ? "Recovery code" : labels[input.name], type, required: input.required === true,
        value: sensitive.has(input.name) ? "" : typeof input.value === "string" ? input.value : "",
        error, autocomplete: type === "hidden" ? undefined : input.name === "password"
          ? kind === "registration" || kind === "reset" ? "new-password" : "current-password"
          : sensitive.has(input.name) ? "one-time-code"
            : input.name === "traits.dateOfBirth" ? "bday" : input.name === "traits.username" ? "username" : "email",
      });
    }
    if (!fields.some((field) => field.name === "csrf_token")) throw new TypeError("Provider CSRF control is missing");
    const resend = kind === "verification" || kind === "recovery" ? nodes.find((candidate) => candidate.type === "input" &&
      object(candidate.attributes).name === "email" && object(candidate.attributes).type === "submit") : undefined;
    const resendEmail = resend ? object(resend.attributes).value : undefined;
    if (resendEmail !== undefined && (typeof resendEmail !== "string" || resendEmail.length > 255)) {
      throw new TypeError("Invalid provider resend control");
    }
    methods.push({ name, fields, ...(google ? { provider: "google" as const } : {}),
      ...(typeof resendEmail === "string" ? { resendEmail } : {}), label: google ? "Continue with Google" :
        name === "totp" ? "Verify code" : name === "lookup_secret" ? "Use recovery code" :
      kind === "registration" ? "Create account" : kind === "reset" ? "Save new password" :
        kind === "recovery" ? fields.some((field) => field.name === "code" && field.type !== "hidden")
          ? "Continue to password reset" : "Send recovery email" : kind === "verification"
        ? fields.some((field) => field.name === "code" && field.type !== "hidden") ? "Verify email" : "Send verification code"
        : "Sign in" });
  }
  if (kind === "registration" && googleRegistration) {
    const google = methods.find((method) => method.name === "oidc");
    if (!google) throw new TypeError("Google continuation is missing its provider control");
    const errors = googleRegistration.submitted ?
      googleProfileErrors(googleRegistration.username ?? "", googleRegistration.dateOfBirth ?? "") : {};
    if (googleRegistration.usernameUnavailable && !errors["traits.username"]) {
      errors["traits.username"] = "This username is already taken. Choose another.";
    }
    google.label = "Complete registration";
    google.fields = google.fields.map((field) => ({
      ...field,
      value: field.name === "traits.username" ? googleRegistration.username ?? field.value :
        field.name === "traits.dateOfBirth" ? googleRegistration.dateOfBirth ?? field.value : field.value,
      error: googleRegistration.submitted ?
        (googleRegistration.providerValidation ? field.error : undefined) ?? errors[field.name] : undefined,
    }));
    google.fields.splice(1, 0, { name: "traits.email", label: "Google email", type: "email",
      required: false, value: googleRegistration.email, readOnly: true });
    return [google];
  }
  if (kind === "registration") {
    // One entry point per method: Google first, then the ordinary form with a stable field order.
    const order = ["csrf_token", "traits.email", "traits.username", "traits.dateOfBirth", "password"];
    for (const method of methods) {
      if (method.name === "password") method.fields.sort((a, b) => order.indexOf(a.name) - order.indexOf(b.name));
    }
    methods.sort((a, b) => Number(b.name === "oidc") - Number(a.name === "oidc"));
  }
  if (!methods.length || methods.length > 3 || new Set(methods.map((method) => method.name)).size !== methods.length) {
    throw new TypeError("Unsupported provider methods");
  }
  return methods;
}
