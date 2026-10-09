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
import { formMessages, formMethods } from "@/lib/auth/view";

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
        return { kind: "flow" as const, flow, csrf: state.csrf, returnTo: state.returnTo,
          methods: kind === "verification" && flow.state === "passed_challenge" ? [] : formMethods(flow.nodes, kind),
          messages: formMessages(flow.messages) };
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
  const methods = view.methods;
  const secondFactor = kind === "login" && methods.every((method) => method.name !== "password");
  const messages = view.messages;
  const validation = query.validation === "1" || view.flow.messages.some((message) => message.type === "error") ||
    methods.some((method) => method.fields.some((field) => field.error));
  const title = secondFactor ? "Two-step verification" : kind === "login" ? "Sign in" :
    kind === "registration" ? "Create your account" : "Verify your email";
  const description = secondFactor ? "Confirm it's you using your authenticator or a recovery code." :
    kind === "login" ? "Sign in to your ByteCore account." :
      kind === "registration" ? "Create your account, verify your email, then sign in." :
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
        {messages.length || validation ? (
          <div id={validation ? "auth-error-summary" : undefined}
            className={validation ? "auth-summary" : "mb-5 text-sm text-muted"}
            role={validation ? "alert" : "status"} tabIndex={validation ? -1 : undefined}>
            {(messages.length ? messages : ["Check your details and try again."]).map((message) => <p key={message}>{message}</p>)}
          </div>
        ) : null}
        {methods.map((method, index) => (
          <div key={method.name} className={index ? "auth-alternative-method" : undefined}>
            {methods.length > 1 ? <h2 className="mb-3 font-semibold">{method.name === "lookup_secret" ? "Recovery" : "Authenticator"}</h2> : null}
            <AuthForm kind={kind} flow={view.flow.id} csrf={view.csrf} returnTo={view.returnTo} method={method} />
          </div>
        ))}
      </div>
      <div className="auth-secondary text-sm">
        {kind === "registration" ? <>Already have an account? <Link prefetch={false} className="store-link" href={`/login?returnTo=${encodeURIComponent(view.returnTo)}`}>Sign in</Link></> :
          kind === "login" ? <>New to ByteCore? <Link prefetch={false} className="store-link" href={`/register?returnTo=${encodeURIComponent(view.returnTo)}`}>Create an account</Link></> :
            <Link prefetch={false} className="store-link" href={`/auth/flow?kind=verification&returnTo=${encodeURIComponent(view.returnTo)}`}>Request a new verification code</Link>}
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
        kind === "registration" ? "Registration is unavailable" : kind === "verification" ? "Verification is unavailable" : "Sign-in is unavailable" :
        code === "provider_logout_pending" ? "Signed out of ByteCore" :
          code === "expired" ? "This session has expired" : "We couldn't complete this request"}</h1>
      <p role="alert" className="text-muted">{unavailable ? "We couldn't confirm this request. Please try again." :
        code === "provider_logout_pending" ? "You're signed out of ByteCore. Provider logout could not be confirmed and will be retried." :
          "Start a new secure flow to continue."}</p>
      <Link prefetch={false} className="store-cta" href={kind === "login" ? `/auth/start?returnTo=${encodeURIComponent(returnTo)}` :
        `/auth/flow?kind=${kind}&returnTo=${encodeURIComponent(returnTo)}`}>{unavailable ? "Retry" : "Start again"}</Link>
      <Link className="store-link" href="/catalog/all-products">Continue browsing</Link>
    </section>
  );
}
