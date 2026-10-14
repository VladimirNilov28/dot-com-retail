import Link from "next/link";
import { ArrowLeft, UserRound } from "lucide-react";
import { AccountNav } from "@/components/account/account-nav";
import { staffRoles } from "@/lib/admin/policy";
import { accountState } from "@/lib/auth/session";

export default async function AccountLayout({ children }: { children: React.ReactNode }) {
  const account = await accountState();
  const user = account.kind === "authenticated" ? account.user : null;
  return (
    <div className="admin-shell account-console">
      <aside className="admin-sidebar" aria-label="Your account">
        <div className="admin-sidebar-head">
          <span className="admin-sidebar-mark" aria-hidden="true">
            {user ? user.username.slice(0, 1).toUpperCase() : <UserRound size={18} />}
          </span>
          <div className="min-w-0">
            <p className="admin-sidebar-title">Your account</p>
            <p className="admin-sidebar-user" title={user?.username}>{user ? user.username : "Not signed in"}</p>
          </div>
        </div>
        <AccountNav staff={!!user && staffRoles.has(user.role)} />
        <Link prefetch={false} href="/" className="admin-sidebar-back"><ArrowLeft size={16} aria-hidden="true" /> Back to store</Link>
      </aside>
      <div className="admin-content">{children}</div>
    </div>
  );
}
