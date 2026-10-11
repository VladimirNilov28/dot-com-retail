import { AccountNav } from "@/components/account/account-nav";

export default function AccountLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="account-shell">
      <AccountNav />
      {children}
    </div>
  );
}
