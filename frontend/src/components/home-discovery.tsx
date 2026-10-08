import Link from "next/link";
import { Fragment } from "react";
import { CatalogRetry } from "./catalog-retry";
import { ProductCard } from "./product-card";
import { catalogHref, homeCategories, type Category } from "@/lib/catalog/model";
import { getHomeDiscovery, getHomeProducts } from "@/lib/catalog/server";

export function HomeReadError({ title, message }: { title: string; message: string }) {
  return <section className="space-y-3" aria-label={title}>
    <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
    <p className="text-sm text-muted">{message}</p>
    <CatalogRetry />
  </section>;
}

async function CategoryProducts({ category }: { category: Category }) {
  const result = await getHomeProducts(category.id);
  if (result.status !== "success") return <HomeReadError title={`${category.name} products unavailable`} message={result.error.message} />;
  if (result.data.searchProducts.items.length === 0) return null;
  return <section className={`home-selection home-selection-${result.data.searchProducts.items.length}`} aria-labelledby={`selection-${category.id}`}>
    <div className="home-section-heading">
      <div className="space-y-1">
        <h2 id={`selection-${category.id}`} className="text-xl font-semibold tracking-tight">Browse {category.name}</h2>
        <p className="text-sm text-muted">Directly assigned products, lowest price first.</p>
      </div>
      <Link href={catalogHref(category.slug)} prefetch={false} className="store-link">View {category.name}</Link>
    </div>
    <ul className="home-products" aria-label={`Products in ${category.name}`}>
      {result.data.searchProducts.items.map((product) => <ProductCard key={product.id} product={product} headingLevel={3} />)}
    </ul>
  </section>;
}

export async function homeDiscoveryContent(categories: Category[]) {
  const result = await getHomeDiscovery();
  if (result.status !== "success") return <HomeReadError title="Product selections unavailable" message={result.error.message} />;
  const selected = homeCategories(categories, result.data.searchProducts.facets.categories);
  if (!selected.length) return <section className="space-y-2">
    <h2 className="text-xl font-semibold tracking-tight">No category products yet</h2>
    <p className="text-sm text-muted">There are no products assigned to these categories yet.</p>
    <Link href="/catalog/all-products" className="store-link">Browse all products</Link>
  </section>;
  return <div className="home-discovery">{await Promise.all(selected.map(async (category) =>
    <Fragment key={category.id}>{await CategoryProducts({ category })}</Fragment>))}</div>;
}
