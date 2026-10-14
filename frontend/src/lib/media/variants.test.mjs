import { expect, test } from "bun:test";
import { variantSrcSet, variantUrl, variantWidth } from "./variants";

const url = "/media/products/fdb0541c-1203-4a01-ad5e-ee900df3734e.jpg";

test("variant URLs add a size query", () => {
  expect(variantUrl(url, "thumb")).toBe(`${url}?size=thumb`);
});
test("variant widths match the server's bounded, never-upscaled output", () => {
  expect(variantWidth({ width: 3000, height: 2000 }, "thumb")).toBe(320);
  expect(variantWidth({ width: 600, height: 1200 }, "thumb")).toBe(160);
  expect(variantWidth({ width: 640, height: 480 }, "medium")).toBe(640);
  expect(variantWidth({ width: 2400, height: 1600 }, "full")).toBe(2000);
});
test("srcSet lists real widths and drops duplicates for small originals", () => {
  expect(variantSrcSet({ url, width: 3000, height: 2000 }, ["thumb", "medium"]))
    .toBe(`${url}?size=thumb 320w, ${url}?size=medium 800w`);
  expect(variantSrcSet({ url, width: 300, height: 300 }, ["thumb", "medium", "full"])).toBe(`${url}?size=thumb 300w`);
});
