import type { Metadata } from "next";
import Link from "next/link";
import { headers } from "next/headers";
import { AuthProblem } from "@/components/auth-page";
import { LogoutForm } from "@/components/logout-form";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, hasSessionCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Account", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function AccountPage() {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return (
    <section className="auth-page">
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Your account</h1>
      <p className="text-muted">Sign in to access your ByteCore account.</p>
      <Link prefetch={false} className="store-cta" href="/login?returnTo=%2Faccount">Sign in</Link>
      <p className="text-sm text-muted">New to ByteCore? <Link prefetch={false} className="store-link" href="/register?returnTo=%2Faccount">Create an account</Link></p>
    </section>
  );
  let result;
  try {
    const cookie = authCookie(request.get("cookie"), authConfig().sessionCookie);
    if (!cookie) throw new AuthError("expired", 401);
    result = await authenticatedState(cookie);
  } catch (error) {
    console.error("account request rejected", error instanceof AuthError ? error.code : "upstream");
    return <AuthProblem code={error instanceof AuthError ? error.code : "unavailable"} returnTo="/account" />;
  }
  return (
    <section className="auth-page">
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Your account</h1>
      <div className="auth-panel space-y-4">
        <p>You&apos;re signed in as <strong className="font-semibold">{result.user.username}</strong>.</p>
        <p className="text-sm text-muted">{result.user.email}</p>
        <LogoutForm csrf={result.state.csrf} />
      </div>
      <Link className="store-link" href="/catalog/all-products">Continue browsing</Link>
    </section>
  );
}
