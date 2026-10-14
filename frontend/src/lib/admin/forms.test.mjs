import { describe, expect, test } from "bun:test";
import { attributes, blockedParents, categoryTree, importFormat, price, slug } from "./forms.ts";

describe("admin form helpers (#80)", () => {
  test("price accepts two decimals and rejects zero, negatives and extra precision", () => {
    expect(price("12.50")).toBe("12.50");
    expect(price("3,9")).toBe("3.9");
    expect(price("0", true)).toBe("0");
    expect(() => price("0")).toThrow("greater than zero");
    expect(() => price("-1")).toThrow();
    expect(() => price("1.999")).toThrow("two decimals");
  });

  test("slug enforces the catalogue pattern", () => {
    expect(slug("trail-shoe-2")).toBe("trail-shoe-2");
    expect(() => slug("Bad Slug")).toThrow();
    expect(() => slug("a--b")).toThrow();
  });

  test("attributes collects key/value rows, skips blanks and rejects duplicates", () => {
    const form = new FormData();
    form.set("attr-key-0", "color");
    form.set("attr-value-0", "red");
    form.set("attr-key-1", "");
    form.set("attr-value-1", "");
    expect(attributes(form)).toEqual({ color: "red" });
    form.set("attr-key-1", "color");
    form.set("attr-value-1", "blue");
    expect(() => attributes(form)).toThrow("listed twice");
    const orphan = new FormData();
    orphan.set("attr-key-0", "");
    orphan.set("attr-value-0", "x");
    expect(() => attributes(orphan)).toThrow("required");
  });

  const categories = [
    { id: "1", name: "Shoes", slug: "shoes", parent: null },
    { id: "2", name: "Trail", slug: "trail", parent: { id: "1" } },
    { id: "3", name: "Mud", slug: "mud", parent: { id: "2" } },
    { id: "4", name: "Bags", slug: "bags", parent: null },
    { id: "5", name: "Orphan", slug: "orphan", parent: { id: "99" } },
  ];

  test("categoryTree orders depth-first with depth and lifts orphans to the root", () => {
    expect(categoryTree(categories).map((row) => `${row.depth}:${row.slug}`)).toEqual(["0:bags", "0:orphan", "0:shoes", "1:trail", "2:mud"]);
  });

  test("blockedParents contains the category and all its descendants", () => {
    expect([...blockedParents(categories, "1")].sort()).toEqual(["1", "2", "3"]);
    expect([...blockedParents(categories, "4")]).toEqual(["4"]);
  });

  test("importFormat prefers the explicit choice, then the extension", () => {
    expect(importFormat("x.csv", "JSON")).toBe("JSON");
    expect(importFormat("x.JSON", "")).toBe("JSON");
    expect(importFormat("x.csv", null)).toBe("CSV");
    expect(() => importFormat("x.txt", "")).toThrow();
  });
});
