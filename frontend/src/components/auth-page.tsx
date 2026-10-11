import "server-only";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import Link from "next/link";
import { KeyRound, ShieldCheck } from "lucide-react";
import { AuthForm } from "./auth-form";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, flowIdentifier, safeReturnTo, type FormKind } from "@/lib/auth/model";
import { readFlow } from "@/lib/auth/flows";
import { withBrowser } from "@/lib/auth/store";
import { authFieldId, formMessages, formMethods, temporaryAuthMessage } from "@/lib/auth/view";
import { passwordErrorMessage } from "@/lib/auth/password";

export type AuthSearch = Promise<Record<string, string | string[] | undefined>>;

export async function AuthPage({ kind, searchParams }: { kind: FormKind; searchParams: AuthSearch }) {
  const query = await searchParams;
  let flowId: string | undefined;
  let returnTo = "/";
  try {
    returnTo = safeReturnTo(query.returnTo);
    if (Array.isArray(query.flow)) throw new AuthError("invalid_request", 400);
    flowId = query.flow;
  } catch (error) {
    if (!(error instanceof AuthError)) throw error;
    console.error("authentication return destination rejected", error.code);
    return <AuthProblem code="invalid_request" kind={kind} returnTo={returnTo} />;
  }
  if (!flowId) {
    if (kind === "reset") return <AuthProblem code="expired" kind={kind} returnTo={returnTo} />;
    redirect(kind === "login" ? `/auth/start?returnTo=${encodeURIComponent(returnTo)}`
      : `/auth/flow?kind=${kind}&returnTo=${encodeURIComponent(returnTo)}`);
  }
  let view;
  try {
    const config = authConfig();
    const browser = authCookie((await headers()).get("cookie"), config.flowCookie);
    if (!browser) throw new AuthError("expired", 401);
    view = await withBrowser(browser, async (state, client) => {
      try {
        const flow = await readFlow(state, browser, flowIdentifier(flowId), kind, client);
        const google = kind === "registration" && state.googleRegistration?.flow === flow.id ? state.googleRegistration : undefined;
        return { kind: "flow" as const, flow, csrf: state.csrf, returnTo: state.returnTo,
          google,
          methods: kind === "verification" && flow.state === "passed_challenge" ? [] : formMethods(flow.nodes, kind, google),
          messages: [...new Set([...formMessages(google && (!google.submitted || !google.providerValidation) ?
            flow.messages.filter((message) => Number(message.id) >= 5000000) : flow.messages, kind), ...flow.nodes.flatMap((node) =>
            formMessages(node.messages, kind).filter((message) => message === temporaryAuthMessage))])] };
      } catch (error) {
        if (!(error instanceof AuthError)) throw error;
        console.error("authentication form unavailable", error.code);
        return { kind: "problem" as const, code: error.code, returnTo: state.returnTo };
      }
    });
  } catch (error) {
    console.error("authentication form unavailable", error instanceof AuthError ? error.code : "upstream");
    return <AuthProblem code={error instanceof AuthError ? error.code : "unavailable"} kind={kind} returnTo={returnTo} />;
  }
  if (view.kind === "problem") return <AuthProblem code={view.code} kind={kind} returnTo={view.returnTo} />;
  if (kind === "verification" && view.flow.state === "passed_challenge") {
    redirect(`/auth/start?returnTo=${encodeURIComponent(view.returnTo)}`);
  }
  const passwordError = (kind === "registration" || kind === "reset") && query.validation === "1" ? passwordErrorMessage(query.passwordError) : undefined;
  const providerMethods = passwordError ? view.methods.map((method) => ({
    ...method, fields: method.fields.map((field) => field.name === "password" ? { ...field, error: passwordError } : field),
  })) : view.methods;
  const secondFactor = kind === "login" && providerMethods.every((method) => method.name !== "password" && method.name !== "oidc");
  // Recovery codes are a separate lost-authenticator path, never a peer sign-in option.
  const recoveryAvailable = secondFactor && providerMethods.some((method) => method.name === "lookup_secret") &&
    providerMethods.some((method) => method.name !== "lookup_secret");
  const recoveryCode = secondFactor && providerMethods.some((method) => method.name === "lookup_secret") &&
    (query.method === "recovery" || !recoveryAvailable);
  const methods = secondFactor ? providerMethods.filter((method) => (method.name === "lookup_secret") === recoveryCode)
    : providerMethods;
  const flowLink = (method?: string) => `/login?flow=${view.flow.id}&returnTo=${encodeURIComponent(view.returnTo)}` +
    (method ? `&method=${method}` : "");
  const messages = view.messages;
  const fieldErrors = methods.flatMap((method) => method.fields.filter((field) => field.error).map((field) => ({
    id: authFieldId(method.name, field.name), label: field.label, error: field.error,
  })));
  const validation = query.validation === "1" || ((!view.google || view.google.providerValidation) &&
    view.flow.messages.some((message) => message.type === "error")) ||
    methods.some((method) => method.fields.some((field) => field.error));
  const title = recoveryCode ? "Use a recovery code" : secondFactor ? "Two-step verification" : kind === "login" ? "Sign in" :
    kind === "registration" ? view.google ? "Complete your registration" : "Create your account" : kind === "recovery" ? "Recover your account" :
      kind === "reset" ? "Set a new password" : "Verify your email";
  const description = recoveryCode ? "Lost access to your authenticator? Enter one of your saved recovery codes. Each code works once." :
    secondFactor ? "Enter the 6-digit code from your authenticator app." :
    kind === "login" ? "Sign in to your ByteCore account." :
      kind === "registration" ? view.google ? "Your Google email is verified. Add your missing account details to continue. No password is needed." :
        "Create your account, verify your email, then sign in." :
        kind === "recovery" ? "Request a secure email code to reset your password." :
          kind === "reset" ? "Choose a new password, then sign in again. Other sessions will be invalidated." :
        "Complete email verification before signing in.";
  return (
    <section className="auth-page" aria-labelledby="auth-title">
      <div className="auth-heading">
        {secondFactor || kind === "verification"
          ? <ShieldCheck size={24} className="text-accent" aria-hidden="true" />
          : <KeyRound size={24} className="text-accent" aria-hidden="true" />}
        <h1 id="auth-title" className="text-2xl font-semibold tracking-tight sm:text-3xl">{title}</h1>
        <p className="text-muted">{description}</p>
      </div>
      <div className="auth-panel">
        {kind === "login" && query.social === "conflict" ? <p role="alert" className="mb-5 text-sm text-muted">
          This Google email may belong to an existing account. Sign in using that account&apos;s existing method,
          then open Account security and choose Connect Google. Restart the connection there; no accounts have been merged.
        </p> : null}
        {kind === "registration" && messages.some((message) => message.includes("cannot be registered")) ? <p className="mb-5 text-sm text-muted">
          If this is your existing account, <a className="store-link" href="/login?returnTo=%2Faccount%2Fsecurity">sign in with its existing method</a>,
          then choose Connect Google in Account security. Restart the connection there; matching emails do not merge accounts.
        </p> : null}
        {kind === "login" && (query.social === "cancelled" || query.social === "failed") ? <p role="alert" className="mb-5 text-sm text-muted">
          {query.social === "cancelled" ? "Google sign-in was cancelled. Try again or use your password." :
            "Google sign-in could not complete. Start again or use your password."}
        </p> : null}
        {kind === "login" && query.reset === "success" ? <p role="status" className="mb-5 text-sm text-muted">Your password has changed. Sign in with your new password.</p> : null}
        {messages.length || validation ? (
          <div id={validation ? "auth-error-summary" : undefined}
            className={validation ? "auth-summary" : "mb-5 text-sm text-muted"}
            role={validation ? "alert" : "status"} tabIndex={validation ? -1 : undefined}>
            {validation ? <h2 className="font-semibold">We couldn&apos;t submit your form</h2> : null}
            {(messages.length ? messages : fieldErrors.length ? [] : ["Check your details and try again."]).map((message) => <p key={message}>{message}</p>)}
            {fieldErrors.length ? <p>Check {fieldErrors.map((field, index) => <span key={field.id}>
              {index ? ", " : ""}<a className="store-link" href={`#${field.id}`}>{field.label.toLowerCase()}</a>
            </span>)}.</p> : null}
          </div>
        ) : null}
        {recoveryCode ? <p className="mb-5 text-sm text-muted">After you continue, your other sessions are signed out and we email you about this
          recovery. Replace your authenticator from Account security right away.</p> : null}
        {methods.map((method, index) => (
          <div key={method.name} className={index ? "auth-alternative-method" : undefined}>
            {methods.length > 1 ? <h2 className="mb-3 font-semibold">{method.name === "lookup_secret" ? "Recovery" :
              method.name === "oidc" ? "Google" : method.name === "password" ? "Email and password" : "Authenticator"}</h2> : null}
            <AuthForm kind={kind} flow={view.flow.id} csrf={view.csrf} returnTo={view.returnTo} method={method} />
          </div>
        ))}
      </div>
      <div className="auth-secondary text-sm">
        {kind === "registration" ? <>Already have an account? <a className="store-link" href={`/login?returnTo=${encodeURIComponent(view.returnTo)}`}>Sign in</a></> :
          kind === "login" ? <>New to ByteCore? <a className="store-link" href={`/register?returnTo=${encodeURIComponent(view.returnTo)}`}>Create an account</a></> :
            kind === "recovery" || kind === "reset" ? <a className="store-link" href="/recover">Request a new recovery code</a> :
            <a className="store-link" href={`/auth/flow?kind=verification&returnTo=${encodeURIComponent(view.returnTo)}`}>Request a new verification code</a>}
        {kind === "login" && !secondFactor ? <a className="store-link" href="/recover">Forgot your password?</a> : null}
        {recoveryAvailable && !recoveryCode ? <a className="store-link" href={flowLink("recovery")}>Lost your authenticator? Use a recovery code</a> : null}
        {recoveryAvailable && recoveryCode ? <a className="store-link" href={flowLink()}>Use your authenticator app instead</a> : null}
        <Link href="/catalog/all-products" className="store-link">Continue browsing</Link>
      </div>
    </section>
  );
}

