import { AdminNav } from "@/components/admin-nav";

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="account-shell">
      <AdminNav />
      {children}
    </div>
  );
}
