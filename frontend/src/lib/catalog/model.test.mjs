import { describe, expect, test } from "bun:test";
import { catalogDiscoveryHref, catalogHref, categoryIndex, formatPrice, listingState, productPrice } from "./model";
import { decimal } from "@/lib/graphql/scalars";

describe("catalog URL state", () => {
  test("uses one-based pages and URL-restorable views", () => {
    expect(listingState({})).toEqual({ page: 1, view: "grid" });
    expect(listingState({ page: "2", view: "list" })).toEqual({ page: 2, view: "list" });
    expect(catalogHref("laptops", 2, "list")).toBe("/catalog/laptops?page=2&view=list");
    expect(catalogHref("A/B")).toBe("/catalog/A%2FB");
    // /catalog is category discovery (#63 step 2); the bounded all-products
    // listing moved to its own static route.
    expect(catalogHref()).toBe("/catalog/all-products");
    expect(catalogHref(undefined, 2, "list")).toBe("/catalog/all-products?page=2&view=list");
    expect(catalogDiscoveryHref()).toBe("/catalog");
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
