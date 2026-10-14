import {
  SubmitContactMessageSource, type ContactMessageInput, type ContactSubject, type SubmitContactMessageMutation,
} from "./generated";
import type { Operation } from "./operations";
import { boolean, list, object, text } from "./scalars";
import { contactLimits, contactSubjects } from "@/lib/contact/model";

function bounded(value: unknown, max: number) {
  const result = text(value);
  if (result.length > max) throw new TypeError("Contact field too long");
  return result;
}

export const submitContactMessage: Operation<SubmitContactMessageMutation, { input: ContactMessageInput }> = {
  name: "SubmitContactMessage", source: SubmitContactMessageSource, kind: "mutation", access: "guest",
  variables(value) {
    const root = object(value);
    if (Object.keys(root).some((key) => key !== "input")) throw new TypeError("Unexpected variables");
    const input = object(root.input);
    if (Object.keys(input).some((key) => !["name", "email", "subject", "message", "website"].includes(key))) {
      throw new TypeError("Unexpected contact fields");
    }
    const subject = text(input.subject);
    if (!contactSubjects.some((candidate) => candidate.value === subject)) throw new TypeError("Invalid contact subject");
    // Generous transport bounds; precise rules (and field errors) come from the backend.
    return { input: {
      name: bounded(input.name, 1000), email: bounded(input.email, 1000), subject: subject as ContactSubject,
      message: bounded(input.message, contactLimits.messageMax * 2),
      ...(input.website === undefined ? {} : { website: bounded(input.website, 1000) }),
    } };
  },
  decode(value) {
    const result = object(object(value).submitContactMessage);
    const reference = result.reference === null ? null : text(result.reference);
    return { submitContactMessage: {
      accepted: boolean(result.accepted), reference,
      fieldErrors: list(result.fieldErrors, (item) => {
        const error = object(item);
        return { field: text(error.field), message: text(error.message) };
      }),
    } };
  },
};
