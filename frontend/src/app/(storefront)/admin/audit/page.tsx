import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { adminOnly } from "@/lib/admin/policy";

export const metadata: Metadata = {
  title: "Admin · Audit log",
  description: "Review the append-only log of privileged staff actions: who changed products, categories, stock, delivery options, imports or roles, and each outcome seen.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

const size = 50;
type Entry = {
  id: string; occurredAt: string; actorSubject: string; actorUsername: string | null; actorRoles: string | null;
  operation: string; targetType: string; targetId: string | null; outcome: string; details: unknown;
};

function one(value: string | string[] | undefined, max = 100): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

export default async function AdminAudit({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const access = await adminAccess(adminOnly);
  if (access.kind !== "ok") return <AdminDenied access={access} next="/admin/audit" area="The audit log" />;
  const filter = { operation: one(params.operation), targetType: one(params.targetType, 60), actor: one(params.actor) };
  const page = /^[0-9]{1,4}$/.test(one(params.page)) ? Number(one(params.page)) : 0;
  let data: { items: Entry[]; totalElements: number } = { items: [], totalElements: 0 };
  let failed = false;
  try {
    data = (await adminGraphql<{ adminAuditLog: typeof data }>(access.session.accessToken,
      `query($f: AuditLogFilter, $p: Int, $s: Int) { adminAuditLog(filter: $f, page: $p, size: $s) {
        items { id occurredAt actorSubject actorUsername actorRoles operation targetType targetId outcome details } totalElements } }`,
      { f: { operation: filter.operation || null, targetType: filter.targetType || null, actor: filter.actor || null }, p: page, s: size })).adminAuditLog;
  } catch {
    failed = true;
  }
  const pages = Math.max(1, Math.ceil(data.totalElements / size));
  const href = (target: number) => `/admin/audit?${new URLSearchParams({ ...filter, page: String(target) })}`;
  return (
    <div className="account-main">
      <h1 className="text-2xl font-semibold tracking-tight">Audit log</h1>
      <form method="get" action="/admin/audit" className="admin-inline" role="search">
        <label className="auth-field"><span className="text-sm">Operation (exact, e.g. updateProduct)</span>
          <input className="auth-input" name="operation" defaultValue={filter.operation} maxLength={100} /></label>
        <label className="auth-field"><span className="text-sm">Target type (exact, e.g. PRODUCT)</span>
          <input className="auth-input" name="targetType" defaultValue={filter.targetType} maxLength={60} /></label>
        <label className="auth-field"><span className="text-sm">Actor username or subject</span>
          <input className="auth-input" name="actor" defaultValue={filter.actor} maxLength={100} /></label>
        <button className="store-cta" type="submit">Filter</button>
      </form>
      {failed ? <p role="alert" className="text-muted">The audit log could not be loaded. Retry shortly.</p> : null}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <caption className="text-sm text-muted">{data.totalElements} entr{data.totalElements === 1 ? "y" : "ies"}, newest first · page {page + 1} of {pages}</caption>
          <thead><tr><th scope="col">When (UTC)</th><th scope="col">Actor</th><th scope="col">Operation</th><th scope="col">Target</th><th scope="col">Outcome</th><th scope="col">Details</th></tr></thead>
          <tbody>
            {data.items.map((entry) => (
              <tr key={entry.id}>
                <td><time dateTime={entry.occurredAt}>{entry.occurredAt.replace("T", " ").slice(0, 19)}</time></td>
                <td>{entry.actorUsername ?? entry.actorSubject}{entry.actorRoles ? <span className="text-muted"> · {entry.actorRoles.replaceAll("ROLE_", "")}</span> : null}</td>
                <td>{entry.operation}</td>
                <td>{entry.targetType}{entry.targetId ? ` #${entry.targetId}` : ""}</td>
                <td><span className="account-badge" data-tone={entry.outcome === "SUCCESS" ? "ok" : "off"}>{entry.outcome}</span></td>
                <td><code className="text-xs">{JSON.stringify(entry.details)}</code></td>
              </tr>
            ))}
            {!failed && data.items.length === 0 ? <tr><td colSpan={6} className="text-muted">No matching entries.</td></tr> : null}
          </tbody>
        </table>
      </div>
      <nav aria-label="Audit pages" className="admin-inline">
        {page > 0 ? <Link prefetch={false} className="store-link" href={href(page - 1)}>Newer entries</Link> : null}
        {page + 1 < pages ? <Link prefetch={false} className="store-link" href={href(page + 1)}>Older entries</Link> : null}
      </nav>
    </div>
  );
}
