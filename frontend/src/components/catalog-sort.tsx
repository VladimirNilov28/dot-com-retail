"use client";

import { useRouter } from "next/navigation";
import { useId, useTransition } from "react";
import { resultsHref, type ResultsState } from "@/lib/catalog/model";
import type { ProductSort } from "@/lib/graphql/generated";

const OPTIONS: ReadonlyArray<{ value: ProductSort; label: string }> = [
  { value: "PRICE_ASC", label: "Price: low to high" },
  { value: "PRICE_DESC", label: "Price: high to low" },
  { value: "RATING_DESC", label: "Highest rated" },
];

/** Sorting responds immediately (no debounce, §9.4) and resets pagination,
 * since it produces a new ordering of the same result set. RELEVANCE is only
 * offered once a query is present — it has no defined meaning otherwise. */
export function CatalogSort({ route, state }: { route: string; state: ResultsState }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const id = useId();
  return (
    <div className="results-sort">
      <label htmlFor={id} className="text-xs text-muted">Sort</label>
      <select id={id} className="results-sort-select" value={state.sort} aria-busy={pending}
        onChange={(event) => {
          startTransition(() => router.push(resultsHref(route, state, { sort: event.target.value as ProductSort }), { scroll: false }));
        }}>
        {state.query && <option value="RELEVANCE">Best match</option>}
        {OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
      </select>
    </div>
  );
}
