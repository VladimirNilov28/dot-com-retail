import { describe, expect, test } from "bun:test";
import { decimal, parseJson } from "@/lib/graphql/scalars";
import { selectVariant, variantHref, variantLabel, variantSpecifications, variantOptionLabels } from "./model";

const variant = (id, price, attributes = {}, isActive = true) => ({
  id, price: decimal(price), attributes, isActive, sku: `SKU-${id}`, barcode: null, weightGrams: null,
});
const product = (variants) => ({ variants });

describe("real product variant selection", () => {
  const variants = [variant("10", "99.00", { ram: "32GB" }), variant("2", "99", { ram: "16GB" }),
    variant("1", "1.00", {}, false), variant("3", "149")];
  test("default uses exact lowest active price and numeric ID, not response order", () => {
    expect(selectVariant(product(variants), {}).selected.id).toBe("2");
    expect(selectVariant(product([...variants].reverse()), {}).selected.id).toBe("2");
    expect(selectVariant(product([variant("1", "9999999999999999999.99"),
      variant("2", "9999999999999999999.98")]), {}).selected.id).toBe("2");
  });
  test("explicit choice supplies one record for price, SKU, and specifications", () => {
    const result = selectVariant(product(variants), { variant: "10" });
    expect(result).toEqual({ selected: variants[0], recovered: false });
    expect(result.selected.price).toBe("99.00");
    expect(result.selected.sku).toBe("SKU-10");
    expect(variantSpecifications(result.selected)).toContainEqual(["Memory (RAM)", "32GB"]);
  });
  test("inactive variants remain inspectable, not stock signals", () => {
    expect(selectVariant(product(variants), { variant: "1" }).selected.isActive).toBe(false);
    expect(selectVariant(product([variants[2]]), {}).selected).toBeNull();
    expect(selectVariant(product([variants[2]]), { variant: "1" }).selected).toBe(variants[2]);
  });
  test.each(["", "0", "-1", "01", "2.0", "Infinity", "999", "foreign", ["2", "10"], ["2"]].map((value) => [value]))(
    "invalid, ambiguous, removed and foreign IDs recover visibly: %s", (value) => {
      expect(selectVariant(product(variants), { variant: value })).toEqual({ selected: variants[1], recovered: true });
    });
  test("single, absent and removed variants have deterministic recovery", () => {
    expect(selectVariant(product([variants[0]]), {}).selected).toBe(variants[0]);
    expect(selectVariant(product([]), {})).toEqual({ selected: null, recovered: false });
    expect(selectVariant(product([]), { variant: "2" })).toEqual({ selected: null, recovered: true });
    expect(selectVariant(product(variants.filter(({ id }) => id !== "2")), { variant: "2" }))
      .toEqual({ selected: variants[0], recovered: true });
  });
});

describe("variant links and presentation", () => {
  test("links encode slugs and replace ambiguous variant parameters without losing other parameters", () => {
    expect(variantHref("A/B", "2", { variant: ["10", "2"], source: "search" }))
      .toBe("/products/A%2FB?source=search&variant=2");
    expect(variantHref("real", null, { variant: "bad" })).toBe("/products/real");
    expect(() => variantHref("real", "01")).toThrow();
  });
  test("labels use real readable attributes and disambiguating SKU", () => {
    expect(variantLabel(variant("2", "99", { ram: "16GB", color: "Silver" })))
      .toContain("Memory (RAM): 16GB");
    expect(variantLabel(variant("2", "99"))).toBe("SKU-2");
  });
  test("option labels prioritize actual differing specifications rather than arbitrary first keys", () => {
    const variants = [variant("1", "99", { backlight: true, color: "Silver", ram: "16GB", storage: "512GB" }),
      variant("2", "149", { backlight: true, color: "Silver", ram: "16GB", storage: "1TB" })];
    const labels = variantOptionLabels(variants);
    expect(labels).toEqual(["Storage"]);
    expect(variantLabel(variants[1], labels)).toBe("Storage: 1TB — SKU-2");
    expect(variantLabel(variants[0], [])).toBe("SKU-1");
  });
  test("JSON values render without object coercion, lost precision or fabricated missing values", () => {
    const attributes = parseJson('{"cores":8,"future_key":true,"ports":["USB-C","HDMI"],"detail":{"x":9999999999999999999},"empty":null}');
    const specs = variantSpecifications({ ...variant("1", "0", attributes), weightGrams: 0, barcode: "real-code" });
    expect(specs).toContainEqual(["Cores", "8"]);
    expect(specs).toContainEqual(["Future key", "Yes"]);
    expect(specs).toContainEqual(["Ports", "USB-C, HDMI"]);
    expect(specs).toContainEqual(["Detail", "X: 9999999999999999999"]);
    expect(specs).toContainEqual(["Empty", "Not specified"]);
    expect(specs).toContainEqual(["Weight (grams)", "0"]);
    expect(specs).toContainEqual(["Barcode", "real-code"]);
    expect(specs.flat().join(" ")).not.toContain("[object Object]");
  });
});
