import type { Metadata } from "next";
import { AccountHeading, accountContext, type AccountQuery } from "@/components/account/account-gate";
import { ActionForm } from "@/components/account/action-form";
import { QueryNotices } from "@/components/account/notice";
import { StepUpCard } from "@/components/account/step-up";

export const metadata: Metadata = { title: "Personal info", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function ProfilePage({ searchParams }: { searchParams: Promise<AccountQuery> }) {
  const title = "Personal info";
  const section = "/account/profile";
  const context = await accountContext(section, title, searchParams);
  if ("element" in context) return context.element;
  const { view, query } = context;
  const { profile } = view.status;
  return (
    <div className="account-main">
      <AccountHeading title={title} />
      <QueryNotices query={query} keys={["email", "stepup"]} />
      <section className="account-card" aria-labelledby="details-heading">
        <h2 id="details-heading" className="text-lg font-semibold">Details</h2>
        <dl className="account-rows">
          <div className="account-row"><dt className="text-muted">Username</dt><dd>{profile.username}</dd></div>
          <div className="account-row"><dt className="text-muted">Date of birth</dt><dd>{profile.dateOfBirth ?? "Not provided"}</dd></div>
        </dl>
      </section>
      <section className="account-card" aria-labelledby="email-heading">
        <div className="account-card-header">
          <div>
            <h2 id="email-heading" className="text-lg font-semibold">Email address</h2>
            <p className="break-all">{profile.email}</p>
          </div>
          <span className="account-badge" data-tone={profile.emailVerified ? "ok" : "off"}>{profile.emailVerified ? "Verified" : "Unverified"}</span>
        </div>
        <p className="text-sm text-muted">Used for sign-in, receipts and security alerts. A connected Google account keeps its own address.</p>
        {view.emailChange ? (
          <div className="account-form">
            <p className="text-sm">Enter the code we sent to <strong className="break-all">{view.emailChange.email}</strong>. It expires in 15 minutes.</p>
            <ActionForm endpoint="/auth/account" csrf={view.csrf} fields={{ action: "email_verify", section }} label="Confirm new email" pendingLabel="Confirming…">
              <label className="auth-field">
                <span>Verification code</span>
                <input className="auth-input px-3" name="code" inputMode="numeric" autoComplete="one-time-code"
                  pattern="[0-9]{6}" minLength={6} maxLength={6} required />
              </label>
            </ActionForm>
            <ActionForm endpoint="/auth/account" csrf={view.csrf} fields={{ action: "email_cancel", section }} label="Cancel change" variant="secondary" />
          </div>
        ) : view.stepUp.ready && view.stepUp.recoveryMode ? (
          <p className="text-sm text-muted">You signed in with a recovery code. Change your email after your next normal sign-in.</p>
        ) : view.stepUp.ready ? (
          <ActionForm endpoint="/auth/account" csrf={view.csrf} fields={{ action: "email_request", section }} label="Send verification code" pendingLabel="Sending…">
            <label className="auth-field">
              <span>New email address</span>
              <input className="auth-input px-3" name="email" type="email" autoComplete="email" maxLength={254} required />
            </label>
            <p className="text-sm text-muted">Your address changes only after you confirm the code sent to the new address.</p>
          </ActionForm>
        ) : null}
      </section>
      {!view.emailChange ? <StepUpCard stepUp={view.stepUp} csrf={view.csrf} section={section} /> : null}
    </div>
  );
}
