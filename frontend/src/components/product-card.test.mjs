import { expect, test } from "bun:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { ProductCard } from "./product-card";
import { catalogListing, homeCategoryProducts } from "@/lib/graphql/operations";

const base = { id: "1", name: "Card", slug: "card", averageRating: null, ratingCount: 0, variants: [{ id: "2", price: "9.99", isActive: true }] };
const html = (product) => renderToStaticMarkup(createElement(ProductCard, { product }));
const image = { url: "/media/products/fdb0541c-1203-4a01-ad5e-ee900df3734e.png", altText: "Front", width: 640, height: 480 };

test("card shows the first available image", () => {
  expect(html({ ...base, images: [image] })).toContain('src="/media/products/fdb0541c-1203-4a01-ad5e-ee900df3734e.png"');
});
test("empty or missing (stale cached) media renders the neutral fallback without crashing", () => {
  for (const images of [[], undefined]) {
    const markup = html({ ...base, images });
    expect(markup).toContain("Image unavailable");
    expect(markup).not.toContain("<img");
  }
});
test("listing and home decoders keep images and reject payloads that omit them", () => {
  const item = { ...base, images: [image] };
  const page = { pageInfo: { page: 0, size: 4, totalItems: 1, totalPages: 1 } };
  expect(homeCategoryProducts.decode({ searchProducts: { items: [item], ...page } }).searchProducts.items[0].images).toEqual([image]);
  expect(() => homeCategoryProducts.decode({ searchProducts: { items: [base], ...page } })).toThrow();
  expect(catalogListing.decode({ searchProducts: { items: [{ ...item, images: [] }], pageInfo: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
    facets: { categories: [], attributes: [], price: { min: null, max: null } } } }).searchProducts.items[0].images).toEqual([]);
});
