"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  ClipboardList, FolderTree, KeyRound, MessageSquareText, Package, ScrollText, Truck, Upload, Users, type LucideIcon,
} from "lucide-react";

type Item = { href: string; label: string; icon: LucideIcon; roles: readonly string[] };

const catalog = ["ADMIN", "CATALOG_MANAGER"] as const;
const orders = ["ADMIN", "ORDER_MANAGER"] as const;
const admin = ["ADMIN"] as const;
const staff = ["ADMIN", "CATALOG_MANAGER", "ORDER_MANAGER", "WAREHOUSE", "SUPPORT"] as const;

// Mirrors the server gates in lib/admin/policy.ts; the pages and API still enforce access.
const groups: { label: string; items: Item[] }[] = [
  { label: "Catalogue", items: [
    { href: "/admin/products", label: "Products", icon: Package, roles: catalog },
    { href: "/admin/categories", label: "Categories", icon: FolderTree, roles: catalog },
    { href: "/admin/import", label: "Bulk import", icon: Upload, roles: catalog },
    { href: "/admin/reviews", label: "Reviews", icon: MessageSquareText, roles: catalog },
  ] },
  { label: "Sales", items: [
    { href: "/admin/orders", label: "Orders", icon: ClipboardList, roles: orders },
    { href: "/admin/shipping", label: "Delivery options", icon: Truck, roles: admin },
  ] },
  { label: "Access", items: [
    { href: "/admin/users", label: "Users and roles", icon: Users, roles: admin },
    { href: "/admin/audit", label: "Audit log", icon: ScrollText, roles: admin },
    { href: "/admin/security", label: "Authenticator", icon: KeyRound, roles: staff },
  ] },
];

/** `role` is null until the session is verified; then only the authenticator is offered. */
export function AdminNav({ role }: { role: string | null }) {
  const pathname = usePathname();
  const visible = groups
    .map((group) => ({ ...group, items: group.items.filter((item) => role ? item.roles.includes(role) : item.href === "/admin/security") }))
    .filter((group) => group.items.length);
  return (
    <nav aria-label="Admin" className="admin-nav">
      {visible.map((group) => (
        <div key={group.label} className="admin-nav-group">
          <p className="admin-nav-label">{group.label}</p>
          <ul>
            {group.items.map(({ href, label, icon: Icon }) => (
              <li key={href}>
                <Link prefetch={false} href={href}
                  aria-current={pathname === href || pathname.startsWith(`${href}/`) ? "page" : undefined}>
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
