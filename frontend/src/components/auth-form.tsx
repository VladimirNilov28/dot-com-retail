"use client";

import { Button, FieldError, Input, Label, TextField } from "@heroui/react";
import { Eye, EyeOff, LoaderCircle, RotateCcw } from "lucide-react";
import { createElement, useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { flushSync } from "react-dom";
import { authFieldId, captchaFailure, methodNeedsCaptcha, type AuthMethod } from "@/lib/auth/view";
import type { FormKind } from "@/lib/auth/model";
import { passwordGuidance, passwordErrorMessage, registrationPasswordError } from "@/lib/auth/password";
import { verificationLinkCode } from "@/lib/auth/verification";

const subscribeHydration = () => () => {};
const clientSnapshot = () => true;
const serverSnapshot = () => false;

function PasswordGuidance({ inputId }: { inputId: string }) {
  const [lengthError, setLengthError] = useState<string>();
  const [score, setScore] = useState<number>();
  const [strengthState, setStrengthState] = useState("");
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
      setLengthError(input.value ? passwordErrorMessage(registrationPasswordError(input.value)) : undefined);
      setScore(undefined);
      setStrengthState("");
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
      <p>{passwordGuidance}</p>
      {lengthError ? <p className="text-danger" role="status">{lengthError}</p> : null}
      {score !== undefined ? <div className="auth-strength">
        <meter min={0} max={4} value={score} aria-label={`Estimated password strength: ${strengthState}`} />
        <p role="status">Strength: {strengthState}</p>
      </div> : null}
    </div>
  );
}

function Captcha({ flow, returnTo, method }: { flow: string; returnTo: string; method: string }) {
  const widget = useRef<HTMLElement>(null);
  const [status, setStatus] = useState("loading");
  const [failure, setFailure] = useState<ReturnType<typeof captchaFailure>>("captcha");
  const [retry, setRetry] = useState(0);
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
  }, [retry]);
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
      {createElement("altcha-widget", { key: retry, ref: widget, id: `auth-security-check-${method}`, tabIndex: -1, challenge: `/auth/captcha?flow=${flow}`, name: "altcha", theme: "dark" })}
      <p className="auth-captcha-status text-sm text-muted" role="status">{messages[status] ?? "Complete the security check."}</p>
      {status === "error" || status === "expired" ? (
        failure === "expired" ? <a href={`/auth/flow?kind=registration&returnTo=${encodeURIComponent(returnTo)}`}
          className="store-link inline-flex items-center gap-2"><RotateCcw size={16} aria-hidden="true" /> Start registration again</a> :
          <Button variant="secondary" onPress={() => { setStatus("loading"); setRetry((value) => value + 1); }}>
            <RotateCcw size={16} aria-hidden="true" /> Reload security check
          </Button>
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
  useEffect(() => {
    if (!["verification", "recovery"].includes(kind) || !location.hash) return;
    const code = verificationLinkCode(location.hash);
    history.replaceState(history.state, "", location.pathname + location.search);
    const input = form.current?.querySelector<HTMLInputElement>('input[name="code"]');
    if (!code || !input) return;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    if (!setter) return;
    setter.call(input, code);
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.focus();
  }, [kind]);
  function submit(event: FormEvent<HTMLFormElement>) {
    if (pending) { event.preventDefault(); return; }
    if (methodNeedsCaptcha(kind, method) && !new FormData(event.currentTarget).get("altcha")) {
      event.preventDefault();
      flushSync(() => setCaptchaError(true));
      document.getElementById(`auth-client-error-summary-${method.name}`)?.focus();
      return;
    }
    const submitter = (event.nativeEvent as SubmitEvent).submitter;
    if ((kind === "verification" || kind === "recovery") && submitter instanceof HTMLButtonElement && submitter.name === "email") {
      const email = document.createElement("input");
      email.type = "hidden";
      email.name = "email";
      email.value = submitter.value;
      event.currentTarget.append(email);
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
      {method.provider ? <input type="hidden" name="provider" value={method.provider} /> : null}
      {kind === "verification" ? <p className="text-sm text-muted" role="status">
        Stay in the browser where you registered. Email links fill the code; choose Verify email to continue.
      </p> : null}
      {kind === "recovery" ? <p className="text-sm text-muted" role="status">
        Use the browser where you requested recovery. Email links fill the code; submit it to continue.
      </p> : null}
      {method.fields.map((field) => field.type === "hidden"
        ? <input key={field.name} type="hidden" name={field.name} value={field.value} />
        : field.readOnly ? <label key={field.name} className="auth-field block space-y-2">
          <span>{field.label}</span>
          <input id={authFieldId(method.name, field.name)} type="email" readOnly value={field.value}
            className="auth-input w-full px-3" aria-describedby="google-email-guidance" />
          <span id="google-email-guidance" className="block text-sm text-muted">Verified by Google. To use a different email, restart with another Google account.</span>
        </label>
        : (
          <TextField key={field.name} id={authFieldId(method.name, field.name)} name={field.name}
            type={field.name === "password" && showPassword ? "text" : field.type} defaultValue={field.value}
            isRequired={field.required} isInvalid={Boolean(field.error) && !edited[field.name]} validationBehavior="native"
            className="auth-field" autoComplete={field.autocomplete}
            aria-describedby={field.name === "password" && (kind === "registration" || kind === "reset") ? `${authFieldId(method.name, field.name)}-guidance` : undefined}
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
            {field.name === "password" && (kind === "registration" || kind === "reset")
              ? <PasswordGuidance inputId={authFieldId(method.name, field.name)} /> : null}
          </TextField>
        ))}
      {methodNeedsCaptcha(kind, method) ? <Captcha flow={flow} returnTo={returnTo} method={method.name} /> : null}
      {captchaError ? <div id={`auth-client-error-summary-${method.name}`} className="auth-summary" role="alert" tabIndex={-1}>
        <p>Complete the security check, then try again. If it cannot load, use the recovery link above.</p>
        <a className="store-link" href={`#auth-security-check-${method.name}`}>Go to security check</a>
      </div> : null}
      <Button type="submit" variant="primary"
        isDisabled={pending} className="auth-submit">
        {pending ? <LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> : null}
        {pending ? "Submitting..." : method.label}
      </Button>
      {(kind === "verification" || kind === "recovery") && method.resendEmail ? <Button type="submit" variant="secondary"
        name="email" value={method.resendEmail} formNoValidate isDisabled={pending} className="auth-submit">
        Send a new code
      </Button> : null}
      <p className="sr-only" role="status">{pending ? "Submitting your form. Please wait." : ""}</p>
    </form>
  );
}
