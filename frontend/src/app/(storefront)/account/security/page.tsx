import type { Metadata } from "next";
import { AccountHeading, accountContext, type AccountQuery } from "@/components/account/account-gate";
import { Notice, QueryNotices } from "@/components/account/notice";
import { AuthenticatorPanel, PasswordPanel, RecoveryCodesPanel } from "@/components/account/security-panels";
import { StepUpCard } from "@/components/account/step-up";

export const metadata: Metadata = { title: "Security", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function SecurityPage({ searchParams }: { searchParams: Promise<AccountQuery> }) {
  const title = "Security";
  const context = await accountContext("/account/security", title, searchParams, { settings: true });
  if ("element" in context) return context.element;
  const { view, settings, query } = context;
  const editable = view.stepUp.ready;
  const notice = typeof query.notice === "string" ? query.notice : undefined;
  const { credentials } = view.status;
  return (
    <div className="account-main">
      <AccountHeading title={title} description="Manage how you sign in and protect your account." />
      <QueryNotices query={query} keys={["recovery", "saved", "stepup", "invalid"]} />
      {notice && !["password_mismatch", "minimum", "maximum"].includes(notice) ? <QueryNotices query={query} keys={["notice"]} /> : null}
      {view.stepUp.recoveryMode && query.recovery !== "1" ? (
        <Notice tone="info">You signed in with a recovery code. Remove the lost authenticator and set up a new one. Other changes are available after your next normal sign-in.</Notice>
      ) : null}
      <StepUpCard stepUp={view.stepUp} csrf={view.csrf} section="/account/security" />
      <PasswordPanel settings={settings} hasPassword={credentials.password} editable={editable && !view.stepUp.recoveryMode} notice={notice} />
      <AuthenticatorPanel settings={settings} enabled={credentials.totp} editable={editable} />
      <RecoveryCodesPanel settings={settings} enabled={credentials.lookupSecret} remaining={view.status.recoveryCodesRemaining}
        totpEnabled={credentials.totp} editable={editable} />
    </div>
  );
}
