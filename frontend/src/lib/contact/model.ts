import type { ContactSubject } from "@/lib/graphql/generated";

export const contactSubjects: readonly { value: ContactSubject; label: string }[] = [
  { value: "ORDER", label: "Order question" },
  { value: "PAYMENT", label: "Payment or refund" },
  { value: "PRODUCT", label: "Product question" },
  { value: "ACCOUNT", label: "Account and sign-in" },
  { value: "FEEDBACK", label: "Feedback" },
  { value: "OTHER", label: "Something else" },
];

export const contactLimits = { name: 100, email: 254, messageMin: 10, messageMax: 4000 } as const;

export type ContactFields = { name: string; email: string; subject: string; message: string };
export type ContactErrors = Partial<Record<keyof ContactFields, string>>;

const controlCharacters = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/;

/** Mirrors ContactService on the backend; the server remains authoritative. */
export function contactErrors(fields: ContactFields): ContactErrors {
  const errors: ContactErrors = {};
  const name = fields.name.trim();
  const email = fields.email.trim();
  const message = fields.message.trim();
  if (!name) errors.name = "Enter your name.";
  else if (name.length > contactLimits.name) errors.name = "Name must be at most 100 characters.";
  else if (/[\u0000-\u001f\u007f-\u009f]/.test(name)) errors.name = "Name contains invalid characters.";
  if (!email) errors.email = "Enter your email address.";
  else if (email.length > contactLimits.email || !/^[^\s@<>"(),;:]+@[^\s@<>"(),;:]+\.[^\s@<>"(),;:]+$/.test(email)) {
    errors.email = "Enter a valid email address.";
  }
  if (!contactSubjects.some((subject) => subject.value === fields.subject)) errors.subject = "Choose a topic.";
  if (message.length < contactLimits.messageMin) errors.message = "Message must be at least 10 characters.";
  else if (message.length > contactLimits.messageMax) errors.message = "Message must be at most 4000 characters.";
  else if (controlCharacters.test(message)) errors.message = "Message contains invalid characters.";
  return errors;
}
