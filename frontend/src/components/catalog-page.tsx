import Link from "next/link";
import { redirect } from "next/navigation";
import { getCategories, getListing } from "@/lib/catalog/server";
import {
  catalogHref, categoryIndex, resultsState, resultsHref, type CatalogFacets, type ResultsState,
} from "@/lib/catalog/model";
import { CatalogControls } from "./catalog-controls";
import { CatalogSort } from "./catalog-sort";
import { CatalogLink } from "./catalog-link";
import { CatalogRetry } from "./catalog-retry";
import { ProductCard } from "./product-card";
import { CatalogSkeleton } from "./catalog-skeleton";
import { ResultsSidebar } from "./catalog-sidebar";
import { SearchForm } from "./search-form";

/** The one shared results surface for category pages, "All products" and
 * search (§9.4): a desktop sidebar + compact grid/list on the right, one
 * URL-backed query/filter/sort/view/page state, and the same components
 * regardless of entry point. `slug` selects a direct-membership category;
 * `isSearch` adds the visible query input (§9.6) and a blank-query "browse"
 * fallback instead of an error. */
export async function CatalogPageContent({ slug, isSearch = false, parameters }: {
  slug?: string; isSearch?: boolean; parameters: Record<string, string | string[] | undefined>;
}) {
  const categories = await getCategories();
  if (categories.status !== "success") {
    return <section className="space-y-4"><h1 className="text-2xl font-semibold tracking-tight">Catalog unavailable</h1>
      <p className="text-muted">{categories.error.message}</p><CatalogRetry /></section>;
  }
  const index = categoryIndex(categories.data.categories);
  const category = slug === undefined ? undefined : index.bySlug.get(slug);
  const state = resultsState(parameters);
  const route = isSearch ? "/search" : catalogHref(slug);
  if (!state) return <section className="space-y-4">
    <h1 className="text-2xl font-semibold tracking-tight">Invalid catalog URL</h1>
    <p className="text-muted">Use a positive page number, a grid or list view, and a supported sort.</p>
    <Link href={route} className="store-cta">Go to first page</Link>
  </section>;
  const trail = category ? index.ancestors(category) : [];
  const children = index.children(category?.id);
  const siblings = category ? index.children(category.parent?.id) : [];
  const links = category ? (children.length ? children : siblings) : index.children(undefined);
  const linksLabel = category ? (children.length ? "Subcategories" : "Related categories") : "Browse by category";
  const parentHref = trail.length ? catalogHref(trail[trail.length - 1]?.slug, 1, state.view) : undefined;
  const heading = isSearch ? (state.query ? `Search results for “${state.query}”` : "Search") : (category?.name ?? "All products");
  const results = await CatalogResults({ route, categoryId: category?.id, hasChildren: children.length > 0, isSearch, state });
  return (
    <div className={`catalog-page catalog-${state.view}`}>
      <nav aria-label="Breadcrumb" className="catalog-breadcrumb">
        <Link href="/" className="store-link">Home</Link><span aria-hidden="true">/</span>
        {isSearch ? <span aria-current="page">Search</span> : <>
          {/* "Catalog" has no discovery page of its own to link to any more
              (that composition moved into the header megamenu); it stays a
              plain ancestor label instead of a dead link. */}
          <span>Catalog</span>
          {trail.map((ancestor) => <span key={ancestor.id} className="contents">
            <span aria-hidden="true">/</span><CatalogLink href={catalogHref(ancestor.slug, 1, state.view)} className="store-link">{ancestor.name}</CatalogLink>
          </span>)}
          <span aria-hidden="true">/</span><span aria-current="page">{category?.name ?? "All products"}</span>
        </>}
      </nav>
      <header className="catalog-heading">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{heading}</h1>
        {/* One shared query input on every results surface (§9.6): visible
            immediately, pre-populated from the URL, scoped to this route's
            filters/view via `buildHref` so category search never jumps to an
            unrelated global result set. */}
        <SearchForm route={route} state={state} />
        {!isSearch && category && children.length ? <p className="text-sm text-muted">
          Products assigned directly to this category. Browse a subcategory for its products.
        </p> : null}
      </header>
      <div className="results-layout">
        <ResultsSidebar route={route} state={state} category={category} links={links} linksLabel={linksLabel}
          parentHref={parentHref} facets={results.facets} />
        <div className="results-main">
          <div className="catalog-transition-loading"><CatalogSkeleton /></div>
          {results.content}
        </div>
      </div>
    </div>
  );
}

