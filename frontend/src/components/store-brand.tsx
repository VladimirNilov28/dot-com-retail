"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

export function StoreBrand() {
  const pathname = usePathname();

  return (
    <Link
      href="/"
      className="store-brand"
      aria-current={pathname === "/" ? "page" : undefined}
    >
      ByteCore
    </Link>
  );
}
