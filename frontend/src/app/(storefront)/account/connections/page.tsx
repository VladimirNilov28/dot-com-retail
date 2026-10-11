import type { Metadata } from "next";
import { AccountHeading, accountContext, type AccountQuery } from "@/components/account/account-gate";
import { ActionForm } from "@/components/account/action-form";
import { QueryNotices } from "@/components/account/notice";
import { StepUpCard } from "@/components/account/step-up";

export const metadata: Metadata = { title: "Connected accounts", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function ConnectionsPage({ searchParams }: { searchParams: Promise<AccountQuery> }) {
  const title = "Connected accounts";
  const section = "/account/connections";
  const context = await accountContext(section, title, searchParams, { settings: true });
  if ("element" in context) return context.element;
  const { view, settings, query } = context;
  const connected = view.status.credentials.google;
  const googleEmail = view.status.profile.googleEmail;
  const showUseGoogleEmail = connected && !!googleEmail && googleEmail.toLowerCase() !== view.status.profile.email.toLowerCase();
  const editable = view.stepUp.ready && !view.stepUp.recoveryMode ? settings : undefined;
  return (
    <div className="account-main">
      <AccountHeading title={title} description="Sign in with another provider without creating a separate account." />
      <QueryNotices query={query} keys={["saved", "notice", "google", "stepup", "invalid"]} />
      <StepUpCard stepUp={view.stepUp} csrf={view.csrf} section={section} />
      <section className="account-card" aria-labelledby="google-heading">
        <div className="account-card-header">
          <div>
            <h2 id="google-heading" className="text-lg font-semibold">Google</h2>
            <p className="text-sm text-muted">{connected ? "Google signs you in to this same ByteCore account." :
              "Connect Google to sign in with it. Your account and order history stay the same."}</p>
          </div>
          <span className="account-badge" data-tone={connected ? "ok" : "off"}>{connected ? "Connected" : "Not connected"}</span>
        </div>
        {showUseGoogleEmail ? (
          <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <p className="text-muted">Google email: <strong className="break-all">{googleEmail}</strong></p>
            <ActionForm endpoint="/auth/account" csrf={view.csrf} label="Use Google email" variant="secondary"
              pendingLabel="Sending code…" fields={{ action: "email_use_google", section: "/account/profile" }} />
          </div>
        ) : null}
        {editable?.actions.includes("google_connect") ? (
          <ActionForm endpoint="/auth/security" csrf={editable.csrf} label="Connect Google" pendingLabel="Opening Google…"
            fields={{ flow: editable.id, action: "google_connect", section }} />
        ) : null}
        {connected && !view.status.credentials.password ? (
          <p className="text-sm text-muted">Google is your only way to sign in. <a className="store-link" href="/account/security">Create a password</a> before disconnecting it.</p>
        ) : editable?.actions.includes("google_disconnect") ? (
          <ActionForm endpoint="/auth/security" csrf={editable.csrf} label="Disconnect Google" variant="secondary"
            fields={{ flow: editable.id, action: "google_disconnect", section, acknowledge: "yes" }}
            confirm={{ title: "Disconnect Google?", action: "Disconnect",
              body: "You'll sign in with your email or username and password instead. Your account data is unchanged." }} />
        ) : null}
        {editable && !connected && !editable.actions.includes("google_connect") ? (
          <p className="text-sm text-muted">Google sign-in isn&apos;t available in this environment.</p>
        ) : null}
      </section>
    </div>
  );
}