export function AuthProblem({ code, kind = "login", returnTo = "/" }: {
  code: string; kind?: FormKind; returnTo?: string;
}) {
  const unavailable = code === "unavailable";
  return (
    <section className="auth-page" aria-labelledby="auth-error-title">
      <h1 id="auth-error-title" className="text-2xl font-semibold tracking-tight">{unavailable ?
        kind === "registration" ? "Registration is unavailable" : kind === "verification" ? "Verification is unavailable" :
          kind === "recovery" ? "Recovery is unavailable" : kind === "reset" ? "Password reset is unavailable" : "Sign-in is unavailable" :
        code === "provider_logout_pending" ? "Signed out of ByteCore" :
          code === "expired" ? "This form has expired" : code === "rate_limited" ? "Too many attempts" : "We couldn't complete this request"}</h1>
      <p role="alert" className="text-muted">{unavailable ? "The authentication service is temporarily unavailable. This does not mean your password was rejected. Wait a moment, then retry." :
        code === "provider_logout_pending" ? "You're signed out of ByteCore. Provider logout could not be confirmed and will be retried." :
          code === "rate_limited" ? "For your security, further attempts are paused. Wait 15 minutes, then try again." :
          kind === "verification" ? "Return to the browser where you registered and use its latest email. If that form expired, start again and request a new code." :
            kind === "recovery" || kind === "reset" ? "Return to the browser where you requested recovery and use its latest email, or request a new code." :
          "Start a new secure flow to continue."}</p>
      <a className="store-cta" href={kind === "login" ? `/auth/start?returnTo=${encodeURIComponent(returnTo)}` :
        kind === "reset" ? "/recover" :
        `/auth/flow?kind=${kind}&returnTo=${encodeURIComponent(returnTo)}`}>{unavailable ? "Retry" : "Start again"}</a>
      <Link className="store-link" href="/catalog/all-products">Continue browsing</Link>
    </section>
  );
}
