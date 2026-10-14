import type { AdminAccess } from "@/lib/admin/gate";

export function AdminDenied({ access, next = "/admin/products", area = "product management" }: {
  access: Exclude<AdminAccess, { kind: "ok" }>; next?: string; area?: string;
}) {
  const mfa = access.kind === "forbidden" && access.reason === "assurance";
  const text = access.kind === "anonymous" ? `Sign in with a staff account to use ${area}.` :
    access.kind === "unavailable" ? "The admin service is temporarily unavailable. Try again shortly." :
      mfa ? "Staff access requires a second authentication factor on this session. Set up or verify your authenticator to continue." :
        `Your account does not have access to ${area}.`;
  return (
    <section className="account-main" aria-labelledby="admin-denied">
      <h1 id="admin-denied" className="text-2xl font-semibold tracking-tight">{mfa ? "Verify it's you" : area.charAt(0).toUpperCase() + area.slice(1)}</h1>
      <p role="alert" className="text-muted">{text}</p>
      {/* Auth endpoints need full navigation, not RSC fetches. */}
      {access.kind === "anonymous" ? <a className="store-cta justify-self-start" href={`/login?returnTo=${encodeURIComponent(next)}`}>Sign in</a> : null}
      {mfa ? <a className="store-cta justify-self-start" href={`/admin/security?next=${encodeURIComponent(next)}`}>Continue to authenticator</a> : null}
      {access.kind === "unavailable" ? <a className="store-link" href={next}>Try again</a> : null}
    </section>
  );
}
