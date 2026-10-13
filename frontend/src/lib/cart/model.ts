import type { Decimal } from "@/lib/graphql/scalars";
import type { JsonValue } from "@/lib/graphql/scalars";
import type { ProductImageData } from "@/components/product-image";

export type CartLine = {
  id: string;
  variantId: string;
  sku: string;
  name: string;
  slug: string;
  options: string;
  image: ProductImageData | null;
  unitPrice: Decimal;
  quantity: number;
  lineTotal: Decimal;
  availability: "AVAILABLE" | "OUT_OF_STOCK" | null;
  limit: number | null;
  issue: string | null;
};

export type CartView = { id: string; lines: CartLine[]; subtotal: Decimal; currency: string; count: number };

export const maxQuantity = 99;

type RawCart = {
  id: string;
  totals: { subtotal: Decimal; currency: string };
  items: {
    id: string; quantity: number; subtotal: Decimal;
    productVariant: {
      id: string; sku: string; price: Decimal; attributes: JsonValue; isActive: boolean;
      availability: "AVAILABLE" | "OUT_OF_STOCK" | null; purchasableLimit: number | null;
      product: { name: string; slug: string; images: ProductImageData[] };
    };
  }[];
};

function optionText(attributes: JsonValue): string {
  if (attributes === null || typeof attributes !== "object" || Array.isArray(attributes)) return "";
  return Object.entries(attributes as Record<string, JsonValue>)
    .filter(([, value]) => typeof value === "string" && value !== "")
    .sort(([a], [b]) => a.localeCompare(b, "en"))
    .map(([key, value]) => `${key.charAt(0).toUpperCase()}${key.slice(1).replace(/([A-Z])/g, " $1").toLowerCase()}: ${value as string}`)
    .join(" · ");
}

/** The single place where stock/activity problems on a saved line are described. */
export function lineIssue(line: Pick<CartLine, "quantity" | "availability" | "limit"> & { active: boolean }): string | null {
  if (!line.active) return "No longer available";
  if (line.availability === "OUT_OF_STOCK") return "Out of stock";
  if (line.limit !== null && line.limit < 10 && line.quantity > line.limit) return `Only ${line.limit} available`;
  return null;
}

export function toCartView(raw: RawCart): CartView {
  const lines = raw.items.map((item): CartLine => {
    const variant = item.productVariant;
    const base = {
      quantity: item.quantity, availability: variant.availability, limit: variant.purchasableLimit,
    };
    return {
      id: item.id, variantId: variant.id, sku: variant.sku, name: variant.product.name, slug: variant.product.slug,
      options: optionText(variant.attributes), image: variant.product.images[0] ?? null,
      unitPrice: variant.price, lineTotal: item.subtotal, ...base,
      issue: lineIssue({ ...base, active: variant.isActive }),
    };
  });
  return {
    id: raw.id, lines, subtotal: raw.totals.subtotal, currency: raw.totals.currency,
    count: lines.reduce((sum, line) => sum + line.quantity, 0),
  };
}

export type CartMessage = { tone: "success" | "error" | "info"; text: string };
export type CartSnapshot =
  | { status: "ok"; cart: CartView | null }
  | { status: "error"; message: string };

export function cartErrorMessage(kind: string): string {
  switch (kind) {
    case "unauthenticated": case "forbidden": return "Your session changed. Reload the page to continue.";
    case "validation": return "That item or quantity can't be added right now. Check availability and try again.";
    case "read-unavailable": return "Your cart couldn't be loaded right now. Try again in a moment.";
    case "timeout": case "transport": case "unavailable": return "The cart service is unavailable. Your cart has not changed — try again.";
    default: return "The cart could not be updated. Your cart has not changed.";
  }
}
