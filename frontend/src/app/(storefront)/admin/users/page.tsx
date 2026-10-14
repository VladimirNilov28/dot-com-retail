import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { Search, UserSearch } from "lucide-react";
import { AdminEmpty, AdminPageHeader } from "@/components/admin-page-header";
import { AdminLoadError, AdminStatus } from "@/components/admin-status";
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
      <AdminPageHeader title="Users and roles"
        description="A new role takes effect in the user's access token after its next refresh (within minutes) or their next sign-in; the API always re-checks the current token." />
      <AdminStatus params={params} />
      <form method="get" action="/admin/users" className="admin-toolbar" role="search">
        <label className="auth-field admin-search"><span className="text-sm">Username or email contains</span>
          <input className="auth-input" type="search" name="q" defaultValue={query} maxLength={100} /></label>
        <label className="auth-field"><span className="text-sm">Role</span>
          <select className="auth-input" name="role" defaultValue={role}><option value="">Any role</option>
            {roles.map((value) => <option key={value} value={value}>{label(value)}</option>)}</select></label>
        <button className="store-cta" type="submit"><Search size={16} aria-hidden="true" />Search</button>
      </form>
      {failed ? <AdminLoadError>Users could not be loaded. Retry shortly.</AdminLoadError> : null}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <caption className="text-sm text-muted">{data.totalElements} matching user{data.totalElements === 1 ? "" : "s"}</caption>
          <thead><tr><th scope="col">User</th><th scope="col">Current role</th><th scope="col">Change role</th></tr></thead>
          <tbody>
            {data.items.map((user) => (
              <tr key={user.id}>
                <th scope="row">
                  <div className="admin-cell-main">
                    <span className="admin-avatar" aria-hidden="true">{user.username.slice(0, 1)}</span>
                    <div>
                      <span className="font-medium">{user.username}{user.username === access.session.username ? <span className="text-muted font-normal"> (you)</span> : null}</span>
                      <span className="admin-cell-sub">{user.email}</span>
                    </div>
                  </div>
                </th>
                <td><span className="account-badge" data-tone={user.role === "USER" ? "plain" : "ok"}>{label(user.role)}</span></td>
                <td>
                  <form action={changeUserRole} className="admin-inline">
                    <input type="hidden" name="userId" value={user.id} />
                    <input type="hidden" name="back" value={back} />
                    <label className="auth-field"><span className="sr-only">Role for {user.username}</span>
                      <select className="auth-input" name="role" defaultValue={user.role}>
                        {roles.map((value) => <option key={value} value={value}>{label(value)}</option>)}</select></label>
                    <button className="store-cta" data-size="sm" type="submit">Change role<span className="sr-only"> for {user.username}</span></button>
                  </form>
                </td>
              </tr>
            ))}
            {!failed && data.items.length === 0 ? <tr><td colSpan={3}><AdminEmpty icon={<UserSearch size={22} />} title="No matching users" /></td></tr> : null}
          </tbody>
        </table>
      </div>
      <nav aria-label="User pages" className="admin-pagination">
        <span>Page {page + 1} of {pages}</span>
        <div>
          {page > 0 ? <Link prefetch={false} className="store-cta" data-size="sm" href={href(page - 1)}>Previous page</Link> : null}
          {page + 1 < pages ? <Link prefetch={false} className="store-cta" data-size="sm" href={href(page + 1)}>Next page</Link> : null}
        </div>
      </nav>
    </div>
  );
}

function label(role: string): string {
  return role.toLowerCase().split("_").map((part) => part[0].toUpperCase() + part.slice(1)).join(" ");
}
