"use client";

import Link from "next/link";
import { Minus, Plus, Trash2 } from "lucide-react";
import { ProductImage } from "@/components/product-image";
import { formatPrice } from "@/lib/catalog/model";
import { variantHref } from "@/lib/product/model";
import { maxQuantity, type CartLine } from "@/lib/cart/model";
import { useCart } from "./cart-provider";

export function CartLines({ lines, onNavigate }: { lines: CartLine[]; onNavigate?: () => void }) {
  const { update, remove, pending } = useCart();
  return (
    <ul className="cart-lines" aria-label="Cart items">
      {lines.map((line) => {
        const ceiling = line.limit !== null && line.limit < 10 ? Math.max(line.limit, 1) : maxQuantity;
        return (
          <li key={line.id} className="cart-line">
            <div className="cart-thumb"><ProductImage image={line.image} sizes="4.5rem" /></div>
            <div className="cart-line-body">
              <Link href={variantHref(line.slug, line.variantId)} className="store-link cart-line-name" onClick={onNavigate}>{line.name}</Link>
              {line.options ? <p className="text-sm text-muted">{line.options}</p> : null}
              <p className="text-sm text-muted">SKU: {line.sku}</p>
              <p className="text-sm">{formatPrice(line.unitPrice)} each</p>
              {line.issue ? <p role="status" className="cart-line-issue">{line.issue}. Adjust or remove this item before checkout.</p> : null}
              <div className="cart-line-controls">
                <div className="cart-quantity" role="group" aria-label={`Quantity for ${line.name}`}>
                  <button type="button" aria-label={`Decrease quantity of ${line.name}`} disabled={pending || line.quantity <= 1}
                    onClick={() => void update(line.id, line.quantity - 1)}><Minus size={16} aria-hidden="true" /></button>
                  <output aria-live="polite">{line.quantity}</output>
                  <button type="button" aria-label={`Increase quantity of ${line.name}`} disabled={pending || line.quantity >= ceiling}
                    onClick={() => void update(line.id, line.quantity + 1)}><Plus size={16} aria-hidden="true" /></button>
                </div>
                <button type="button" className="cart-remove" disabled={pending} onClick={() => void remove(line.id)}>
                  <Trash2 size={16} aria-hidden="true" /> Remove<span className="sr-only"> {line.name}</span>
                </button>
              </div>
            </div>
            <p className="cart-line-total" aria-label={`Line total ${formatPrice(line.lineTotal)}`}>{formatPrice(line.lineTotal)}</p>
          </li>
        );
      })}
    </ul>
  );
}
