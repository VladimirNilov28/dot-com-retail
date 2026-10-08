import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Star } from "lucide-react";
import { getProduct } from "@/lib/product/server";
import { selectVariant, variantHref, variantLabel, variantSpecifications, variantOptionLabels } from "@/lib/product/model";
import { formatPrice, catalogHref } from "@/lib/catalog/model";
import { CatalogLink } from "@/components/catalog-link";
import { ImageUnavailable } from "@/components/image-unavailable";
import { ProductRecovery } from "@/components/product-recovery";

export async function generateMetadata({ params }: PageProps<"/products/[slug]">): Promise<Metadata> {
  const result = await getProduct((await params).slug);
  const product = result.status === "success" ? result.data.product : null;
  return {
    title: product?.name ?? (result.status === "success" ? "Product not found" : "Product unavailable"),
    description: product?.description?.trim() || (product ? `Explore ${product.name} and its variants at ByteCore.` : "This product is unavailable."),
    ...(product ? {} : { robots: { index: false, follow: false } }),
  };
}

export default async function ProductPage({ params, searchParams }: PageProps<"/products/[slug]">) {
  const { slug } = await params;
  const result = await getProduct(slug);
  if (result.status !== "success") return <ProductRecovery message={result.error.message} />;
  const product = result.data.product;
  if (!product) notFound();
  const parameters = await searchParams;
  const { selected, recovered } = selectVariant(product, parameters);
  const categories = [...product.categories].sort((a, b) => a.name.localeCompare(b.name, "en") || a.id.localeCompare(b.id, "en"));
  const variants = [...product.variants].sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : 1);
  const optionLabels = variantOptionLabels(variants);
  const specifications = selected ? variantSpecifications(selected) : [];
  return <article className="product-page">
    <nav aria-label="Breadcrumb" className="catalog-breadcrumb">
      <Link href="/" className="store-link">Home</Link><span aria-hidden="true">/</span>
      <Link href="/catalog/all-products" className="store-link">All products</Link>
      {categories[0] && <><span aria-hidden="true">/</span>
        <Link href={catalogHref(categories[0].slug)} className="store-link">{categories[0].name}</Link></>}
      <span aria-hidden="true">/</span><span aria-current="page">{product.name}</span>
    </nav>
    <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{product.name}</h1>
    <div className="product-detail-layout">
      <div className="product-media"><ImageUnavailable /></div>
      <div className="product-information">
        <div className="product-summary">
        <div className="product-selected">
          <p className="product-detail-price">{selected ? formatPrice(selected.price) : "Price unavailable"}</p>
          {selected && <p className="text-sm text-muted">SKU: <span className="product-sku">{selected.sku}</span></p>}
          {product.averageRating !== null && product.ratingCount > 0 && <p className="product-rating text-sm text-muted">
            <Star size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
            {product.averageRating.toFixed(1)} ({product.ratingCount} {product.ratingCount === 1 ? "rating" : "ratings"})
          </p>}
          <p className="text-sm text-muted">Availability unknown</p>
          {selected && !selected.isActive && <p className="product-variant-unavailable">This variant is unavailable.</p>}
        </div>
        <div className="product-transition-loading" role="status" aria-label="Loading selected variant">
          <div className="catalog-skeleton product-price-skeleton" />
          <div className="catalog-skeleton product-copy-skeleton" />
          <p className="text-sm text-muted">Loading variant…</p>
        </div>
        </div>
        <section className="product-variants" aria-labelledby="variant-heading">
          <h2 id="variant-heading" className="text-lg font-semibold tracking-tight">Choose a variant</h2>
          {recovered && <p className="text-sm text-muted" role="status">
            The linked variant is no longer available for this product. {selected ? "Showing the lowest-priced active variant." : "No active variant is available."}{" "}
            <Link href={variantHref(slug, selected?.id ?? null, parameters)} className="store-link">Use this product link</Link>
          </p>}
          {variants.length ? <ul className="product-variant-options" aria-label="Product variants">
            {variants.map((variant) => <li key={variant.id}>
              <CatalogLink href={variantHref(slug, variant.id, parameters)} scroll={false}
                className="product-variant-link" aria-current={selected?.id === variant.id ? "true" : undefined}>
                <span>{variantLabel(variant, optionLabels)}</span>
                <span className="product-variant-meta">
                  {selected?.id === variant.id && <span>Selected</span>}
                  {!variant.isActive && <span>Unavailable</span>}
                </span>
              </CatalogLink>
            </li>)}
          </ul> : <p className="text-sm text-muted">No variants are available for this product.</p>}
          {variants.length > 0 && !selected && <p className="text-sm text-muted">No active variant is available. You can still inspect the variants above.</p>}
        </section>
        {categories.length > 0 && <nav aria-label="Product categories" className="product-categories">
          {categories.map((category) => <Link key={category.id} href={catalogHref(category.slug)} className="store-link">{category.name}</Link>)}
        </nav>}
      </div>
    </div>
    <section className="product-description space-y-3" aria-labelledby="description-heading">
      <h2 id="description-heading" className="text-xl font-semibold tracking-tight">Description</h2>
      <p className="text-muted">{product.description?.trim() || "No description has been provided."}</p>
    </section>
    <div className="product-specification-region">
    <section className="product-selected product-specifications space-y-3" aria-labelledby="specifications-heading">
      <h2 id="specifications-heading" className="text-xl font-semibold tracking-tight">Specifications</h2>
      {specifications.length ? <dl>{specifications.map(([name, value], index) => <div key={`${name}-${index}`}>
        <dt className="text-muted">{name}</dt><dd>{value}</dd>
      </div>)}</dl> : <p className="text-muted">{variants.length ? "Select a variant to view its specifications." : "No variant specifications are available."}</p>}
    </section>
    <div className="product-specifications-loading space-y-3" aria-hidden="true">
      <h2 className="text-xl font-semibold tracking-tight">Specifications</h2>
      <div>{specifications.map((_, index) => <div key={index} className="product-specification-skeleton">
        <div className="catalog-skeleton" /><div className="catalog-skeleton" />
      </div>)}</div>
    </div>
    </div>
  </article>;
}
