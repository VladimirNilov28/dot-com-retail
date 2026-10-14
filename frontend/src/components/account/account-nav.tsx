"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const links = [
  { href: "/account", label: "Overview" },
  { href: "/account/orders", label: "Orders" },
  { href: "/account/profile", label: "Personal info" },
  { href: "/account/security", label: "Security" },
  { href: "/account/connections", label: "Connected accounts" },
  { href: "/account/sessions", label: "Sessions" },
] as const;

export function AccountNav() {
  const pathname = usePathname();
  return (
    <nav aria-label="Account" className="account-nav">
      {links.map((link) => (
        <Link key={link.href} prefetch={false} href={link.href}
          aria-current={pathname === link.href || (link.href !== "/account" && pathname.startsWith(`${link.href}/`)) ? "page" : undefined}>{link.label}</Link>
      ))}
    </nav>
  );
}
