import type { CatalogCategoriesQuery, CatalogListingQuery, ProductSort } from "@/lib/graphql/generated";
import { decimal, type Decimal } from "@/lib/graphql/scalars";

export type Category = CatalogCategoriesQuery["categories"][number];
export type CatalogProduct = CatalogListingQuery["searchProducts"]["items"][number];
export type CatalogFacets = CatalogListingQuery["searchProducts"]["facets"];
export type View = "grid" | "list";
export const PAGE_SIZE = 20;
export const HOME_SECTION_LIMIT = 3;

export function homeCategories(categories: Category[], facets: CatalogFacets["categories"]): Category[] {
  const eligible = new Set(facets.filter((facet) => facet.count > 0).map((facet) => facet.id));
  return categories.filter((category) => eligible.has(category.id))
    .sort((a, b) => a.name.localeCompare(b.name, "en") || a.id.localeCompare(b.id, "en"))
    .slice(0, HOME_SECTION_LIMIT);
}

export function listingState(parameters: Record<string, string | string[] | undefined>): { page: number; view: View } | null {
  const page = parameters.page ?? "1";
  const view = parameters.view ?? "grid";
  if (typeof page !== "string" || !/^[1-9]\d{0,9}$/.test(page) ||
      Number(page) > 2147483647 || (view !== "grid" && view !== "list")) {
    return null;
  }
  return { page: Number(page), view };
}

/** Sorts actually supported by `searchProducts` (backend schema `ProductSort`).
 * RELEVANCE only has a defined meaning with a non-blank query, matching the
 * backend's documented ranking contract. */
export const SORTS: readonly ProductSort[] = ["RELEVANCE", "PRICE_ASC", "PRICE_DESC", "RATING_DESC"];
export const DEFAULT_SORT: ProductSort = "PRICE_ASC";
export const ATTRIBUTE_PARAM_PREFIX = "attr.";
export const MAX_QUERY_LENGTH = 200;
export const MAX_ATTRIBUTE_FILTERS = 20;

export interface AttributeFilter { readonly name: string; readonly value: string }

export interface ResultsState {
  readonly page: number;
  readonly view: View;
  readonly sort: ProductSort;
  readonly query: string;
  readonly minPrice?: Decimal;
  readonly maxPrice?: Decimal;
  readonly attributes: readonly AttributeFilter[];
}

function parsePriceParam(value: string | string[] | undefined): Decimal | null | undefined {
  if (value === undefined) return undefined;
  if (Array.isArray(value)) return null;
  try {
    const parsed = decimal(value);
    return Number(parsed) >= 0 ? parsed : null;
  } catch {
    return null;
  }
}

/** The shared URL-state contract for category pages, "All products" and
 * search (§9.4/§9.6): query/sort/filters/page/view all live in the URL so
 * results are reload- and back/forward-restorable, and category browsing and
 * search share one state shape instead of a second, inconsistent interface. */
export function resultsState(parameters: Record<string, string | string[] | undefined>): ResultsState | null {
  const base = listingState(parameters);
  if (!base) return null;
  const rawQuery = parameters.q;
  if (Array.isArray(rawQuery)) return null;
  const query = (rawQuery ?? "").trim();
  if (query.length > MAX_QUERY_LENGTH) return null;
  const rawSort = parameters.sort;
  if (Array.isArray(rawSort)) return null;
  const sort = (rawSort ?? (query ? "RELEVANCE" : DEFAULT_SORT)) as ProductSort;
  if (!SORTS.includes(sort) || (sort === "RELEVANCE" && !query)) return null;
  const minPrice = parsePriceParam(parameters.minPrice);
  const maxPrice = parsePriceParam(parameters.maxPrice);
  if (minPrice === null || maxPrice === null) return null;
  if (minPrice !== undefined && maxPrice !== undefined && Number(minPrice) > Number(maxPrice)) return null;
  const attributes: AttributeFilter[] = [];
  for (const [key, value] of Object.entries(parameters)) {
    if (!key.startsWith(ATTRIBUTE_PARAM_PREFIX)) continue;
    if (Array.isArray(value) || !value) return null;
    const name = key.slice(ATTRIBUTE_PARAM_PREFIX.length);
    if (!name || name.length > 100 || value.length > 200) return null;
    attributes.push({ name, value });
  }
  if (attributes.length > MAX_ATTRIBUTE_FILTERS) return null;
  attributes.sort((a, b) => a.name.localeCompare(b.name, "en"));
  return {
    ...base, sort, query,
    ...(minPrice === undefined ? {} : { minPrice }),
    ...(maxPrice === undefined ? {} : { maxPrice }),
    attributes,
  };
}

