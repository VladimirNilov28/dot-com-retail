"use client";

import { SlidersHorizontal, X } from "lucide-react";
import { Button, Drawer } from "@heroui/react";
import { useId, useState, type MouseEvent } from "react";
import { isPlainClick } from "./category-navigation";
import { CatalogLink } from "./catalog-link";
import {
  ATTRIBUTE_PARAM_PREFIX, DEFAULT_SORT, catalogHref, hasActiveFilters, resultsHref,
  type AttributeFilter, type Category, type CatalogFacets, type ResultsState,
} from "@/lib/catalog/model";

function facetCount(facets: CatalogFacets | null, id: string) {
  return facets?.categories.find((entry) => entry.id === id)?.count;
}

function navClick(onNavigate: () => void) {
  return (event: MouseEvent<HTMLAnchorElement>) => {
    if (isPlainClick(event)) onNavigate();
  };
}

function PreserveParams({ state, omit }: { state: ResultsState; omit: readonly string[] }) {
  const defaultSort = state.query ? "RELEVANCE" : DEFAULT_SORT;
  return <>
    {!omit.includes("view") && state.view !== "grid" && <input type="hidden" name="view" value={state.view} />}
    {!omit.includes("sort") && state.sort !== defaultSort && <input type="hidden" name="sort" value={state.sort} />}
    {!omit.includes("query") && state.query && <input type="hidden" name="q" value={state.query} />}
    {!omit.includes("attributes") && state.attributes.map((attribute) =>
      <input key={attribute.name} type="hidden" name={`${ATTRIBUTE_PARAM_PREFIX}${attribute.name}`} value={attribute.value} />)}
  </>;
}

/** Contextual category navigation for the results sidebar/drawer: an "up"
 * link, direct children or siblings, and real facet counts where available.
 * Deliberately NOT the megamenu's full taxonomy (§9.4) — only what is
 * relevant to the current page, so it stays scannable instead of becoming a
 * second sitemap. */
function CategoryContext({ state, category, links, linksLabel, parentHref, facets, onNavigate }: {
  state: ResultsState; category?: Category; links: Category[]; linksLabel: string;
  parentHref?: string; facets: CatalogFacets | null; onNavigate: () => void;
}) {
  return (
    <section className="results-section" aria-label="Category navigation">
      <h2 className="results-section-heading">Categories</h2>
      <nav aria-label="Category navigation" className="results-nav">
        {category && (
          <CatalogLink href={parentHref ?? catalogHref(undefined, 1, state.view)} className="results-nav-link results-nav-up" onClick={navClick(onNavigate)}>
            {category.parent ? "↑ Up a level" : "↑ All categories"}
          </CatalogLink>
        )}
        {!category && <span className="results-nav-link results-nav-current" aria-current="page">All products</span>}
        {links.length > 0 && <p className="results-nav-group-label">{linksLabel}</p>}
        {links.map((entry) => {
          const count = facetCount(facets, entry.id);
          return (
            <CatalogLink key={entry.id} href={catalogHref(entry.slug, 1, state.view)} className="results-nav-link"
              aria-current={entry.id === category?.id ? "page" : undefined} onClick={navClick(onNavigate)}>
              {entry.name}{count !== undefined && <span className="results-nav-count"> ({count})</span>}
            </CatalogLink>
          );
        })}
      </nav>
    </section>
  );
}

/** Real filters only (§9.4): price range and single-value-per-name attribute
 * facets, as removable chips. No in-stock toggle — availability is not a
 * public contract. Counts come straight from `facets`, never invented. */
