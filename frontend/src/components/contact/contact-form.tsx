"use client";

import { Button } from "@heroui/react";
import { useRef, useState, type FormEvent } from "react";
import { contactErrors, contactLimits, contactSubjects, type ContactErrors, type ContactFields } from "@/lib/contact/model";
import { requestGuest } from "@/lib/graphql/browser";
import { submitContactMessage } from "@/lib/graphql/contact-operations";

const empty: ContactFields = { name: "", email: "", subject: "", message: "" };
const order: (keyof ContactFields)[] = ["name", "email", "subject", "message"];

export function ContactForm() {
  const [fields, setFields] = useState(empty);
  const [website, setWebsite] = useState("");
  const [errors, setErrors] = useState<ContactErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reference, setReference] = useState<string | null>(null);
  const form = useRef<HTMLFormElement>(null);

  function focusFirst(found: ContactErrors) {
    const first = order.find((key) => found[key]);
    if (first) form.current?.querySelector<HTMLElement>(`[name="${first}"]`)?.focus();
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const found = contactErrors(fields);
    setErrors(found);
    if (Object.keys(found).length) { focusFirst(found); return; }
    setPending(true);
    const result = await requestGuest(submitContactMessage, { input: {
      name: fields.name.trim(), email: fields.email.trim(), subject: fields.subject as never,
      message: fields.message.trim(), ...(website ? { website } : {}),
    } });
    setPending(false);
    if (result.status !== "success") { setFormError(result.error.message); return; }
    const outcome = result.data.submitContactMessage;
    if (!outcome.accepted) {
      const server: ContactErrors = {};
      for (const error of outcome.fieldErrors) {
        if ((order as string[]).includes(error.field)) server[error.field as keyof ContactFields] = error.message;
      }
      setErrors(server);
      if (Object.keys(server).length) focusFirst(server);
      else setFormError("Your message could not be sent. Check the form and try again.");
      return;
    }
    setReference(outcome.reference);
    setFields(empty);
  }

  if (reference) {
    return (
      <section className="account-card" role="status" aria-live="polite">
        <h2 className="text-lg font-semibold">Thanks — your message was sent</h2>
        <p className="text-muted">Your reference is <strong className="text-foreground">{reference}</strong>. We&apos;ve emailed you a confirmation and will reply to the address you gave.</p>
        <div><Button variant="secondary" onPress={() => setReference(null)}>Send another message</Button></div>
      </section>
    );
  }

  const describedBy = (key: keyof ContactFields, extra?: string) =>
    [errors[key] ? `contact-${key}-error` : null, extra].filter(Boolean).join(" ") || undefined;
  const error = (key: keyof ContactFields) =>
    errors[key] ? <small id={`contact-${key}-error`} className="cart-line-issue">{errors[key]}</small> : null;

  return (
    <form ref={form} className="checkout-section contact-form" onSubmit={submit} noValidate aria-label="Contact form">
      <div className="checkout-address-grid">
        <label>
          <span>Name</span>
          <input name="name" value={fields.name} autoComplete="name" required maxLength={contactLimits.name}
            aria-invalid={!!errors.name} aria-describedby={describedBy("name")}
            onChange={(event) => setFields({ ...fields, name: event.target.value })} />
          {error("name")}
        </label>
        <label>
          <span>Email</span>
          <input name="email" type="email" value={fields.email} autoComplete="email" required maxLength={contactLimits.email}
            aria-invalid={!!errors.email} aria-describedby={describedBy("email")}
            onChange={(event) => setFields({ ...fields, email: event.target.value })} />
          {error("email")}
        </label>
        <label className="checkout-wide">
          <span>Topic</span>
          <select name="subject" value={fields.subject} required aria-invalid={!!errors.subject} aria-describedby={describedBy("subject")}
            onChange={(event) => setFields({ ...fields, subject: event.target.value })}>
            <option value="">Choose a topic</option>
            {contactSubjects.map((subject) => <option key={subject.value} value={subject.value}>{subject.label}</option>)}
          </select>
          {error("subject")}
        </label>
        <label className="checkout-wide">
          <span>Message</span>
          <textarea name="message" value={fields.message} required rows={7} maxLength={contactLimits.messageMax}
            aria-invalid={!!errors.message} aria-describedby={describedBy("message", "contact-message-hint")}
            onChange={(event) => setFields({ ...fields, message: event.target.value })} />
          <small id="contact-message-hint" className="text-muted">
            {contactLimits.messageMin}–{contactLimits.messageMax} characters ({fields.message.trim().length} used).
          </small>
          {error("message")}
        </label>
      </div>
      {/* Honeypot: hidden from people and assistive technology; bots that fill it are silently ignored. */}
      <div className="contact-trap" aria-hidden="true">
        <label>Website<input name="website" tabIndex={-1} autoComplete="off" value={website} onChange={(event) => setWebsite(event.target.value)} /></label>
      </div>
      {formError ? <p role="alert" className="cart-line-issue">{formError}</p> : null}
      <p className="text-sm text-muted">We use your name and email only to answer this message.</p>
      <div><Button type="submit" isDisabled={pending}>{pending ? "Sending…" : "Send message"}</Button></div>
    </form>
  );
}
