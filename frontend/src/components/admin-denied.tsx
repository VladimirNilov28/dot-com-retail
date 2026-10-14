import { KeyRound, LockKeyhole, ServerCrash, ShieldOff } from "lucide-react";
import type { AdminAccess } from "@/lib/admin/gate";

export function AdminDenied({ access, next = "/admin/products", area = "Product management" }: {
  access: Exclude<AdminAccess, { kind: "ok" }>; next?: string; area?: string;
}) {
  const mfa = access.kind === "forbidden" && access.reason === "assurance";
  const text = access.kind === "anonymous" ? `Sign in with a staff account to open ${area.toLowerCase()}.` :
    access.kind === "unavailable" ? "The admin service is temporarily unavailable. Try again shortly." :
      mfa ? "Staff access requires a second authentication factor on this session. Set up or verify your authenticator to continue." :
        `Your account does not have access to ${area.toLowerCase()}.`;
  const Icon = access.kind === "anonymous" ? LockKeyhole : access.kind === "unavailable" ? ServerCrash : mfa ? KeyRound : ShieldOff;
  return (
    <section className="admin-denied" aria-labelledby="admin-denied">
      <span className="admin-denied-icon" aria-hidden="true"><Icon size={26} /></span>
      <h1 id="admin-denied">{mfa ? "Verify it's you" : area}</h1>
      <p role="alert" className="text-muted">{text}</p>
      {/* Auth endpoints need full navigation, not RSC fetches. */}
      {access.kind === "anonymous" ? <a className="store-cta" data-variant="primary" href={`/login?returnTo=${encodeURIComponent(next)}`}>Sign in</a> : null}
      {mfa ? <a className="store-cta" data-variant="primary" href={`/admin/security?next=${encodeURIComponent(next)}`}>Continue to authenticator</a> : null}
      {access.kind === "unavailable" ? <a className="store-cta" href={next}>Try again</a> : null}
    </section>
  );
}
