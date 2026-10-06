"use client";

import { Button } from "@heroui/react";
export default function CatalogError({ reset }: { reset: () => void }) {
  return <section className="space-y-4">
    <h1 className="text-2xl font-semibold tracking-tight">Catalog unavailable</h1>
    <p className="text-muted">Something went wrong while loading the catalog.</p>
    <Button variant="secondary" onPress={reset}>Retry</Button>
  </section>;
}
