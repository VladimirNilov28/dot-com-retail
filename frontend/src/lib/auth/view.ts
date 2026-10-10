import { object } from "@/lib/graphql/scalars";
import type { FormKind } from "./model";

export interface AuthField {
  name: string;
  label: string;
  type: "text" | "email" | "password" | "date" | "hidden";
  required: boolean;
  value: string;
  error?: string;
  autocomplete?: string;
}

export interface AuthMethod {
  name: "password" | "totp" | "lookup_secret" | "code";
  label: string;
  fields: AuthField[];
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
      if ([4000008, 4000012, 4000016, 4070006].includes(Number(message.id))) return "The code is incorrect, expired or already used. Try again.";
      if ([4010001, 4040001, 4070005].includes(Number(message.id))) return "This form has expired. Start a new secure flow.";
      if (typeof message.text === "string" && safeHookMessages.has(message.text)) return message.text;
      console.error("authentication validation reason", "unrecognized_provider_validation");
      return "Check the details below and try again.";
    }
    if (message.type === "info" || message.type === "success") {
      if (message.id === 1080001) return "Check your inbox for the verification code.";
      if (message.id === 1080002) return "Your email is verified.";
      return "";
    }
    throw new TypeError("Invalid provider message type");
  }).filter(Boolean);
}

export function formMethods(nodes: Record<string, unknown>[], kind: FormKind): AuthMethod[] {
  const allowed = kind === "login" ? ["password", "totp", "lookup_secret"] :
    kind === "registration" ? ["password"] : ["code"];
  const methods: AuthMethod[] = [];
  for (const node of nodes) {
    if (node.type !== "input") continue;
    const attributes = object(node.attributes);
    if (attributes.name !== "method" || attributes.type !== "submit" || !allowed.includes(String(attributes.value))) continue;
    const name = attributes.value;
    if (name !== "password" && name !== "totp" && name !== "lookup_secret" && name !== "code") continue;
    const fields: AuthField[] = [];
    for (const candidate of nodes) {
      if (candidate.type !== "input" || (candidate.group !== "default" && candidate.group !== node.group)) continue;
      const input = object(candidate.attributes);
      if (input.name === "method" || input.type === "submit") continue;
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
        name: input.name, label: labels[input.name], type, required: input.required === true,
        value: sensitive.has(input.name) ? "" : typeof input.value === "string" ? input.value : "",
        error, autocomplete: type === "hidden" ? undefined : input.name === "password"
          ? kind === "registration" ? "new-password" : "current-password"
          : sensitive.has(input.name) ? "one-time-code"
            : input.name === "traits.dateOfBirth" ? "bday" : input.name === "traits.username" ? "username" : "email",
      });
    }
    if (!fields.some((field) => field.name === "csrf_token")) throw new TypeError("Provider CSRF control is missing");
    methods.push({ name, fields, label: name === "totp" ? "Verify code" : name === "lookup_secret" ? "Use recovery code" :
      kind === "registration" ? "Create account" : kind === "verification"
        ? fields.some((field) => field.name === "code" && field.type !== "hidden") ? "Verify email" : "Send verification code"
        : "Sign in" });
  }
  if (!methods.length || methods.length > 3 || new Set(methods.map((method) => method.name)).size !== methods.length) {
    throw new TypeError("Unsupported provider methods");
  }
  return methods;
}
