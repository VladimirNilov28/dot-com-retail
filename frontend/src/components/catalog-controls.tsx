"use client";

import { Button } from "@heroui/react";
import { Grid2X2, List } from "lucide-react";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { resultsHref, type ResultsState } from "@/lib/catalog/model";

export function CatalogControls({ route, state }: { route: string; state: ResultsState }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  return (
    <div className="catalog-view" aria-label="Product view" role="group" aria-busy={pending}>
      {(["grid", "list"] as const).map((mode) => {
        const Icon = mode === "grid" ? Grid2X2 : List;
        return (
          <Button key={mode} variant="ghost" aria-pressed={state.view === mode} aria-label={`${mode === "grid" ? "Grid" : "List"} view`}
            className="catalog-view-button" onPress={() => {
              startTransition(() => router.push(resultsHref(route, state, { view: mode }), { scroll: false }));
            }}>
            <Icon size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
          </Button>
        );
      })}
      <span className="sr-only" role="status">{pending ? "Updating product view" : ""}</span>
    </div>
  );
}
