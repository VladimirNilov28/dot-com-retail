import Link from "next/link";
import { Suspense } from "react";
import { CatalogRetry } from "./catalog-retry";
import { ProductCard } from "./product-card";
import { catalogHref, homeCategories, type Category } from "@/lib/catalog/model";
import { getHomeDiscovery, getHomeProducts } from "@/lib/catalog/server";

export function HomeDiscoveryLoading() {
  return <div className="home-loading" role="status" aria-label="Loading product selections">
    <div className="catalog-skeleton h-6 w-48" />
    <ul className="home-products" aria-hidden="true">
      {Array.from({ length: 4 }, (_, index) => <li key={index} className="product-card">
        <div className="catalog-image catalog-skeleton" />
        <div className="catalog-skeleton h-12" /><div className="catalog-skeleton h-6 w-24" />
      </li>)}
    </ul>
  </div>;
}

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
  return <section className="home-selection" aria-labelledby={`selection-${category.id}`}>
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

export async function HomeDiscovery({ categories }: { categories: Category[] }) {
  const result = await getHomeDiscovery();
  if (result.status !== "success") return <HomeReadError title="Product selections unavailable" message={result.error.message} />;
  const selected = homeCategories(categories, result.data.searchProducts.facets.categories);
  if (!selected.length) return <section className="space-y-2">
    <h2 className="text-xl font-semibold tracking-tight">No category products yet</h2>
    <p className="text-sm text-muted">There are no products assigned to these categories yet.</p>
    <Link href="/catalog/all-products" className="store-link">Browse all products</Link>
  </section>;
  return <>{selected.map((category) => <Suspense key={category.id} fallback={<HomeDiscoveryLoading />}>
    <CategoryProducts category={category} />
  </Suspense>)}</>;
}