function Filters({ route, state, facets, onNavigate }: {
  route: string; state: ResultsState; facets: CatalogFacets | null; onNavigate: () => void;
}) {
  if (!facets) return null;
  const active = hasActiveFilters(state);
  const hasPriceFacet = facets.price.min !== null || facets.price.max !== null;
  const hasPrice = state.minPrice !== undefined || state.maxPrice !== undefined;
  return (
    <section className="results-section" aria-label="Filters">
      <div className="results-section-header">
        <h2 className="results-section-heading">Filters</h2>
        {active && (
          <CatalogLink href={resultsHref(route, state, { minPrice: undefined, maxPrice: undefined, attributes: [] })}
            className="results-clear-link" onClick={navClick(onNavigate)}>
            Clear all
          </CatalogLink>
        )}
      </div>
      {active && (
        <ul className="results-chips" aria-label="Applied filters">
          {hasPrice && (
            <li>
              <CatalogLink href={resultsHref(route, state, { minPrice: undefined, maxPrice: undefined })}
                className="results-chip" onClick={navClick(onNavigate)}>
                Price: {state.minPrice ?? "0"}–{state.maxPrice ?? "∞"} EUR <span aria-hidden="true">×</span>
              </CatalogLink>
            </li>
          )}
          {state.attributes.map((attribute) => (
            <li key={attribute.name}>
              <CatalogLink
                href={resultsHref(route, state, { attributes: state.attributes.filter((entry) => entry.name !== attribute.name) })}
                className="results-chip" onClick={navClick(onNavigate)}>
                {attribute.name}: {attribute.value} <span aria-hidden="true">×</span>
              </CatalogLink>
            </li>
          ))}
        </ul>
      )}
      {hasPriceFacet && (
        <form action={route} method="get" className="results-price-form" aria-label="Price range">
          <PreserveParams state={state} omit={["minPrice", "maxPrice"]} />
          <label className="results-price-label">
            Min
            <input type="number" min="0" step="0.01" name="minPrice" defaultValue={state.minPrice ?? ""} inputMode="decimal" className="results-price-input" />
          </label>
          <label className="results-price-label">
            Max
            <input type="number" min="0" step="0.01" name="maxPrice" defaultValue={state.maxPrice ?? ""} inputMode="decimal" className="results-price-input" />
          </label>
          <button type="submit" className="store-cta results-price-submit">Apply</button>
        </form>
      )}
      {facets.attributes.map((attribute) => (
        <div key={attribute.name} className="results-filter-block">
          <h3 className="results-filter-heading">{attribute.name}</h3>
          <ul className="results-filter-group" role="list">
            {attribute.values.map((value) => {
              const isActive = state.attributes.some((entry) => entry.name === attribute.name && entry.value === value.value);
              const nextAttributes: AttributeFilter[] = isActive
                ? state.attributes.filter((entry) => entry.name !== attribute.name)
                : [...state.attributes.filter((entry) => entry.name !== attribute.name), { name: attribute.name, value: value.value }];
              return (
                <li key={value.value}>
                  <CatalogLink href={resultsHref(route, state, { attributes: nextAttributes })}
                    className="results-filter-option" aria-pressed={isActive} onClick={navClick(onNavigate)}>
                    {value.value} <span className="results-filter-count">({value.count})</span>
                  </CatalogLink>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </section>
  );
}

export interface CatalogSidebarProps {
  route: string; state: ResultsState; category?: Category; links: Category[]; linksLabel: string;
  parentHref?: string; facets: CatalogFacets | null;
}

/** One sidebar implementation, rendered twice: a static desktop `<aside>`
 * (≥48rem) and inside an accessible HeroUI `Drawer` for narrower viewports
 * (§9.4) — never two different navigation/filter interfaces to maintain. */
export function ResultsSidebar(props: CatalogSidebarProps) {
  const [open, setOpen] = useState(false);
  const drawerId = useId();
  const close = () => setOpen(false);
  const badge = props.state.attributes.length + (props.state.minPrice !== undefined || props.state.maxPrice !== undefined ? 1 : 0);
  return (
    <>
      <aside className="results-sidebar" aria-label="Category navigation and filters">
        <CategoryContext {...props} onNavigate={() => {}} />
        <Filters {...props} onNavigate={() => {}} />
      </aside>
      <div className="results-drawer-trigger-wrap">
        <Drawer isOpen={open} onOpenChange={setOpen}>
          <Button variant="ghost" className="results-drawer-trigger" aria-haspopup="dialog">
            <SlidersHorizontal size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
            Categories &amp; filters
            {badge > 0 && <span className="results-drawer-badge" aria-hidden="true">{badge}</span>}
          </Button>
          <Drawer.Backdrop className="store-drawer-backdrop">
            <Drawer.Content placement="right" className="store-drawer-content">
              <Drawer.Dialog id={drawerId} className="store-drawer-dialog">
                <Drawer.Header className="store-drawer-header">
                  <Drawer.Heading className="text-lg font-semibold">Categories &amp; filters</Drawer.Heading>
                  <Drawer.CloseTrigger aria-label="Close categories and filters" className="store-close">
                    <X size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
                  </Drawer.CloseTrigger>
                </Drawer.Header>
                <Drawer.Body className="store-drawer-body">
                  <CategoryContext {...props} onNavigate={close} />
                  <Filters {...props} onNavigate={close} />
                </Drawer.Body>
              </Drawer.Dialog>
            </Drawer.Content>
          </Drawer.Backdrop>
        </Drawer>
      </div>
    </>
  );
}
