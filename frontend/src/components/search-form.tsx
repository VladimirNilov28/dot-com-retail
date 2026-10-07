"use client";

import { Search } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { requestPublic } from "@/lib/graphql/browser";
import { latestRequest } from "@/lib/graphql/transport";
import { catalogSuggestions } from "@/lib/graphql/operations";

type Suggestion = { productId: string; name: string; slug: string };
const DEBOUNCE_MS = 250;
const MIN_QUERY_LENGTH = 2;

/** The search/results surface's own query input (§9.4/§9.6). Submits a plain
 * GET to `/search?q=…` without JavaScript; suggestions are a progressive
 * enhancement layered on top using the same public, bounded transport used
 * for results (no second transport, no unbounded reads). */
export function SearchForm({ query }: { query: string }) {
  const router = useRouter();
  const [value, setValue] = useState(query);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [status, setStatus] = useState<"idle" | "loading" | "error">("idle");
  const inputId = useId();
  const listboxId = useId();
  const tracker = useRef(latestRequest());
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => () => { tracker.current.cancel(); clearTimeout(timer.current); }, []);

  useEffect(() => {
    function onOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onOutside);
    return () => document.removeEventListener("mousedown", onOutside);
  }, []);

  function schedule(query: string) {
    clearTimeout(timer.current);
    const trimmed = query.trim();
    if (trimmed.length < MIN_QUERY_LENGTH) {
      tracker.current.cancel();
      setSuggestions([]); setStatus("idle"); setOpen(false);
      return;
    }
    timer.current = setTimeout(async () => {
      setStatus("loading");
      const result = await tracker.current.run((signal) => requestPublic(catalogSuggestions, { query: trimmed, limit: 8 }, { signal }));
      if (result.status === "failure" && result.error.kind === "stale") return;
      if (result.status === "success") {
        setSuggestions(result.data.productSearchSuggestions);
        setStatus("idle");
      } else {
        setSuggestions([]);
        setStatus("error");
      }
      setOpen(true); setActive(-1);
    }, DEBOUNCE_MS);
  }

  function go(query: string) {
    setOpen(false);
    router.push(`/search?q=${encodeURIComponent(query)}`);
  }

  function goToProduct(slug: string) {
    setOpen(false);
    router.push(`/products/${encodeURIComponent(slug)}`);
  }

  return (
    <div ref={containerRef} className="results-search">
      <form role="search" action="/search" method="get" className="results-search-form" onSubmit={(event) => {
        event.preventDefault();
        go(value);
      }}>
        <label htmlFor={inputId} className="sr-only">Search products</label>
        <Search size={18} strokeWidth={2} aria-hidden="true" focusable="false" className="results-search-icon" />
        <input id={inputId} name="q" type="search" value={value} autoComplete="off"
          role="combobox" aria-expanded={open && suggestions.length > 0} aria-controls={listboxId}
          aria-activedescendant={active >= 0 ? `${listboxId}-${active}` : undefined}
          className="results-search-input" placeholder="Search products"
          onChange={(event) => { setValue(event.target.value); schedule(event.target.value); }}
          onFocus={() => { if (suggestions.length) setOpen(true); }}
          onKeyDown={(event) => {
            if (event.key === "Escape") { setOpen(false); return; }
            if (!open || suggestions.length === 0) return;
            if (event.key === "ArrowDown") { event.preventDefault(); setActive((index) => (index + 1) % suggestions.length); }
            else if (event.key === "ArrowUp") { event.preventDefault(); setActive((index) => (index - 1 + suggestions.length) % suggestions.length); }
            else if (event.key === "Enter" && active >= 0) { event.preventDefault(); goToProduct(suggestions[active].slug); }
          }} />
        <button type="submit" className="store-cta results-search-submit">Search</button>
      </form>
      {open && (
        <ul id={listboxId} role="listbox" aria-label="Search suggestions" className="results-suggestions">
          {status === "error" && <li className="results-suggestion-status">Suggestions unavailable</li>}
          {status !== "error" && suggestions.length === 0 && <li className="results-suggestion-status">No suggestions</li>}
          {suggestions.map((suggestion, index) => (
            <li key={suggestion.productId} id={`${listboxId}-${index}`} role="option" aria-selected={index === active}
              className={index === active ? "results-suggestion results-suggestion-active" : "results-suggestion"}>
              <button type="button" className="results-suggestion-button" onClick={() => goToProduct(suggestion.slug)}>{suggestion.name}</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
