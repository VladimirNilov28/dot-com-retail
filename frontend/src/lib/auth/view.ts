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

export function formMessages(values: unknown): string[] {
  if (values === undefined) return [];
  if (!Array.isArray(values) || values.length > 30) throw new TypeError("Invalid provider messages");
  return values.map((value) => {
    const message = object(value);
    if (message.type === "error") {
      if (message.id === 4000006) return "Email or password is incorrect. Please try again.";
      if (typeof message.text === "string" && safeHookMessages.has(message.text)) return message.text;
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
      const errorMessages = formMessages(candidate.messages);
      const error = errorMessages.length ? input.name === "password"
        ? "Choose a strong, unique password that meets the account security policy."
        : input.name === "traits.dateOfBirth" ? "Enter a valid date of birth." : "Please check this field." : undefined;
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