async function CatalogResults({ route, categoryId, hasChildren, isSearch, state }: {
  route: string; categoryId?: string; hasChildren: boolean; isSearch: boolean; state: ResultsState;
}): Promise<{ content: React.ReactNode; facets: CatalogFacets | null }> {
  const result = await getListing({
    page: state.page - 1, sort: state.sort, query: state.query || undefined, categoryId,
    minPrice: state.minPrice, maxPrice: state.maxPrice, attributes: state.attributes,
  });
  if (result.status === "success" && state.page > Math.max(1, result.data.searchProducts.pageInfo.totalPages)) {
    redirect(resultsHref(route, state, { page: Math.max(1, result.data.searchProducts.pageInfo.totalPages) }));
  }
  const listing = result.status === "success" ? result.data.searchProducts : null;
  const facets = listing?.facets ?? null;
  const content = <>
      <div className="catalog-toolbar">
        <p className="text-sm text-muted" role="status">{listing ?
          (listing.pageInfo.totalItems === 0 ? "0 products" :
            `${(state.page - 1) * 20 + 1}–${(state.page - 1) * 20 + listing.items.length} of ${listing.pageInfo.totalItems} products`) :
          "Products could not be loaded"}</p>
        <div className="flex flex-wrap items-center gap-3">
          <CatalogSort route={route} state={state} />
          <CatalogControls route={route} state={state} />
        </div>
      </div>
      {result.status !== "success" ? <section className="catalog-state space-y-4" aria-label="Products error">
        <h2 className="text-lg font-semibold">Unable to load products</h2>
        <p className="text-muted">{result.error.message}</p><CatalogRetry />
      </section> : listing && listing.items.length === 0 ? <section className="catalog-state space-y-3">
        <h2 className="text-lg font-semibold">{hasChildren && categoryId ? "No products assigned directly" :
          isSearch && state.query ? "No matching products" : "No products yet"}</h2>
        <p className="text-muted">{hasChildren && categoryId ? "Products in subcategories are not included here. Choose a subcategory above." :
          isSearch && state.query ? "Try a different search term or clear filters." :
          "This catalog selection is empty. Browse another category or return to all products."}</p>
        {categoryId && <CatalogLink href={catalogHref(undefined, 1, state.view)} className="store-link">Browse all products</CatalogLink>}
      </section> : listing && <>
        <ul className="catalog-products" aria-label="Products">{listing.items.map((product) => <ProductCard key={product.id} product={product} />)}</ul>
        <p className="mt-4 text-xs text-muted">Stock information is not public yet. Availability is unknown; prices are browsing information, not a checkout quote.</p>
        {listing.pageInfo.totalPages > 1 && <nav aria-label="Pagination" className="catalog-pagination">
          {state.page > 1 ? <CatalogLink href={resultsHref(route, state, { page: state.page - 1 })} className="store-cta">Previous</CatalogLink> : <span className="catalog-disabled">Previous</span>}
          <span className="text-sm">Page {state.page} of {listing.pageInfo.totalPages}</span>
          {state.page < listing.pageInfo.totalPages ? <CatalogLink href={resultsHref(route, state, { page: state.page + 1 })} className="store-cta">Next</CatalogLink> : <span className="catalog-disabled">Next</span>}
        </nav>}
      </>}
  </>;
  return { content, facets };
}
