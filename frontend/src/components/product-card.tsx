import Link from "next/link";
import { Star } from "lucide-react";
import { ImageUnavailable } from "./image-unavailable";
import { productPrice, type CatalogProduct } from "@/lib/catalog/model";

function Rating({ product }: { product: CatalogProduct }) {
  if (product.averageRating === null || product.ratingCount === 0) {
    return <p className="text-xs text-muted">No ratings yet</p>;
  }
  return (
    <p className="product-rating text-xs text-muted">
      <Star size={14} strokeWidth={2} aria-hidden="true" focusable="false" className="product-rating-icon" />
      {product.averageRating.toFixed(1)} ({product.ratingCount} {product.ratingCount === 1 ? "rating" : "ratings"})
    </p>
  );
}

export function ProductCard({ product }: { product: CatalogProduct }) {
  return (
    <li className="product-card">
      <ImageUnavailable />
      <div className="product-card-copy">
        <h2 className="font-semibold tracking-tight">
          <Link href={`/products/${encodeURIComponent(product.slug)}`} prefetch={false} className="product-name">
            {product.name}
          </Link>
        </h2>
        <p className="product-price">{productPrice(product)}</p>
        <Rating product={product} />
        <p className="text-sm text-muted">Availability unknown</p>
      </div>
    </li>
  );
}
