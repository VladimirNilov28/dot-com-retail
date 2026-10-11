import type { Metadata } from "next";
import { AccountHeading, accountContext, type AccountQuery } from "@/components/account/account-gate";
import { ActionForm } from "@/components/account/action-form";
import { QueryNotices } from "@/components/account/notice";

export const metadata: Metadata = { title: "Sessions", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

const format = new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" });

export default async function SessionsPage({ searchParams }: { searchParams: Promise<AccountQuery> }) {
  const title = "Sessions";
  const section = "/account/sessions";
  const context = await accountContext(section, title, searchParams, { sessions: true });
  if ("element" in context) return context.element;
  const { view, query } = context;
  const sessions = view.sessions ?? [];
  const others = sessions.filter((session) => !session.current);
  return (
    <div className="account-main">
      <AccountHeading title={title} description="Where your account is signed in. Times are shown in UTC." />
      <QueryNotices query={query} keys={["sessions"]} />
      <section className="account-card" aria-labelledby="sessions-heading">
        <h2 id="sessions-heading" className="sr-only">Active sessions</h2>
        <ul className="account-rows">
          {sessions.map((session) => (
            <li key={session.id} className="account-row">
              <div className="min-w-0">
                <p className="font-medium">{session.current ? "This browser" : "Other session"}
                  {session.current ? <span className="account-badge ms-2" data-tone="ok">Current</span> : null}</p>
                <p className="text-sm text-muted">Signed in {format.format(new Date(session.authenticatedAt))}
                  {session.methods.length ? ` with ${session.methods.join(" + ")}` : ""}. Expires {format.format(new Date(session.expiresAt))}.</p>
              </div>
              {!session.current ? (
                <ActionForm endpoint="/auth/account" csrf={view.csrf} label="Sign out" variant="secondary" pendingLabel="Signing out…"
                  fields={{ action: "session_revoke", session: session.id, section }} />
              ) : null}
            </li>
          ))}
        </ul>
        {others.length ? (
          <ActionForm endpoint="/auth/account" csrf={view.csrf} label="Sign out all other sessions" variant="secondary"
            fields={{ action: "sessions_revoke_others", section }}
            confirm={{ title: "Sign out everywhere else?", action: "Sign out others", body: "Every other browser and device will need to sign in again." }} />
        ) : <p className="text-sm text-muted">No other active sessions.</p>}
      </section>
    </div>
  );
}
