import { expect, test } from "bun:test";
import { clampText, productDescription } from "./seo.ts";

test("clampText keeps short text and cuts long text at a word boundary", () => {
  expect(clampText("  short   text ", 20)).toBe("short text");
  const result = clampText("word ".repeat(60), 160);
  expect(result.length).toBeLessThanOrEqual(160);
  expect(result.endsWith("word…")).toBe(true);
});

test("productDescription stays within 160 characters", () => {
  expect(productDescription("Laptop X", "x ".repeat(200)).length).toBeLessThanOrEqual(160);
  expect(productDescription("Laptop X", null)).toContain("Laptop X");
  expect(productDescription("Laptop X", "Fast and light.").startsWith("Fast and light. Buy Laptop X")).toBe(true);
});
