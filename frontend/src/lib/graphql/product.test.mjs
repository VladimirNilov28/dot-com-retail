import { expect, test } from "bun:test";
import { productDetail } from "./operations";
import { execute } from "./transport";
import { requestHive } from "./server-transport";
import { parseJson } from "./scalars";

const source = '{"product":{"id":"1","name":"Real product","slug":"real","description":null,"categories":[],"averageRating":null,"ratingCount":0,"variants":[{"id":"2","sku":"REAL-2","price":9999999999999999999.99,"attributes":{"cores":8},"isActive":true,"barcode":null,"weightGrams":null}]}}';
const config = { endpoint: "http://localhost:4002/graphql", origin: "http://localhost:3000" };
const response = (body) => new Response(body, { headers: { "Content-Type": "application/json" } });

test("detail decoder keeps exact money, lossless specifications and optional fields", () => {
  const data = productDetail.decode(parseJson(source));
  expect(data.product.variants[0].price).toBe("9999999999999999999.99");
  expect(data.product.variants[0].attributes.cores.value).toBe("8");
  expect(data.product.description).toBeNull();
  expect(data.product.averageRating).toBeNull();
  expect(data.product.ratingCount).toBe(0);
});
test("a successful null product is distinct from an upstream failure", async () => {
  expect(productDetail.decode({ product: null })).toEqual({ product: null });
  const result = await execute(productDetail, { slug: "real" }, { endpoint: config.endpoint,
    fetch: async () => response('{"data":{"product":null},"errors":[{"message":"private diagnostic","extensions":{"errorType":"INTERNAL"}}]}') });
  expect(result.status).not.toBe("success");
  expect(JSON.stringify(result)).not.toContain("private diagnostic");
});
test("detail read is public and does not forward caller credentials", async () => {
  const { result } = await requestHive(productDetail, { slug: "real" }, config,
    { kind: "authenticated", accessToken: "test-only", guestCookieHeader: "retail_guest_cart=test" },
    { fetch: async (_, options) => {
      expect([...options.headers.keys()].sort()).toEqual(["accept", "content-type"]);
      expect(options.credentials).toBe("omit");
      expect(options.cache).toBe("no-store");
      return response(`{"data":${source}}`);
    } });
  expect(result.status).toBe("success");
  expect(productDetail.source).not.toMatch(/inventory|warehouse/);
});
test.each(["", "a".repeat(256)])("invalid slug variables cannot dispatch", (slug) => {
  expect(() => productDetail.variables({ slug })).toThrow();
});
test("unexpected variables and schema-invalid null prices fail explicitly", () => {
  expect(() => productDetail.variables({ slug: "real", id: "1" })).toThrow();
  const data = parseJson(source);
  data.product.variants[0].price = null;
  expect(() => productDetail.decode(data)).toThrow();
});
test("real ratings decode; malformed or duplicate variant identities do not silently render", () => {
  const data = parseJson(source);
  data.product.averageRating = 4.5;
  data.product.ratingCount = 2;
  expect(productDetail.decode(data).product.averageRating).toBe(4.5);
  data.product.variants.push(data.product.variants[0]);
  expect(() => productDetail.decode(data)).toThrow();
});
