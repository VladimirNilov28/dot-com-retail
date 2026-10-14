import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, KeyRound, MapPin, Package, ShieldCheck, UserRound } from "lucide-react";
import { headers } from "next/headers";
import { AccountHeading } from "@/components/account/account-gate";
import { AuthProblem } from "@/components/auth-page";
import { LogoutForm } from "@/components/logout-form";
import { accountStatus, type AccountStatus } from "@/lib/auth/account";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, hasSessionCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";
import { staffRoles } from "@/lib/admin/policy";

export const metadata: Metadata = { title: "Account", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

function Item({ href, label, value, ok, optional = false }: { href: string; label: string; value: string; ok: boolean; optional?: boolean }) {
  return (
    <li className="account-row">
      <Link prefetch={false} href={href} className="store-link">{label}</Link>
      <span className="account-badge" data-tone={ok ? "ok" : optional ? "plain" : "warn"}>{value}</span>
    </li>
  );
}

const shortcuts = [
  { href: "/account/orders", label: "Orders", text: "Track, cancel or request a refund", icon: Package },
  { href: "/account/addresses", label: "Addresses", text: "Saved delivery addresses", icon: MapPin },
  { href: "/account/profile", label: "Personal info", text: "Name, email and phone", icon: UserRound },
  { href: "/account/security", label: "Security", text: "Password and two-factor", icon: KeyRound },
];

export default async function AccountPage() {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return (
    <div className="account-main">
      <AccountHeading title="Your account" description="Sign in to access your ByteCore account." />
      <div className="flex flex-wrap items-center gap-4">
        { }
        <a className="store-cta" data-variant="primary" href="/login?returnTo=%2Faccount">Sign in</a>
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
  const staff = staffRoles.has(result.user.role);
  return (
    <div className="account-main">
      <AccountHeading title="Your account" description="Manage your orders, details and sign-in security." />
      <section className="account-card account-summary" aria-label="Account summary">
        <div className="flex min-w-0 items-center gap-3">
          <span className="admin-avatar account-avatar" aria-hidden="true">{result.user.username.slice(0, 1).toUpperCase()}</span>
          <div className="min-w-0">
            <p className="font-semibold">{result.user.username}</p>
            <p className="text-sm text-muted break-all">{result.user.email}</p>
          </div>
        </div>
        <LogoutForm csrf={result.state.csrf} />
      </section>
      {staff ? (
        <section className="account-card account-staff" aria-labelledby="staff-heading">
          <div className="flex min-w-0 items-start gap-3">
            <ShieldCheck className="account-staff-icon" size={22} aria-hidden="true" />
            <div className="min-w-0">
              <h2 id="staff-heading" className="text-lg font-semibold">Admin console</h2>
              <p className="text-sm text-muted">You have staff access. Admin pages require your authenticator app.</p>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Link prefetch={false} className="store-cta" data-variant="primary" href="/admin">Open admin console <ArrowRight size={16} aria-hidden="true" /></Link>
            <Link prefetch={false} className="store-cta" href="/admin/security">Authenticator</Link>
          </div>
        </section>
      ) : (
        <nav aria-label="Account shortcuts" className="account-shortcuts">
          {shortcuts.map(({ href, label, text, icon: Icon }) => (
            <Link key={href} prefetch={false} href={href} className="account-shortcut">
              <Icon size={20} aria-hidden="true" />
              <span className="min-w-0"><span className="block font-semibold">{label}</span><span className="block text-sm text-muted">{text}</span></span>
            </Link>
          ))}
        </nav>
      )}
      {status ? (
        <section className="account-card" aria-labelledby="status-heading">
          <h2 id="status-heading" className="text-lg font-semibold">Security checkup</h2>
          <ul className="account-rows">
            <Item href="/account/profile" label="Email address" value={status.profile.emailVerified ? "Verified" : "Unverified"} ok={status.profile.emailVerified} />
            <Item href="/account/security" label="Password" value={status.credentials.password ? "Set" : "Not set"} ok={status.credentials.password} />
            <Item href="/account/security" label="Authenticator app" value={status.credentials.totp ? "On" : "Off"} ok={status.credentials.totp} />
            <Item href="/account/security" label="Recovery codes" value={status.credentials.lookupSecret ? `${status.recoveryCodesRemaining} left` : "Off"}
              ok={status.credentials.lookupSecret && status.recoveryCodesRemaining > 2} />
            <Item href="/account/connections" label="Google" value={status.credentials.google ? "Connected" : "Not connected"} ok={status.credentials.google} optional />
          </ul>
        </section>
      ) : null}
      <Link className="store-link justify-self-start" href="/catalog/all-products">Continue browsing</Link>
    </div>
  );
}