const RESET_ON_KEYS = ["sort", "query", "minPrice", "maxPrice", "attributes"] as const;

/** Builds a URL for `route` from `current` state with `changes` applied.
 * Sort/query/filter changes reset pagination to page 1 (a new result set);
 * view and plain page navigation do not reset anything else (§9.4). */
export function resultsHref(route: string, current: ResultsState, changes: Partial<ResultsState> = {}) {
  const next: ResultsState = { ...current, ...changes };
  const resets = RESET_ON_KEYS.some((key) => key in changes);
  const page = changes.page !== undefined ? changes.page : (resets ? 1 : current.page);
  const query = new URLSearchParams();
  if (page !== 1) query.set("page", String(page));
  if (next.view !== "grid") query.set("view", next.view);
  const defaultSort = next.query ? "RELEVANCE" : DEFAULT_SORT;
  if (next.sort !== defaultSort) query.set("sort", next.sort);
  if (next.query) query.set("q", next.query);
  if (next.minPrice) query.set("minPrice", next.minPrice);
  if (next.maxPrice) query.set("maxPrice", next.maxPrice);
  for (const attribute of next.attributes) query.set(`${ATTRIBUTE_PARAM_PREFIX}${attribute.name}`, attribute.value);
  return query.size ? `${route}?${query}` : route;
}

export function hasActiveFilters(state: ResultsState) {
  return state.minPrice !== undefined || state.maxPrice !== undefined || state.attributes.length > 0;
}

/** `/catalog` itself no longer renders a product listing or a discovery page;
 * category discovery lives in the header's catalog megamenu, and the bounded,
 * paginated "all products" listing lives at this reserved static route, which
 * Next.js always prioritises over `/catalog/[slug]`. Accepted trade-off: a
 * real category slugged "all-products" would become unreachable via
 * `/catalog/<slug>` (none exists in the current taxonomy). */
export const ALL_PRODUCTS_SLUG = "all-products";

export function catalogHref(slug?: string, page = 1, view: View = "grid") {
  const query = new URLSearchParams();
  if (page !== 1) query.set("page", String(page));
  if (view !== "grid") query.set("view", view);
  const route = slug === undefined ? `/catalog/${ALL_PRODUCTS_SLUG}` : `/catalog/${encodeURIComponent(slug)}`;
  return query.size ? `${route}?${query}` : route;
}

/** The shared results surface (category pages, "All products" and search)
 * already renders its own visible, URL-populated query input (§9.6); the
 * header's compact quick action is a duplicate field there, so it hides
 * itself on these routes instead of stacking a second entry point. */
export function isResultsRoute(pathname: string) {
  return pathname === "/search" || pathname.startsWith("/catalog/");
}

/** Readable presentation labels for the real `searchProducts` attribute facet
 * keys (backend schema has no label/metadata field of its own). Backend keys
 * and URL serialization are unchanged by this map — it is display-only. */
const ATTRIBUTE_LABELS: Record<string, string> = {
  backlight: "Backlight", bands: "Bands", batteryLifeHours: "Battery life (hours)", capacity: "Capacity",
  color: "Colour", connectivity: "Connectivity", cores: "Cores", cpu: "Processor", dpi: "Sensor DPI",
  formFactor: "Form factor", fov: "Field of view", gpu: "Graphics card", interface: "Interface",
  layout: "Layout", memory: "Memory", panel: "Panel type", radiatorSize: "Radiator size", ram: "Memory (RAM)",
  rating: "Rating", refreshRate: "Refresh rate", resolution: "Resolution", size: "Size", socket: "Socket",
  socketSupport: "Socket support", speed: "Speed", storage: "Storage", switchType: "Switch type",
  threads: "Threads", type: "Type", wattage: "Wattage", weightGrams: "Weight (grams)", wifiStandard: "Wi-Fi standard",
};

/** Falls back to a camelCase/snake_case/kebab-case-to-words formatter for any
 * attribute key not yet in the table above, so an unmapped key still reads as
 * words instead of a raw identifier. */
export function attributeLabel(name: string): string {
  const known = ATTRIBUTE_LABELS[name];
  if (known) return known;
  const words = name
    .replace(/[_-]+/g, " ")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  if (!words.length) return name;
  return words.map((word, index) => (index === 0
    ? word.charAt(0).toUpperCase() + word.slice(1).toLowerCase()
    : word.toLowerCase())).join(" ");
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
