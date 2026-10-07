import Link from "next/link";
import { getCategories } from "@/lib/catalog/server";
import { catalogHref, categoryIndex } from "@/lib/catalog/model";
import { CatalogRetry } from "./catalog-retry";

// A flat taxonomy is a flat sitemap; keep deeper categories reachable without
// repeating this component's logic for arbitrary depth, which the real data
// does not need: the owner's hierarchy is at most grandchildren of a root.
export async function CatalogDiscovery() {
  const result = await getCategories();
  if (result.status !== "success") {
    return <section className="space-y-4">
      <h1 className="text-2xl font-semibold tracking-tight">Catalog unavailable</h1>
      <p className="text-muted">{result.error.message}</p>
      <CatalogRetry />
    </section>;
  }
  const index = categoryIndex(result.data.categories);
  const roots = index.children(undefined);
  return (
    <div className="catalog-discovery">
      <header className="catalog-heading">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Catalog</h1>
        <p className="text-sm text-muted">Find a category, or browse every ByteCore product at once.</p>
      </header>
      {roots.length === 0 ? <section className="catalog-state space-y-3">
        <h2 className="text-lg font-semibold">No categories yet</h2>
        <p className="text-muted">Categories are not available right now.</p>
      </section> : <nav aria-label="Categories" className="catalog-discovery-groups">
        {roots.map((root) => {
          const children = index.children(root.id);
          return (
            <div key={root.id} className="catalog-discovery-group">
              <h2 className="catalog-discovery-group-heading">
                <Link href={catalogHref(root.slug)} className="store-link">{root.name}</Link>
              </h2>
              {children.length > 0 && <ul className="catalog-discovery-links">
                {children.map((child) => {
                  const grandchildren = index.children(child.id);
                  return (
                    <li key={child.id} className="catalog-discovery-child">
                      <Link href={catalogHref(child.slug)} className="store-link">{child.name}</Link>
                      {grandchildren.length > 0 && <details className="catalog-discovery-disclosure">
                        <summary className="catalog-discovery-summary">{child.name} subcategories</summary>
                        <ul className="catalog-discovery-grandchildren">
                          {grandchildren.map((grandchild) => <li key={grandchild.id}>
                            <Link href={catalogHref(grandchild.slug)} className="store-link">{grandchild.name}</Link>
                          </li>)}
                        </ul>
                      </details>}
                    </li>
                  );
                })}
              </ul>}
            </div>
          );
        })}
      </nav>}
      <p className="catalog-discovery-all">
        <Link href={catalogHref(undefined)} className="store-link">All products</Link>
        <span className="text-sm text-muted"> — browse everything in one bounded, paginated list.</span>
      </p>
    </div>
  );
}
