import { describe, expect, test } from "bun:test";
import {
  catalogHref, categoryIndex, formatPrice, hasActiveFilters, listingState, productPrice, resultsHref, resultsState,
} from "./model";
import { decimal } from "@/lib/graphql/scalars";

describe("catalog URL state", () => {
  test("uses one-based pages and URL-restorable views", () => {
    expect(listingState({})).toEqual({ page: 1, view: "grid" });
    expect(listingState({ page: "2", view: "list" })).toEqual({ page: 2, view: "list" });
    expect(catalogHref("laptops", 2, "list")).toBe("/catalog/laptops?page=2&view=list");
    expect(catalogHref("A/B")).toBe("/catalog/A%2FB");
    // /catalog itself no longer renders a listing or discovery page (the
    // owner's revised megamenu decision); the bounded all-products listing
    // lives at this reserved static route.
    expect(catalogHref()).toBe("/catalog/all-products");
    expect(catalogHref(undefined, 2, "list")).toBe("/catalog/all-products?page=2&view=list");
  });
  test.each(["0", "-1", "2.5", "Infinity", "2147483648", "01", "", "1e2"])("rejects invalid page %s", (page) => {
    expect(listingState({ page })).toBeNull();
  });
  test("rejects ambiguous query arrays and invalid views", () => {
    expect(listingState({ page: ["1", "2"] })).toBeNull();
    expect(listingState({ view: ["grid"] })).toBeNull();
    expect(listingState({ view: "table" })).toBeNull();
  });
});

describe("shared results state (category pages, All products, search)", () => {
  test("defaults sort by query presence and round trips through resultsHref", () => {
    expect(resultsState({})).toEqual({ page: 1, view: "grid", sort: "PRICE_ASC", query: "", attributes: [] });
    expect(resultsState({ q: "cable" })).toEqual({ page: 1, view: "grid", sort: "RELEVANCE", query: "cable", attributes: [] });
    expect(resultsState({ q: "  cable  " }).query).toBe("cable");
  });
  test("rejects RELEVANCE without a query, inverted/negative prices and oversized input", () => {
    expect(resultsState({ sort: "RELEVANCE" })).toBeNull();
    expect(resultsState({ sort: "NEWEST", q: "x" })).toBeNull();
    expect(resultsState({ minPrice: "10", maxPrice: "5" })).toBeNull();
    expect(resultsState({ minPrice: "-1" })).toBeNull();
    expect(resultsState({ q: "x".repeat(201) })).toBeNull();
    expect(resultsState({ q: ["a", "b"] })).toBeNull();
    expect(resultsState({ "attr.color": ["red", "blue"] })).toBeNull();
    expect(resultsState({ "attr.": "red" })).toBeNull();
  });
  test("parses attribute filters, sorts them by name and bounds their count", () => {
    const state = resultsState({ "attr.color": "red", "attr.size": "M" });
    expect(state.attributes).toEqual([{ name: "color", value: "red" }, { name: "size", value: "M" }]);
    const many = Object.fromEntries(Array.from({ length: 21 }, (_, index) => [`attr.k${index}`, "v"]));
    expect(resultsState(many)).toBeNull();
  });
  test("resultsHref omits default values and resets pagination only for filter/sort/query changes", () => {
    const base = resultsState({ page: "3" });
    expect(resultsHref("/catalog/laptops", base, { view: "list" })).toBe("/catalog/laptops?page=3&view=list");
    expect(resultsHref("/catalog/laptops", base, { sort: "PRICE_DESC" })).toBe("/catalog/laptops?sort=PRICE_DESC");
    expect(resultsHref("/catalog/laptops", base, { minPrice: decimal("10") })).toBe("/catalog/laptops?minPrice=10");
    expect(resultsHref("/catalog/laptops", base, { minPrice: undefined })).toBe("/catalog/laptops");
    // Changing only the query does not itself change sort — RELEVANCE must be
    // selected explicitly, since it is otherwise undefined without a query.
    expect(resultsHref("/search", resultsState({}), { query: "cable" })).toBe("/search?sort=PRICE_ASC&q=cable");
  });
  test("hasActiveFilters only reports price/attribute filters, not sort/query/page/view", () => {
    expect(hasActiveFilters(resultsState({}))).toBe(false);
    expect(hasActiveFilters(resultsState({ q: "cable", page: "2" }))).toBe(false);
    expect(hasActiveFilters(resultsState({ minPrice: "5" }))).toBe(true);
    expect(hasActiveFilters(resultsState({ "attr.color": "red" }))).toBe(true);
  });
});

describe("flat category navigation", () => {
  const root = { id: "1", name: "Computers", slug: "computers", parent: null };
  const child = { id: "2", name: "Laptops", slug: "laptops", parent: { id: "1" } };
  const leaf = { id: "3", name: "Portable", slug: "portable", parent: { id: "2" } };
  test("builds ancestors and direct children without fetching or descendant expansion", () => {
    const index = categoryIndex([leaf, root, child]);
    expect(index.children()).toEqual([root]);
    expect(index.children("1")).toEqual([child]);
    expect(index.ancestors(leaf)).toEqual([root, child]);
    expect(index.bySlug.get("portable")).toEqual(leaf);
  });
  test("rejects cycles, orphan parents and duplicated identities instead of hiding them", () => {
    expect(() => categoryIndex([child])).toThrow();
    expect(() => categoryIndex([root, root])).toThrow();
    expect(() => categoryIndex([root, { ...child, slug: root.slug }])).toThrow();
    expect(() => categoryIndex([{ ...root, parent: { id: "2" } }, child])).toThrow();
  });
});

describe("exact EUR display, not stock inference", () => {
  test.each([
    ["0.00", "€0.00"], ["12.3", "€12.30"], ["1.005", "€1.01"], ["1.004", "€1.00"],
    ["1e3", "€1,000.00"], ["1e-3", "€0.00"], ["999999999999999999999.12", "€999,999,999,999,999,999,999.12"],
  ])("formats %s without floating-point money", (source, output) => {
    expect(formatPrice(decimal(source))).toBe(output);
  });
  test("rejects negative and unreasonable prices", () => {
    expect(() => formatPrice(decimal("-1"))).toThrow();
    expect(() => formatPrice(decimal("1e99999"))).toThrow();
  });
  function product(prices, active = true) {
    return { id: "1", name: "Real product", slug: "real", variants: prices.map((value, index) => ({
      id: String(index), price: decimal(value), isActive: active,
    })) };
  }
  test("uses the active minimum, distinguishes equal prices and never fabricates zero", () => {
    expect(productPrice(product(["100", "99.90"]))).toBe("From €99.90");
    expect(productPrice(product(["79.90", "79.900"]))).toBe("€79.90");
    expect(productPrice(product([]))).toBe("Price unavailable");
    expect(productPrice(product(["12"], false))).toBe("Price unavailable");
    const mixed = product(["20"]);
    mixed.variants.push({ id: "2", price: decimal("1"), isActive: false });
    expect(productPrice(mixed)).toBe("€20.00");
  });
});
