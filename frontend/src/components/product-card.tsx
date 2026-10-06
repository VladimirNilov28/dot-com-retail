import Link from "next/link";
import { ImageUnavailable } from "./image-unavailable";
import { productPrice, type CatalogProduct } from "@/lib/catalog/model";

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
        <p className="text-sm text-muted">Availability unknown</p>
      </div>
    </li>
  );
}
