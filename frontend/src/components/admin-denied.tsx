import type { AdminAccess } from "@/lib/admin/gate";

export function AdminDenied({ access }: { access: Exclude<AdminAccess, { kind: "ok" }> }) {
  const text = access.kind === "anonymous" ? "Sign in with a staff account to manage products." :
    access.kind === "unavailable" ? "The admin service is temporarily unavailable. Try again shortly." :
      access.reason === "assurance" ? "Staff access requires a second authentication factor on this session." :
        "Your account does not have access to product management.";
  return (
    <section className="account-main" aria-labelledby="admin-denied">
      <h1 id="admin-denied" className="text-2xl font-semibold tracking-tight">Product management</h1>
      <p role="alert" className="text-muted">{text}</p>
      {access.kind === "anonymous" ? <a className="store-cta" href="/login?returnTo=%2Fadmin%2Fproducts">Sign in</a> : null}
    </section>
  );
}
