"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  KeyRound, LayoutDashboard, Link2, MapPin, MonitorSmartphone, Package, ShieldCheck, UserRound, type LucideIcon,
} from "lucide-react";

type Item = { href: string; label: string; icon: LucideIcon };

const groups: { label: string; items: Item[] }[] = [
  { label: "Shopping", items: [
    { href: "/account", label: "Overview", icon: LayoutDashboard },
    { href: "/account/orders", label: "Orders", icon: Package },
    { href: "/account/addresses", label: "Addresses", icon: MapPin },
  ] },
  { label: "Profile and security", items: [
    { href: "/account/profile", label: "Personal info", icon: UserRound },
    { href: "/account/security", label: "Security", icon: KeyRound },
    { href: "/account/connections", label: "Connected accounts", icon: Link2 },
    { href: "/account/sessions", label: "Sessions", icon: MonitorSmartphone },
  ] },
];

/** `staff` only adds the admin shortcut; the admin area enforces role and MFA itself. */
export function AccountNav({ staff = false }: { staff?: boolean }) {
  const pathname = usePathname();
  // Customer profile/security pages reject staff sessions; staff manage those in the admin area.
  const visible = staff ? [
    { label: "Account", items: groups[0].items.slice(0, 2) },
    { label: "Staff", items: [
      { href: "/admin", label: "Admin console", icon: ShieldCheck },
      { href: "/admin/security", label: "Authenticator", icon: KeyRound },
    ] },
  ] : groups;
  return (
    <nav aria-label="Account" className="admin-nav">
      {visible.map((group) => (
        <div key={group.label} className="admin-nav-group">
          <p className="admin-nav-label">{group.label}</p>
          <ul>
            {group.items.map(({ href, label, icon: Icon }) => (
              <li key={href}>
                <Link prefetch={false} href={href}
                  aria-current={pathname === href || (href !== "/account" && pathname.startsWith(`${href}/`)) ? "page" : undefined}>
                  <Icon size={17} aria-hidden="true" /><span>{label}</span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}
