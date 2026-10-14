import type { Metadata } from "next";
import Link from "next/link";
import { Filter, ScrollText } from "lucide-react";
import { AdminDenied } from "@/components/admin-denied";
import { AdminEmpty, AdminPageHeader } from "@/components/admin-page-header";
import { AdminLoadError } from "@/components/admin-status";
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
      <AdminPageHeader title="Audit log" description="Append-only record of privileged staff actions, newest first. Times are UTC." />
      <form method="get" action="/admin/audit" className="admin-toolbar" role="search">
        <label className="auth-field"><span className="text-sm">Operation</span>
          <input className="auth-input" name="operation" defaultValue={filter.operation} maxLength={100} placeholder="updateProduct" /></label>
        <label className="auth-field"><span className="text-sm">Target type</span>
          <input className="auth-input" name="targetType" defaultValue={filter.targetType} maxLength={60} placeholder="PRODUCT" /></label>
        <label className="auth-field"><span className="text-sm">Actor username or subject</span>
          <input className="auth-input" name="actor" defaultValue={filter.actor} maxLength={100} /></label>
        <button className="store-cta" type="submit"><Filter size={16} aria-hidden="true" />Filter</button>
        {filter.operation || filter.targetType || filter.actor ? <Link prefetch={false} className="store-cta" data-variant="ghost" href="/admin/audit">Clear</Link> : null}
      </form>
      <p className="admin-hint">Operation and target type match exactly.</p>
      {failed ? <AdminLoadError>The audit log could not be loaded. Retry shortly.</AdminLoadError> : null}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <caption className="text-sm text-muted">{data.totalElements} entr{data.totalElements === 1 ? "y" : "ies"}</caption>
          <thead><tr><th scope="col">When (UTC)</th><th scope="col">Actor</th><th scope="col">Operation</th><th scope="col">Target</th><th scope="col">Outcome</th><th scope="col">Details</th></tr></thead>
          <tbody>
            {data.items.map((entry) => (
              <tr key={entry.id}>
                <td className="whitespace-nowrap"><time dateTime={entry.occurredAt}>{entry.occurredAt.replace("T", " ").slice(0, 19)}</time></td>
                <td><span className="font-medium">{entry.actorUsername ?? entry.actorSubject}</span>
                  {entry.actorRoles ? <span className="admin-cell-sub block">{entry.actorRoles.replaceAll("ROLE_", "")}</span> : null}</td>
                <td><code className="admin-code" style={{ color: "var(--foreground)" }}>{entry.operation}</code></td>
                <td>{entry.targetType}{entry.targetId ? <span className="text-muted"> #{entry.targetId}</span> : null}</td>
                <td><span className="account-badge" data-tone={entry.outcome === "SUCCESS" ? "ok" : "danger"}>{entry.outcome}</span></td>
                <td>
                  {entry.details && Object.keys(entry.details as object).length ? (
                    <details>
                      <summary className="text-sm store-link cursor-pointer">View</summary>
                      <pre className="admin-code" style={{ whiteSpace: "pre-wrap", maxWidth: "28rem" }}>{JSON.stringify(entry.details, null, 2)}</pre>
                    </details>
                  ) : <span className="text-muted">—</span>}
                </td>
              </tr>
            ))}
            {!failed && data.items.length === 0 ? <tr><td colSpan={6}><AdminEmpty icon={<ScrollText size={22} />} title="No matching entries" /></td></tr> : null}
          </tbody>
        </table>
      </div>
      <nav aria-label="Audit pages" className="admin-pagination">
        <span>Page {page + 1} of {pages}</span>
        <div>
          {page > 0 ? <Link prefetch={false} className="store-cta" data-size="sm" href={href(page - 1)}>Newer entries</Link> : null}
          {page + 1 < pages ? <Link prefetch={false} className="store-cta" data-size="sm" href={href(page + 1)}>Older entries</Link> : null}
        </div>
      </nav>
    </div>
  );
}
