"use client";

import { Button, Description, FieldError, Input, Label, TextField } from "@heroui/react";
import { LoaderCircle, RotateCcw } from "lucide-react";
import { createElement, useEffect, useRef, useState, type FormEvent } from "react";
import { flushSync } from "react-dom";
import type { AuthMethod } from "@/lib/auth/view";
import type { FormKind } from "@/lib/auth/model";

function Captcha({ flow, returnTo }: { flow: string; returnTo: string }) {
  const widget = useRef<HTMLElement>(null);
  const [status, setStatus] = useState("loading");
  useEffect(() => {
    const element = widget.current;
    if (!element) return;
    let active = true;
    function change(event: Event) {
      if (event instanceof CustomEvent && event.detail && typeof event.detail.state === "string") {
        setStatus(event.detail.state);
      }
    }
    element.addEventListener("statechange", change);
    import("altcha").then(() => {
      if (active) setStatus("unverified");
    }).catch(() => {
      console.error("Security check widget could not load");
      if (active) setStatus("error");
    });
    return () => { active = false; element.removeEventListener("statechange", change); };
  }, []);
  const messages: Record<string, string> = {
    loading: "Loading the security check...", unverified: "Complete the security check before creating your account.",
    verifying: "Checking...", verified: "Security check complete.", expired: "Security check expired. Please check again.",
    error: "The security check could not load. Reload it and try again.",
  };
  return (
    <div className="auth-captcha">
      <p className="font-medium">Security check</p>
      {createElement("altcha-widget", { ref: widget, challenge: `/auth/captcha?flow=${flow}`, name: "altcha", theme: "dark" })}
      <p className="auth-captcha-status text-sm text-muted" role="status">{messages[status] ?? "Complete the security check."}</p>
      {status === "error" || status === "expired" ? (
        <a href={`/register?flow=${flow}&returnTo=${encodeURIComponent(returnTo)}`} className="store-link inline-flex items-center gap-2">
          <RotateCcw size={16} aria-hidden="true" /> Reload security check
        </a>
      ) : null}
      <noscript><style>{".auth-captcha-status { display: none; }"}</style>
        <p className="text-sm text-warning">Creating an account requires JavaScript for the self-hosted security check. You can still sign in without JavaScript.</p>
      </noscript>
    </div>
  );
}

export function AuthForm({ kind, flow, csrf, returnTo, method }: {
  kind: FormKind; flow: string; csrf: string; returnTo: string; method: AuthMethod;
}) {
  const [pending, setPending] = useState(false);
  const [captchaError, setCaptchaError] = useState(false);
  const [edited, setEdited] = useState<Record<string, boolean>>({});
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => {
    document.getElementById("auth-error-summary")?.focus();
  }, []);
  function submit(event: FormEvent<HTMLFormElement>) {
    if (pending) { event.preventDefault(); return; }
    if (kind === "registration" && !new FormData(event.currentTarget).get("altcha")) {
      event.preventDefault();
      setCaptchaError(true);
      event.currentTarget.querySelector<HTMLElement>("altcha-widget")?.focus();
      return;
    }
    // Commit the disabled state before the native POST starts navigation.
    flushSync(() => setPending(true));
  }
  return (
    <form ref={form} method="post" action={`/auth/submit?kind=${kind}&returnTo=${encodeURIComponent(returnTo)}`} onSubmit={submit}
      className="auth-form" aria-busy={pending}>
      <input type="hidden" name="flow" value={flow} />
      <input type="hidden" name="_csrf" value={csrf} />
      <input type="hidden" name="method" value={method.name} />
      {method.fields.map((field) => field.type === "hidden"
        ? <input key={field.name} type="hidden" name={field.name} value={field.value} />
        : (
          <TextField key={field.name} name={field.name} type={field.type} defaultValue={field.value}
            isRequired={field.required} isInvalid={Boolean(field.error) && !edited[field.name]} validationBehavior="native"
            className="auth-field" autoComplete={field.autocomplete}
            onChange={() => setEdited((previous) => ({ ...previous, [field.name]: true }))}>
            <Label>{field.label}</Label>
            <Input className="auth-input" inputMode={["totp_code", "code"].includes(field.name) ? "numeric" : undefined} />
            {field.name === "password" && kind === "registration"
              ? <Description>Use a strong password you don&apos;t use elsewhere.</Description> : null}
            {field.error && !edited[field.name] ? <FieldError>{field.error}</FieldError> : null}
          </TextField>
        ))}
      {kind === "registration" ? <Captcha flow={flow} returnTo={returnTo} /> : null}
      {captchaError ? <p className="text-sm text-danger" role="alert">Complete the security check, then try again.</p> : null}
      <Button type="submit" variant={method.name === "lookup_secret" ? "secondary" : "primary"}
        isDisabled={pending} className="auth-submit">
        {pending ? <LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> : null}
        {pending ? "Submitting..." : method.label}
      </Button>
      <p className="sr-only" role="status">{pending ? "Submitting your form. Please wait." : ""}</p>
    </form>
  );
}
