"use client";

import { Button, FieldError, Input, Label, TextField } from "@heroui/react";
import { Eye, EyeOff, LoaderCircle, RotateCcw } from "lucide-react";
import { createElement, useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { flushSync } from "react-dom";
import { authFieldId, captchaFailure, type AuthMethod } from "@/lib/auth/view";
import type { FormKind } from "@/lib/auth/model";
import { passwordLengthFeedback } from "@/lib/auth/password";

const subscribeHydration = () => () => {};
const clientSnapshot = () => true;
const serverSnapshot = () => false;

function PasswordGuidance({ inputId }: { inputId: string }) {
  const [length, setLength] = useState<ReturnType<typeof passwordLengthFeedback>>();
  const [score, setScore] = useState<number>();
  const [strengthState, setStrengthState] = useState("Enter a password to see strength guidance.");
  useEffect(() => {
    const input = document.getElementById(inputId);
    if (!(input instanceof HTMLInputElement)) return;
    let active = true;
    let revision = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    function evaluate() {
      if (!(input instanceof HTMLInputElement)) return;
      const current = ++revision;
      clearTimeout(timer);
      setLength(input.value ? passwordLengthFeedback(input.value) : undefined);
      setScore(undefined);
      setStrengthState(input.value ? "Estimating strength locally..." : "Enter a password to see strength guidance.");
      if (!input.value) return;
      timer = setTimeout(() => {
        import("@/lib/auth/password-strength").then(({ passwordStrength, strengthLabels }) => {
          if (!active || current !== revision || !(input instanceof HTMLInputElement)) return;
          // Bound estimator work; over-limit passwords are already rejected by local length guidance.
          const result = passwordStrength(input.value.slice(0, 72));
          setScore(result);
          setStrengthState(strengthLabels[result]);
        }).catch(() => {
          console.error("authentication guidance reason", "strength_module_unavailable");
          if (active && current === revision) setStrengthState("Strength guidance is unavailable. Server validation still applies.");
        });
      }, 200);
    }
    input.addEventListener("input", evaluate);
    input.addEventListener("change", evaluate);
    evaluate();
    return () => { active = false; clearTimeout(timer); input.removeEventListener("input", evaluate); input.removeEventListener("change", evaluate); };
  }, [inputId]);
  return (
    <div id={`${inputId}-guidance`} className="auth-password-guidance text-sm text-muted">
      <p className="font-medium text-foreground">Password requirements</p>
      <ul className="list-disc pl-5">
        <li>Use 8–72 UTF-8 bytes. Plain English characters use one byte each; accented characters and emoji may use more.</li>
        <li>Do not use a password similar to your email address or found in a data breach. These checks happen on the server.</li>
        <li>No uppercase, number or symbol mix is required. Use a long, unique password or passphrase.</li>
      </ul>
      <p role="status">{length ? `${length.bytes} UTF-8 bytes · ${length.minimumMet ? "Minimum met" : "Too short"} · ${length.maximumMet ? "Within maximum" : "Too long"}` : ""}</p>
      <div className="auth-strength">
        <p role="status">Strength: {strengthState}</p>
        {score !== undefined ? <meter min={0} max={4} value={score} aria-label={`Password strength: ${strengthState}`} /> : null}
        <p>Strength is guidance, not acceptance. The server makes the final decision.</p>
      </div>
      <noscript><style>{".auth-strength { display: none; }"}</style></noscript>
    </div>
  );
}

function Captcha({ flow, returnTo }: { flow: string; returnTo: string }) {
  const widget = useRef<HTMLElement>(null);
  const [status, setStatus] = useState("loading");
  const [failure, setFailure] = useState<ReturnType<typeof captchaFailure>>("captcha");
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
    import("altcha").then(async () => {
      if (!active) return;
      const challengeFetch: typeof fetch = async (input, init) => {
        const response = await fetch(input, init);
        if (!response.ok && active) setFailure(captchaFailure(response.status));
        return response;
      };
      await (element as HTMLElementTagNameMap["altcha-widget"]).configure({ fetch: challengeFetch });
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
    error: failure === "expired" ? "This registration form expired or is no longer bound to this browser. Start a new registration." :
      failure === "service" ? "The security-check service is temporarily unavailable. Wait a moment, then reload the check." :
        "The security check failed. Reload it and try again.",
  };
  return (
    <div className="auth-captcha">
      <p className="font-medium">Security check</p>
      {createElement("altcha-widget", { ref: widget, id: "auth-security-check", tabIndex: -1, challenge: `/auth/captcha?flow=${flow}`, name: "altcha", theme: "dark" })}
      <p className="auth-captcha-status text-sm text-muted" role="status">{messages[status] ?? "Complete the security check."}</p>
      {status === "error" || status === "expired" ? (
        <a href={failure === "expired" ? `/auth/flow?kind=registration&returnTo=${encodeURIComponent(returnTo)}` :
          `/register?flow=${flow}&returnTo=${encodeURIComponent(returnTo)}`} className="store-link inline-flex items-center gap-2">
          <RotateCcw size={16} aria-hidden="true" /> {failure === "expired" ? "Start registration again" : "Reload security check"}
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
  const [showPassword, setShowPassword] = useState(false);
  const hydrated = useSyncExternalStore(subscribeHydration, clientSnapshot, serverSnapshot);
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => {
    document.getElementById("auth-error-summary")?.focus();
  }, []);
  function submit(event: FormEvent<HTMLFormElement>) {
    if (pending) { event.preventDefault(); return; }
    if (kind === "registration" && !new FormData(event.currentTarget).get("altcha")) {
      event.preventDefault();
      flushSync(() => setCaptchaError(true));
      document.getElementById("auth-client-error-summary")?.focus();
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
          <TextField key={field.name} id={authFieldId(method.name, field.name)} name={field.name}
            type={field.name === "password" && showPassword ? "text" : field.type} defaultValue={field.value}
            isRequired={field.required} isInvalid={Boolean(field.error) && !edited[field.name]} validationBehavior="native"
            className="auth-field" autoComplete={field.autocomplete}
            aria-describedby={field.name === "password" && kind === "registration" ? `${authFieldId(method.name, field.name)}-guidance` : undefined}
            onChange={() => setEdited((previous) => ({ ...previous, [field.name]: true }))}>
            <Label>{field.label}</Label>
            <Input className="auth-input"
              inputMode={["totp_code", "code"].includes(field.name) ? "numeric" : undefined} />
            {field.error && !edited[field.name] ? <FieldError>{field.error}</FieldError> : null}
            {field.name === "password" && hydrated ? <button type="button" className="store-link auth-password-toggle"
              aria-controls={authFieldId(method.name, field.name)} aria-pressed={showPassword}
              onClick={() => setShowPassword((value) => !value)}>
              {showPassword ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
              {showPassword ? "Hide password" : "Show password"}
            </button> : null}
            {field.name === "password" && kind === "registration"
              ? <PasswordGuidance inputId={authFieldId(method.name, field.name)} /> : null}
          </TextField>
        ))}
      {kind === "registration" ? <Captcha flow={flow} returnTo={returnTo} /> : null}
      {captchaError ? <div id="auth-client-error-summary" className="auth-summary" role="alert" tabIndex={-1}>
        <p>Complete the security check, then try again. If it cannot load, use the recovery link above.</p>
        <a className="store-link" href="#auth-security-check">Go to security check</a>
      </div> : null}
      <Button type="submit" variant={method.name === "lookup_secret" ? "secondary" : "primary"}
        isDisabled={pending} className="auth-submit">
        {pending ? <LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> : null}
        {pending ? "Submitting..." : method.label}
      </Button>
      <p className="sr-only" role="status">{pending ? "Submitting your form. Please wait." : ""}</p>
    </form>
  );
}
