import type { Metadata } from "next";
import Link from "next/link";
import { headers } from "next/headers";
import { AccountHeading } from "@/components/account/account-gate";
import { AuthProblem } from "@/components/auth-page";
import { LogoutForm } from "@/components/logout-form";
import { accountStatus, type AccountStatus } from "@/lib/auth/account";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, hasSessionCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Account", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

function Item({ href, label, value, ok }: { href: string; label: string; value: string; ok: boolean }) {
  return (
    <li className="account-row">
      <Link prefetch={false} href={href} className="store-link">{label}</Link>
      <span className="account-badge" data-tone={ok ? "ok" : "off"}>{value}</span>
    </li>
  );
}

export default async function AccountPage() {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return (
    <div className="account-main">
      <AccountHeading title="Your account" description="Sign in to access your ByteCore account." />
      <div className="flex flex-wrap items-center gap-4">
        { }
        <a className="store-cta" href="/login?returnTo=%2Faccount">Sign in</a>
        { }
        <a className="store-link" href="/register?returnTo=%2Faccount">Create an account</a>
      </div>
    </div>
  );
  let result;
  let status: AccountStatus | undefined;
  try {
    const cookie = authCookie(request.get("cookie"), authConfig().sessionCookie);
    if (!cookie) throw new AuthError("expired", 401);
    result = await authenticatedState(cookie);
    if (result.user.role === "USER") status = await accountStatus(result.state);
  } catch (error) {
    console.error("account request rejected", error instanceof AuthError ? error.code : "upstream");
    return <AuthProblem code={error instanceof AuthError ? error.code : "unavailable"} returnTo="/account" />;
  }
  return (
    <div className="account-main">
      <AccountHeading title="Your account" />
      <section className="account-card account-summary" aria-label="Account summary">
        <div className="min-w-0">
          <p className="font-semibold">{result.user.username}</p>
          <p className="text-sm text-muted break-all">{result.user.email}</p>
        </div>
        <LogoutForm csrf={result.state.csrf} />
      </section>
      {status ? (
        <section className="account-card" aria-labelledby="status-heading">
          <h2 id="status-heading" className="text-lg font-semibold">Security checkup</h2>
          <ul className="account-rows">
            <Item href="/account/profile" label="Email address" value={status.profile.emailVerified ? "Verified" : "Unverified"} ok={status.profile.emailVerified} />
            <Item href="/account/security" label="Password" value={status.credentials.password ? "Set" : "Not set"} ok={status.credentials.password} />
            <Item href="/account/security" label="Authenticator app" value={status.credentials.totp ? "On" : "Off"} ok={status.credentials.totp} />
            <Item href="/account/security" label="Recovery codes" value={status.credentials.lookupSecret ? `${status.recoveryCodesRemaining} left` : "Off"}
              ok={status.credentials.lookupSecret && status.recoveryCodesRemaining > 2} />
            <Item href="/account/connections" label="Google" value={status.credentials.google ? "Connected" : "Not connected"} ok={status.credentials.google} />
          </ul>
        </section>
      ) : <p className="text-sm text-muted">Staff authenticator setup is in the <Link className="store-link" href="/admin/security">admin area</Link>.</p>}
      <Link className="store-link" href="/catalog/all-products">Continue browsing</Link>
    </div>
  );
}
