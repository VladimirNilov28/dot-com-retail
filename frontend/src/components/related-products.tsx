import type { CatalogProduct } from "@/lib/catalog/model";
import { ProductCard } from "./product-card";

/** Deterministic related products (shared category, then brand) — never presented as personalised. */
export function RelatedProducts({ title, products }: { title: string; products: readonly CatalogProduct[] }) {
  if (products.length === 0) return null;
  return (
    <section className="related-products space-y-4" aria-labelledby="related-products-heading">
      <h2 id="related-products-heading" className="text-xl font-semibold tracking-tight">{title}</h2>
      <ul className="home-products" aria-label={title}>
        {products.map((product) => <ProductCard key={product.id} product={product} headingLevel={3} />)}
      </ul>
    </section>
  );
}
