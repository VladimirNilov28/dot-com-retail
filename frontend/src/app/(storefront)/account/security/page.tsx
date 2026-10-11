import type { Metadata } from "next";
import Link from "next/link";
import { headers } from "next/headers";
import { AuthProblem } from "@/components/auth-page";
import { SecuritySettings } from "@/components/security-settings";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, hasSessionCookie } from "@/lib/auth/model";
import { loadSecuritySettings } from "@/lib/auth/settings";

export const metadata: Metadata = { title: "Account security", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function SecurityPage({ searchParams }: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return (
    <section className="auth-page">
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Account security</h1>
      <p className="text-muted">Sign in to manage Google, your authenticator and backup codes.</p>
      <a className="store-cta" href="/login?returnTo=%2Faccount%2Fsecurity">Sign in</a>
    </section>
  );
  let settings;
  try {
    const browser = authCookie(request.get("cookie"), authConfig().sessionCookie);
    if (!browser) throw new AuthError("expired", 401);
    const query = await searchParams;
    if (query.reauth === "required") throw new AuthError("reauthentication_required", 401);
    const flow = query.flow;
    if (Array.isArray(flow)) throw new AuthError("invalid_request", 400);
    settings = await loadSecuritySettings(browser, flow);
    if (query.saved === "1") settings.saved = true;
  } catch (error) {
    const code = error instanceof AuthError ? error.code : "unavailable";
    if (code === "reauthentication_required") return (
      <section className="auth-page">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Confirm it&apos;s you</h1>
        <p className="text-muted">Security changes need a recent sign-in using your strongest available method. No settings have been changed.</p>
        {/* Auth endpoints require full navigation, not RSC fetches. */}
        {/* eslint-disable-next-line @next/next/no-html-link-for-pages */}
        <a className="store-cta"
          href="/auth/security-reauth">Confirm sign-in</a>
        <Link className="store-link" href="/account">Back to account</Link>
      </section>
    );
    console.error("authentication security page reason", code);
    return <AuthProblem code={code} returnTo="/account/security" />;
  }
  return (
    <section className="auth-page">
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Account security</h1>
      <p className="text-muted">Two-step sign-in is optional for customers. Changes require a recent, fully authenticated session.</p>
      {(await searchParams).google === "cancelled" ? <p role="status" className="text-muted">Google connection was cancelled. No credentials were changed.</p> : null}
      {(await searchParams).google === "failed" ? <p role="alert" className="text-danger">Google could not be connected. Try again. A Google identity already connected to another account cannot be transferred.</p> : null}
      <SecuritySettings key={settings.id} settings={settings} />
      {/* eslint-disable-next-line @next/next/no-html-link-for-pages */}
      <a className="store-link" href="/auth/security">Start a fresh security form</a>
      <Link className="store-link" href="/account">Back to account</Link>
    </section>
  );
}
