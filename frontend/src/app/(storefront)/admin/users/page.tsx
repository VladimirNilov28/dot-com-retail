import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { AdminStatus } from "@/components/admin-status";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { adminOnly } from "@/lib/admin/policy";
import { changeUserRole } from "@/lib/admin/manage-actions";

export const metadata: Metadata = {
  title: "Admin · Users and roles",
  description: "Search active user accounts by username or email, filter by role, and assign staff roles. You cannot remove your own admin role or the last administrator.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

const roles = ["USER", "SUPPORT", "CATALOG_MANAGER", "ORDER_MANAGER", "WAREHOUSE", "ADMIN"] as const;
const size = 25;
type User = { id: string; username: string; email: string; role: string; createdAt: string };

function one(value: string | string[] | undefined): string {
  return typeof value === "string" ? value : "";
}

export default async function AdminUsers({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const access = await adminAccess(adminOnly);
  if (access.kind !== "ok") return <AdminDenied access={access} next="/admin/users" area="User management" />;
  const query = one(params.q).trim().slice(0, 100);
  const role = (roles as readonly string[]).includes(one(params.role)) ? one(params.role) : "";
  const page = /^[0-9]{1,4}$/.test(one(params.page)) ? Number(one(params.page)) : 0;
  let data: { items: User[]; totalElements: number } = { items: [], totalElements: 0 };
  let failed = false;
  try {
    data = (await adminGraphql<{ adminUsers: typeof data }>(access.session.accessToken,
      `query($q: String, $r: Role, $p: Int, $s: Int) { adminUsers(query: $q, role: $r, page: $p, size: $s) { items { id username email role createdAt } totalElements } }`,
      { q: query || null, r: role || null, p: page, s: size })).adminUsers;
  } catch {
    failed = true;
  }
  const pages = Math.max(1, Math.ceil(data.totalElements / size));
  const href = (target: number) => `/admin/users?${new URLSearchParams({ q: query, role, page: String(target) })}`;
  const back = href(page);
  return (
    <div className="account-main">
      <h1 className="text-2xl font-semibold tracking-tight">Users and roles</h1>
      <AdminStatus params={params} />
      <p className="text-sm text-muted">A new role takes effect in the user&apos;s access token after its next refresh (within minutes) or their next sign-in; the API always re-checks the current token.</p>
      <form method="get" action="/admin/users" className="admin-inline" role="search">
        <label className="auth-field"><span className="text-sm">Username or email contains</span>
          <input className="auth-input" name="q" defaultValue={query} maxLength={100} /></label>
        <label className="auth-field"><span className="text-sm">Role</span>
          <select className="auth-input" name="role" defaultValue={role}><option value="">Any role</option>
            {roles.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
        <button className="store-cta" type="submit">Search</button>
      </form>
      {failed ? <p role="alert" className="text-muted">Users could not be loaded. Retry shortly.</p> : null}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <caption className="text-sm text-muted">{data.totalElements} matching user{data.totalElements === 1 ? "" : "s"} · page {page + 1} of {pages}</caption>
          <thead><tr><th scope="col">User</th><th scope="col">Email</th><th scope="col">Role</th></tr></thead>
          <tbody>
            {data.items.map((user) => (
              <tr key={user.id}>
                <th scope="row">{user.username}{user.username === access.session.username ? " (you)" : ""}</th>
                <td>{user.email}</td>
                <td>
                  <form action={changeUserRole} className="admin-inline">
                    <input type="hidden" name="userId" value={user.id} />
                    <input type="hidden" name="back" value={back} />
                    <label className="auth-field"><span className="text-sm">Role for {user.username}</span>
                      <select className="auth-input" name="role" defaultValue={user.role}>
                        {roles.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
                    <button className="store-cta" type="submit">Change role</button>
                  </form>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <nav aria-label="User pages" className="admin-inline">
        {page > 0 ? <Link prefetch={false} className="store-link" href={href(page - 1)}>Previous page</Link> : null}
        {page + 1 < pages ? <Link prefetch={false} className="store-link" href={href(page + 1)}>Next page</Link> : null}
      </nav>
    </div>
  );
}
