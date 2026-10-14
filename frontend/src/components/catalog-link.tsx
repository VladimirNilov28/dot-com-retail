"use client";

import Link from "next/link";
import { useLinkStatus } from "next/link";
import type { ComponentProps } from "react";

function Pending() {
  const { pending } = useLinkStatus();
  return <span className={pending ? "catalog-link-pending" : "sr-only"} role="status">{pending ? "Loading…" : ""}</span>;
}

export function CatalogLink(props: ComponentProps<typeof Link>) {
  return <Link {...props} prefetch={false}>{props.children}<Pending /></Link>;
}
