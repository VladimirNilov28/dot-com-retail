import { expect, test } from "bun:test";
import { catalogCategories, catalogListing, catalogSuggestions, shippingOptions } from "./operations";
import { execute } from "./transport";
import { requestHive } from "./server-transport";
import { handleGuestRequest } from "./proxy";
import { parseJson } from "./scalars";

const config = { endpoint: "http://localhost:4063/graphql", origin: "http://localhost:3000" };
const variables = { input: { page: 0, size: 20, sort: "PRICE_ASC" } };
const body = '{"data":{"searchProducts":{"items":[{"id":"1","name":"Actual name","slug":"actual","averageRating":4.5,"ratingCount":3,"variants":[{"id":"2","price":9999999999999999999.99,"isActive":true}]}],"pageInfo":{"page":0,"size":20,"totalItems":1,"totalPages":1},"facets":{"categories":[],"attributes":[],"price":{"min":null,"max":null}}}}}';
const response = (source = body, headers = {}) => new Response(source, { headers: { "Content-Type": "application/json", ...headers } });
const request = (operation = catalogListing, input = variables, headers = {}) => new Request("http://localhost:3000/graphql", {
  method: "POST", headers: { "Content-Type": "application/json", ...headers },
  body: JSON.stringify({ query: operation.source, operationName: operation.name, variables: input }),
});

test("bounded registered listing retains exact money and selected response shape", async () => {
  const result = await execute(catalogListing, variables, { endpoint: config.endpoint, fetch: async () => response() });
  expect(result.status).toBe("success");
  expect(result.data.searchProducts.items[0].variants[0].price).toBe("9999999999999999999.99");
});
test("public server context omits all credentials and guest ceremony", async () => {
  const { result, setCookies } = await requestHive(catalogListing, variables, config,
    { kind: "authenticated", accessToken: "test-only-token", guestCookieHeader: "retail_guest_cart=test" },
    { fetch: async (_, options) => {
      expect([...options.headers.keys()].sort()).toEqual(["accept", "content-type"]);
      expect(options.credentials).toBe("omit");
      expect(options.cache).toBe("no-store");
      return response();
    } });
  expect(result.status).toBe("success");
  expect(setCookies).toEqual([]);
});
test("public proxy dispatch needs no guest header and strips incoming cookies", async () => {
  const result = await handleGuestRequest(request(catalogListing, variables, { Cookie: "private=owner" }), config,
    async (_, options) => {
      expect(options.headers.has("Cookie")).toBe(false);
      expect(options.headers.has("Origin")).toBe(false);
      expect(options.headers.has("Authorization")).toBe(false);
      return response();
    });
  expect(result.status).toBe(200);
  expect(result.headers.get("Cache-Control")).toBe("private, no-store");
  expect((await result.json()).data.searchProducts.items[0].name).toBe("Actual name");
});
test("public categories validate flat parent relationships and reject malformed selection", () => {
  expect(catalogCategories.decode({ categories: [{ id: "1", name: "Root", slug: "root", parent: null }] }).categories).toHaveLength(1);
  expect(() => catalogCategories.decode({ categories: [{ id: "1" }] })).toThrow();
});
test.each([
  { page: -1, size: 20, sort: "PRICE_ASC" },
  { page: 0, size: 1000, sort: "PRICE_ASC" },
  { page: 0, size: 20, sort: "RELEVANCE" },
  { page: 0, size: 20, sort: "PRICE_ASC", filters: { inStock: true } },
  { page: 0, size: 20, sort: "PRICE_ASC", filters: { categoryId: "bad" } },
  { page: 0, size: 20, sort: "NEWEST" },
  { page: 0, size: 20, sort: "PRICE_ASC", filters: { minPrice: "-1.00" } },
  { page: 0, size: 20, sort: "PRICE_ASC", filters: { attributes: [{ name: "", value: "x" }] } },
  { page: 0, size: 20, sort: "PRICE_ASC", filters: { attributes: Array(21).fill({ name: "colour", value: "black" }) } },
])("rejects unsupported listing input without dispatch", async (input) => {
  let calls = 0;
  const result = await handleGuestRequest(request(catalogListing, { input }), config,
    async () => { calls++; return response(); });
  expect(result.status).toBe(400);
  expect(calls).toBe(0);
});
test("accepts real search/sort/filter combinations and decodes facets/rating", async () => {
  const input = {
    page: 0, size: 20, sort: "RATING_DESC", query: "cable",
    filters: { categoryId: "3", minPrice: "5.00", maxPrice: "99.99", attributes: [{ name: "colour", value: "black" }] },
  };
  const result = await execute(catalogListing, { input }, { endpoint: config.endpoint, fetch: async () => response() });
  expect(result.status).toBe("success");
  expect(result.data.searchProducts.items[0].averageRating).toBe(4.5);
  expect(result.data.searchProducts.items[0].ratingCount).toBe(3);
  expect(result.data.searchProducts.facets.categories).toEqual([]);
  expect(result.data.searchProducts.facets.price).toEqual({ min: null, max: null });
});
test("suggestions validate a bounded, non-blank query and decode real fields only", () => {
  expect(() => catalogSuggestions.variables({ query: "" })).toThrow();
  expect(() => catalogSuggestions.variables({ query: "a".repeat(201) })).toThrow();
  expect(() => catalogSuggestions.variables({ query: "usb", limit: 11 })).toThrow();
  expect(catalogSuggestions.variables({ query: "usb" })).toEqual({ query: "usb" });
  expect(catalogSuggestions.decode({ productSearchSuggestions: [{ productId: "1", name: "USB cable", slug: "usb-cable" }] })
    .productSearchSuggestions).toHaveLength(1);
});
test("rejects arbitrary catalog documents and public bearer forwarding", async () => {
  const arbitrary = new Request("http://localhost:3000/graphql", { method: "POST",
    headers: { "Content-Type": "application/json" }, body: '{"operationName":"Products","query":"{ products { id } }"}' });
  expect((await handleGuestRequest(arbitrary, config)).status).toBe(400);
  expect((await handleGuestRequest(request(catalogListing, variables, { Authorization: "Bearer test" }), config)).status).toBe(403);
  expect((await handleGuestRequest(request(catalogListing, variables, { Origin: "http://foreign.invalid" }), config)).status).toBe(403);
});
test("public context cannot execute guest reads and guest proxy ceremony is preserved", async () => {
  expect((await requestHive(shippingOptions, {}, config, { kind: "public" })).result.status).toBe("failure");
  expect((await handleGuestRequest(request(shippingOptions, {}), config)).status).toBe(403);
});
test("public credentials returned by upstream are a protocol error", async () => {
  const { result } = await requestHive(catalogListing, variables, config, { kind: "public" },
    { fetch: async () => response(body, { "Set-Cookie": "retail_guest_cart=bad; Path=/graphql" }) });
  expect(result.status).toBe("failure");
  expect(result.error.kind).toBe("protocol");
});
test("HTTP-200 GraphQL errors and excessive item counts never become full public success", async () => {
  const result = await execute(catalogListing, variables, { endpoint: config.endpoint,
    fetch: async () => response(body.slice(0, -1) + ',"errors":[{"message":"private diagnostic"}]}') });
  expect(result.status).toBe("partial");
  expect(JSON.stringify(result)).not.toContain("private diagnostic");
  const selection = catalogListing.decode(parseJson(body).data);
  selection.searchProducts.items = Array(21).fill(selection.searchProducts.items[0]);
  expect(() => catalogListing.decode(selection)).toThrow();
});
