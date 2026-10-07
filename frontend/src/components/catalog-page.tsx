import Link from "next/link";
import { redirect } from "next/navigation";
import { getCategories, getListing } from "@/lib/catalog/server";
import { catalogHref, categoryIndex, listingState, type View } from "@/lib/catalog/model";
import { CatalogControls } from "./catalog-controls";
import { CatalogLink } from "./catalog-link";
import { CatalogRetry } from "./catalog-retry";
import { ProductCard } from "./product-card";
import { CatalogSkeleton } from "./catalog-skeleton";

export async function CatalogPageContent({ slug, parameters }: {
  slug?: string; parameters: Record<string, string | string[] | undefined>;
}) {
  const categories = await getCategories();
  if (categories.status !== "success") {
    return <section className="space-y-4"><h1 className="text-2xl font-semibold tracking-tight">Catalog unavailable</h1>
      <p className="text-muted">{categories.error.message}</p><CatalogRetry /></section>;
  }
  const index = categoryIndex(categories.data.categories);
  const category = slug === undefined ? undefined : index.bySlug.get(slug);
  const state = listingState(parameters);
  const trail = category ? index.ancestors(category) : [];
  const children = index.children(category?.id);
  const siblings = category ? index.children(category.parent?.id) : [];
  const links = children.length ? children : siblings;
  if (!state) return <section className="space-y-4">
    <h1 className="text-2xl font-semibold tracking-tight">Invalid catalog URL</h1>
    <p className="text-muted">Use a positive page number and a grid or list view.</p>
    <Link href={catalogHref(slug)} className="store-cta">Go to first page</Link>
  </section>;
  const results = await CatalogResults({ slug, categoryId: category?.id, hasChildren: children.length > 0, state });
  return (
    <div className={`catalog-page catalog-${state.view}`}>
      <nav aria-label="Breadcrumb" className="catalog-breadcrumb">
        <Link href="/" className="store-link">Home</Link><span aria-hidden="true">/</span>
        {/* "Catalog" has no discovery page of its own to link to any more
            (that composition moved into the header megamenu); it stays a
            plain ancestor label instead of a dead link. */}
        <span>Catalog</span>
        {trail.map((ancestor) => <span key={ancestor.id} className="contents">
          <span aria-hidden="true">/</span><CatalogLink href={catalogHref(ancestor.slug, 1, state.view)} className="store-link">{ancestor.name}</CatalogLink>
        </span>)}
        <span aria-hidden="true">/</span><span aria-current="page">{category?.name ?? "All products"}</span>
      </nav>
      <header className="catalog-heading">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{category?.name ?? "All products"}</h1>
        <p className="text-sm text-muted">{category && children.length ?
          "Products assigned directly to this category. Browse a subcategory for its products." :
          "Every ByteCore product in one bounded, paginated list. Prices are shown in EUR."}</p>
      </header>
      {links.length > 0 && <nav aria-label={children.length && category ? "Subcategories" : "Categories"} className="catalog-categories">
        {category && <CatalogLink href={catalogHref(undefined, 1, state.view)} className="store-cta">All products</CatalogLink>}
        {links.map((entry) => <CatalogLink key={entry.id} href={catalogHref(entry.slug, 1, state.view)}
          className="store-cta" aria-current={entry.id === category?.id ? "page" : undefined}>{entry.name}</CatalogLink>)}
      </nav>}
      <div className="catalog-transition-loading"><CatalogSkeleton /></div>
      {results}
    </div>
  );
}

async function CatalogResults({ slug, categoryId, hasChildren, state }: {
  slug?: string; categoryId?: string; hasChildren: boolean; state: { page: number; view: View };
}) {
  const result = await getListing(state.page - 1, categoryId);
  if (result.status === "success" && state.page > Math.max(1, result.data.searchProducts.pageInfo.totalPages)) {
    redirect(catalogHref(slug, Math.max(1, result.data.searchProducts.pageInfo.totalPages), state.view));
  }
  const listing = result.status === "success" ? result.data.searchProducts : null;
  return <>
      <div className="catalog-toolbar">
        <p className="text-sm text-muted" role="status">{listing ?
          (listing.pageInfo.totalItems === 0 ? "0 products" :
            `${(state.page - 1) * 20 + 1}–${(state.page - 1) * 20 + listing.items.length} of ${listing.pageInfo.totalItems} products`) :
          "Products could not be loaded"}</p>
        <div className="flex items-center gap-3">
          <span className="text-xs text-muted">Price: low to high</span>
          <CatalogControls slug={slug} page={state.page} view={state.view} />
        </div>
      </div>
      {result.status !== "success" ? <section className="catalog-state space-y-4" aria-label="Products error">
        <h2 className="text-lg font-semibold">Unable to load products</h2>
        <p className="text-muted">{result.error.message}</p><CatalogRetry />
      </section> : listing && listing.items.length === 0 ? <section className="catalog-state space-y-3">
        <h2 className="text-lg font-semibold">{hasChildren && categoryId ? "No products assigned directly" : "No products yet"}</h2>
        <p className="text-muted">{hasChildren && categoryId ? "Products in subcategories are not included here. Choose a subcategory above." :
          "This catalog selection is empty. Browse another category or return to all products."}</p>
        {categoryId && <CatalogLink href={catalogHref(undefined, 1, state.view)} className="store-link">Browse all products</CatalogLink>}
      </section> : listing && <>
        <ul className="catalog-products" aria-label="Products">{listing.items.map((product) => <ProductCard key={product.id} product={product} />)}</ul>
        <p className="mt-4 text-xs text-muted">Stock information is not public yet. Availability is unknown; prices are browsing information, not a checkout quote.</p>
        {listing.pageInfo.totalPages > 1 && <nav aria-label="Pagination" className="catalog-pagination">
          {state.page > 1 ? <CatalogLink href={catalogHref(slug, state.page - 1, state.view)} className="store-cta">Previous</CatalogLink> : <span className="catalog-disabled">Previous</span>}
          <span className="text-sm">Page {state.page} of {listing.pageInfo.totalPages}</span>
          {state.page < listing.pageInfo.totalPages ? <CatalogLink href={catalogHref(slug, state.page + 1, state.view)} className="store-cta">Next</CatalogLink> : <span className="catalog-disabled">Next</span>}
        </nav>}
      </>}
  </>;
}
