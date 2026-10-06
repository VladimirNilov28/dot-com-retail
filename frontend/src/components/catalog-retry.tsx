"use client";

import { Button } from "@heroui/react";
import { useRouter } from "next/navigation";
import { useTransition } from "react";

export function CatalogRetry() {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  return <Button variant="secondary" className="catalog-retry" isDisabled={pending}
    onPress={() => startTransition(() => router.refresh())}>
    {pending ? "Retrying…" : "Retry"}
  </Button>;
}
