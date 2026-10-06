import type { CatalogCategoriesQuery, CatalogListingQuery } from "@/lib/graphql/generated";
import type { Decimal } from "@/lib/graphql/scalars";

export type Category = CatalogCategoriesQuery["categories"][number];
export type CatalogProduct = CatalogListingQuery["searchProducts"]["items"][number];
export type View = "grid" | "list";
export const PAGE_SIZE = 20;

export function listingState(parameters: Record<string, string | string[] | undefined>): { page: number; view: View } | null {
  const page = parameters.page ?? "1";
  const view = parameters.view ?? "grid";
  if (typeof page !== "string" || !/^[1-9]\d{0,9}$/.test(page) ||
      Number(page) > 2147483647 || (view !== "grid" && view !== "list")) {
    return null;
  }
  return { page: Number(page), view };
}

export function catalogHref(slug?: string, page = 1, view: View = "grid") {
  const query = new URLSearchParams();
  if (page !== 1) query.set("page", String(page));
  if (view !== "grid") query.set("view", view);
  const route = slug === undefined ? "/catalog" : `/catalog/${encodeURIComponent(slug)}`;
  return query.size ? `${route}?${query}` : route;
}

export function categoryIndex(categories: Category[]) {
  const byId = new Map<string, Category>();
  const bySlug = new Map<string, Category>();
  for (const category of categories) {
    if (!category.id || !category.slug || byId.has(category.id) || bySlug.has(category.slug)) {
      throw new TypeError("Invalid category identity");
    }
    byId.set(category.id, category);
    bySlug.set(category.slug, category);
  }
  function ancestors(category: Category) {
    const result: Category[] = [];
    const seen = new Set([category.id]);
    let id = category.parent?.id;
    while (id) {
      const parent = byId.get(id);
      if (!parent || seen.has(id)) throw new TypeError("Invalid category hierarchy");
      seen.add(id);
      result.unshift(parent);
      id = parent.parent?.id;
    }
    return result;
  }
  for (const category of categories) ancestors(category);
  return {
    bySlug, ancestors,
    children: (id?: string) => categories.filter((category) => (category.parent?.id ?? undefined) === id)
      .sort((a, b) => a.name.localeCompare(b.name, "en") || a.id.localeCompare(b.id, "en")),
  };
}

function exactPrice(value: Decimal) {
  const match = /^(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value);
  if (!match) throw new TypeError("Invalid catalog price");
  const exponent = Number(match[3] ?? "0");
  if (!Number.isInteger(exponent) || Math.abs(exponent) > 256) throw new TypeError("Invalid price exponent");
  const fraction = match[2] ?? "";
  const scale = fraction.length - exponent;
  const coefficient = BigInt(match[1] + fraction);
  return { coefficient, scale };
}

function comparePrice(left: Decimal, right: Decimal) {
  const a = exactPrice(left);
  const b = exactPrice(right);
  const scale = Math.max(a.scale, b.scale);
  const x = a.coefficient * BigInt(10) ** BigInt(scale - a.scale);
  const y = b.coefficient * BigInt(10) ** BigInt(scale - b.scale);
  return x < y ? -1 : x > y ? 1 : 0;
}

export function formatPrice(value: Decimal) {
  const { coefficient, scale } = exactPrice(value);
  const divisor = BigInt(10) ** BigInt(Math.max(0, scale - 2));
  const cents = scale <= 2 ? coefficient * BigInt(10) ** BigInt(2 - scale) :
    (coefficient + divisor / BigInt(2)) / divisor;
  return `€${new Intl.NumberFormat("en-IE").format(cents / BigInt(100))}.${String(cents % BigInt(100)).padStart(2, "0")}`;
}

export function productPrice(product: CatalogProduct) {
  const prices = product.variants.filter((variant) => variant.isActive).map((variant) => variant.price);
  if (!prices.length) return "Price unavailable";
  const minimum = prices.reduce((a, b) => comparePrice(a, b) <= 0 ? a : b);
  return `${prices.some((price) => comparePrice(price, minimum) !== 0) ? "From " : ""}${formatPrice(minimum)}`;
}
