import Link from "next/link";
import { ArrowLeft, ShieldCheck } from "lucide-react";
import { AdminNav } from "@/components/admin-nav";
import { adminAccess } from "@/lib/admin/gate";
import { staffRoles } from "@/lib/admin/policy";

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  const access = await adminAccess(staffRoles);
  const session = access.kind === "ok" ? access.session : null;
  return (
    <div className="admin-shell">
      <aside className="admin-sidebar" aria-label="Admin console">
        <div className="admin-sidebar-head">
          <span className="admin-sidebar-mark" aria-hidden="true"><ShieldCheck size={18} /></span>
          <div className="min-w-0">
            <p className="admin-sidebar-title">Admin console</p>
            {session ? (
              <p className="admin-sidebar-user" title={session.username}>
                {session.username} · <span>{roleLabel(session.role)}</span>
              </p>
            ) : <p className="admin-sidebar-user">Staff sign-in required</p>}
          </div>
        </div>
        <AdminNav role={session?.role ?? null} />
        <Link prefetch={false} href="/" className="admin-sidebar-back"><ArrowLeft size={16} aria-hidden="true" /> Back to store</Link>
      </aside>
      <div className="admin-content">{children}</div>
    </div>
  );
}

function roleLabel(role: string): string {
  return role.toLowerCase().split("_").map((part) => part[0].toUpperCase() + part.slice(1)).join(" ");
}
