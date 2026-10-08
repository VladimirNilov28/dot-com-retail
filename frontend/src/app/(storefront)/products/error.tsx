"use client";

import { Button } from "@heroui/react";
import Link from "next/link";

export default function ProductError({ reset }: { reset: () => void }) {
  return <section className="space-y-4">
    <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Product unavailable</h1>
    <p className="text-muted">The product could not be displayed. Please try again.</p>
    <Button variant="secondary" className="catalog-retry" onPress={reset}>Retry</Button>
    <Link href="/catalog/all-products" className="store-link">Browse all products</Link>
  </section>;
}
