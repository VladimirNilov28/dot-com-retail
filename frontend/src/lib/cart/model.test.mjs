import { expect, test } from "bun:test";
import { lineIssue, toCartView, cartErrorMessage } from "./model";
import { guestCartAdd, guestCartView, myCartRemove, variantAvailability } from "../graphql/operations";

const raw = (variant, quantity = 2) => ({
  id: "1", totals: { subtotal: "39.98", currency: "EUR" },
  items: [{ id: "5", quantity, subtotal: "39.98", productVariant: {
    id: "9", sku: "SKU-9", price: "19.99", attributes: { color: "Black", size: "M" }, isActive: true,
    availability: "AVAILABLE", purchasableLimit: 10,
    product: { name: "Lamp", slug: "lamp", images: [{ url: "/media/products/x.jpg", altText: "", width: 1, height: 1 }] },
    ...variant } }],
});

test("cart view keeps server money and derives thumbnail, options and count", () => {
  const view = toCartView(raw({}));
  expect(view.subtotal).toBe("39.98");
  expect(view.count).toBe(2);
  expect(view.lines[0].image?.url).toBe("/media/products/x.jpg");
  expect(view.lines[0].options).toBe("Color: Black · Size: M");
  expect(view.lines[0].issue).toBeNull();
});

test("a line without images falls back to no image, never a made-up one", () => {
  expect(toCartView(raw({ product: { name: "Lamp", slug: "lamp", images: [] } })).lines[0].image).toBeNull();
});

test("stock and activity problems are described honestly", () => {
  expect(lineIssue({ active: false, quantity: 1, availability: "AVAILABLE", limit: 5 })).toBe("No longer available");
  expect(lineIssue({ active: true, quantity: 1, availability: "OUT_OF_STOCK", limit: 0 })).toBe("Out of stock");
  expect(lineIssue({ active: true, quantity: 4, availability: "AVAILABLE", limit: 3 })).toBe("Only 3 available");
  expect(lineIssue({ active: true, quantity: 40, availability: "AVAILABLE", limit: 10 })).toBeNull();
  expect(lineIssue({ active: true, quantity: 1, availability: null, limit: null })).toBeNull();
});

test("a failed read never claims anything about the cart contents", () => {
  expect(cartErrorMessage("read-unavailable")).not.toContain("has not changed");
});

test("failures never claim the cart changed", () => {
  expect(cartErrorMessage("transport")).toContain("has not changed");
});

test("cart operations validate identifiers and quantities before dispatch", () => {
  expect(guestCartAdd.variables({ input: { productVariantId: "9", quantity: 1 } })).toEqual({ input: { productVariantId: "9", quantity: 1 } });
  for (const input of [{ productVariantId: "9", quantity: 0 }, { productVariantId: "x", quantity: 1 },
    { productVariantId: "9", quantity: 100 }, { productVariantId: "9", quantity: 1, price: "0" }]) {
    expect(() => guestCartAdd.variables({ input })).toThrow();
  }
  expect(() => myCartRemove.variables({ id: "0" })).toThrow();
  expect(() => guestCartView.variables({ x: 1 })).toThrow();
});

test("availability decoder keeps unknown distinct from out of stock", () => {
  const decoded = variantAvailability.decode({ product: { slug: "lamp", variants: [
    { id: "1", availability: null, purchasableLimit: null },
    { id: "2", availability: "OUT_OF_STOCK", purchasableLimit: 0 },
  ] } });
  expect(decoded.product.variants.map((v) => v.availability)).toEqual([null, "OUT_OF_STOCK"]);
  expect(() => variantAvailability.decode({ product: { slug: "l", variants: [{ id: "1", availability: "MAYBE", purchasableLimit: 1 }] } })).toThrow();
  expect(() => variantAvailability.decode({ product: { slug: "l", variants: [{ id: "1", availability: "AVAILABLE", purchasableLimit: 11 }] } })).toThrow();
});
