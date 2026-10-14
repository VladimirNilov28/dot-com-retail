import type { Metadata } from "next";
import { headers } from "next/headers";
import { AdminPageHeader } from "@/components/admin-page-header";
import { Notice } from "@/components/account/notice";
import { AuthenticatorPanel } from "@/components/account/security-panels";
import { AuthProblem } from "@/components/auth-page";
import { adminAccess } from "@/lib/admin/gate";
import { staffRoles } from "@/lib/admin/policy";
import { authConfig } from "@/lib/auth/config";
import { AuthError, adminNext, authCookie, hasSessionCookie } from "@/lib/auth/model";
import { loadStaffSecurity, type StaffSecurity } from "@/lib/auth/settings";

export const metadata: Metadata = { title: "Admin · Authenticator", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

type Query = Record<string, string | string[] | undefined>;
const single = (value: string | string[] | undefined) => (typeof value === "string" ? value : undefined);

export default async function AdminSecurity({ searchParams }: { searchParams: Promise<Query> }) {
  const query = await searchParams;
  const next = adminNext(single(query.next));
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) {
    return (
      <div className="account-main">
        <AdminPageHeader title="Authenticator" description="Sign in with a staff account first." />
        <a className="store-cta justify-self-start" data-variant="primary" href={`/login?returnTo=${encodeURIComponent("/admin/security")}`}>Sign in</a>
      </div>
    );
  }
  let security: StaffSecurity;
  try {
    const browser = authCookie(request.get("cookie"), authConfig().sessionCookie);
    if (!browser) throw new AuthError("expired", 401);
    const flow = single(query.flow);
    security = await loadStaffSecurity(browser, flow);
  } catch (error) {
    const code = error instanceof AuthError ? error.code : "unavailable";
    if (code === "forbidden") {
      return (
        <div className="account-main">
          <AdminPageHeader title="Authenticator" description="This page is for staff accounts. Customers manage security in their account." />
          <a className="store-link" href="/account/security">Account security</a>
        </div>
      );
    }
    if (code === "reauthentication_required") {
      return <Reconfirm next={next} />;
    }
    console.error("admin security reason", code);
    return <AuthProblem code={code} returnTo="/admin/security" />;
  }
  if (!security.fresh || query.reauth === "required") return <Reconfirm next={next} />;
  const enrolled = security.totpEnabled;
  // Already verified on this session: offer the way forward instead of another sign-in loop.
  const verified = enrolled && (await adminAccess(staffRoles)).kind === "ok";
  return (
    <div className="account-main">
      <AdminPageHeader title="Authenticator"
        description="Administrative access needs an authenticator app on every staff sign-in." />
      {query.saved ? <Notice tone="success">Authenticator confirmed. Verify it once to open the admin area.</Notice> : null}
      {query.invalid === "1" ? <Notice tone="error">That code was not accepted. Check your app and try again.</Notice> : null}
      {verified ? (
        <section className="account-card" aria-labelledby="verified-h">
          <h2 id="verified-h" className="text-lg font-semibold">Authenticator verified</h2>
          <p className="text-sm text-muted">This session is verified with your authenticator app.</p>
          <a className="store-cta justify-self-start" data-variant="primary" href={next}>Continue to admin</a>
        </section>
      ) : enrolled ? (
        <section className="account-card" aria-labelledby="verify-h">
          <h2 id="verify-h" className="text-lg font-semibold">Verify authenticator</h2>
          <p className="text-sm text-muted">Enter the current code from your authenticator app to unlock the admin area.</p>
          <a className="store-cta justify-self-start" data-variant="primary" href={`/auth/start?returnTo=${encodeURIComponent(next)}`}>Verify authenticator</a>
        </section>
      ) : (
        <AuthenticatorPanel settings={security.settings} enabled={false} editable section="/admin/security" next={next} />
      )}
    </div>
  );
}

function Reconfirm({ next }: { next: string }) {
  return (
    <div className="account-main">
      <AdminPageHeader title="Confirm it's you"
        description="Setting up an authenticator needs a recent sign-in. Nothing has been changed." />
      {/* eslint-disable-next-line @next/next/no-html-link-for-pages -- auth endpoints need full navigation */}
      <a className="store-cta justify-self-start" data-variant="primary" href="/auth/security-reauth?section=%2Fadmin%2Fsecurity" data-next={next}>Confirm sign-in</a>
    </div>
  );
}
