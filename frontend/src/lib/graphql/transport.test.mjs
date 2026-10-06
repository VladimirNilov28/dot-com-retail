import { describe, expect, test } from "bun:test";
import { parse, stringify, isLosslessNumber } from "lossless-json";
import { execute, latestRequest } from "./transport";
import { shippingOptions, guestCart, guestOrder } from "./operations";
import { decimal, uuid, json, parseJson, serializeJson } from "./scalars";
import { guestCookies, hiveConfig, requestHive, responseCookies } from "./server-transport";
import { handleGuestRequest } from "./proxy";

const config = { endpoint: "http://localhost:4002/graphql", origin: "http://localhost:3000" };
const shipping = '{"data":{"checkoutShippingOptions":[{"method":"STANDARD","charge":4.99,"currency":"EUR","estimate":"Development","supportedCountries":["EE"]}]}}';
const response = (body = shipping, status = 200, headers = {}) => new Response(body, {
  status, headers: { "Content-Type": "application/json", ...headers },
});
const run = (fetcher, options = {}, operation = shippingOptions, variables = {}) =>
  execute(operation, variables, { endpoint: config.endpoint, fetch: fetcher, ...options });
const proxyRequest = (operation = shippingOptions, variables = {}, headers = {}) =>
  new Request("http://localhost:3000/graphql", {
    method: "POST", headers: { Origin: config.origin, "X-Guest-Cart-Request": "1", "Content-Type": "application/json", ...headers },
    body: JSON.stringify({ query: operation.source, operationName: operation.name, variables }),
  });

describe("schema-derived operations and exact scalars", () => {
  test("uses exact money and preserves trailing zeros without a JS number", async () => {
    const result = await run(async () => response(shipping.replace("4.99", "999999999999999999999.123400")));
    expect(result.status).toBe("success");
    expect(result.data.checkoutShippingOptions[0].charge).toBe("999999999999999999999.123400");
    expect(decimal(parse("0.00"))).toBe("0.00");
    expect(() => decimal(4.99)).toThrow();
    expect(() => decimal("NaN")).toThrow();
    expect(() => decimal("01.00")).toThrow();
  });
  test("validates UUIDs and losslessly round trips nested JSON", () => {
    expect(uuid("01234567-89ab-cdef-0123-456789abcdef")).toBe("01234567-89ab-cdef-0123-456789abcdef");
    expect(() => uuid("not-a-uuid")).toThrow();
    const value = json(parse('{"ram":32,"precise":90071992547409931234,"decimal":0.0100,"nested":[null,true,"ok"]}'));
    expect(isLosslessNumber(value.precise)).toBe(true);
    expect(stringify(value)).toBe('{"ram":32,"precise":90071992547409931234,"decimal":0.0100,"nested":[null,true,"ok"]}');
    expect(serializeJson(value)).toBe(stringify(value));
  });
  test("plain JSON marker fields cannot spoof money or alter JSON serialization", () => {
    const raw = parseJson('{"isLosslessNumber":true,"value":"4.99","nested":{"toString":"normal data"}}');
    expect(() => decimal(raw)).toThrow();
    expect(serializeJson(json(raw))).toBe('{"isLosslessNumber":true,"value":"4.99","nested":{"toString":"normal data"}}');
    expect(() => json(new Date())).toThrow();
    expect(() => serializeJson(Infinity)).toThrow();
    expect(() => serializeJson(Array(2))).toThrow();
  });
  test("validates variables before sending, including mutually exclusive order references", async () => {
    let calls = 0;
    const fetcher = async () => { calls++; return response(); };
    for (const [operation, variables] of [
      [shippingOptions, { countryCode: "bogus" }],
      [shippingOptions, { unexpected: "value" }],
      [guestCart, { ownerId: "2" }],
      [guestOrder, {}],
      [guestOrder, { publicId: "bad" }],
      [guestOrder, { publicId: "01234567-89ab-cdef-0123-456789abcdef", requestId: "01234567-89ab-cdef-0123-456789abcdef" }],
    ]) {
      expect((await run(fetcher, {}, operation, variables)).error.kind).toBe("validation");
    }
    expect(calls).toBe(0);
  });
  test("decodes an actual selected guest-cart shape including exact JSON and bounded Int", async () => {
    const body = '{"data":{"guestCart":{"id":"1","expiresAt":"2026-11-01T00:00:00Z","totals":{"subtotal":19.98,"currency":"EUR"},"items":[{"id":"2","quantity":2,"subtotal":19.98,"productVariant":{"id":"3","sku":"fixture","price":9.99,"attributes":{"ram":32},"isActive":true}}]}}}';
    const result = await run(async () => response(body), {}, guestCart);
    expect(result.status).toBe("success");
    expect(result.data.guestCart.items[0].quantity).toBe(2);
    expect(result.data.guestCart.items[0].productVariant.price).toBe("9.99");
    expect(stringify(result.data.guestCart.items[0].productVariant.attributes)).toBe('{"ram":32}');
    expect((await run(async () => response(body.replace('"quantity":2', '"quantity":2147483648')), {}, guestCart)).error.kind).toBe("protocol");
  });
});

describe("explicit error and partial-data behavior", () => {
  test.each([
    ["BAD_REQUEST", "validation"], ["ValidationError", "validation"],
    ["UNAUTHENTICATED", "unauthenticated"], ["FORBIDDEN", "forbidden"],
    ["NOT_FOUND", "not-found"], ["GUEST_CART_UNAVAILABLE", "not-found"],
    ["CHECKOUT_QUOTE_CHANGED", "conflict"], ["CONFLICT", "conflict"],
    ["INTERNAL", "unavailable"], ["UNRECOGNIZED", "graphql"],
  ])("classifies HTTP-200 %s without exposing raw messages", async (code, kind) => {
    const result = await run(async () => response(JSON.stringify({
      errors: [{ message: "secret SQL/token details", extensions: { errorType: code } }],
    })));
    expect(result.status).toBe("failure");
    expect(result.error.kind).toBe(kind);
    expect(JSON.stringify(result)).not.toContain("secret");
  });
  test("recognizes Hive's HTTP-200 nested downstream 401", async () => {
    const result = await run(async () => response('{"data":null,"errors":[{"message":"hidden","extensions":{"code":"DOWNSTREAM_SERVICE_ERROR"}},{"message":"hidden","extensions":{"code":"SUBREQUEST_HTTP_ERROR","http":{"status":401}}}]}'));
    expect(result.error.kind).toBe("unauthenticated");
    expect(result.error.httpStatus).toBe(401);
    expect(result.error.retryable).toBe(false);
  });
  test("unknown extension codes cannot leak private diagnostic text", async () => {
    const result = await run(async () => response('{"errors":[{"message":"private","extensions":{"code":"PRIVATE_DIAGNOSTIC_VALUE"}}]}'));
    expect(result.error.kind).toBe("graphql");
    expect(result.error.codes).toBeUndefined();
    expect(JSON.stringify(result)).not.toContain("PRIVATE_DIAGNOSTIC_VALUE");
  });
  test("partial selected data is explicit, never success-shaped", async () => {
    const result = await run(async () => response('{"data":{"guestCart":null},"errors":[{"message":"hidden","extensions":{"errorType":"GUEST_CART_UNAVAILABLE"}}]}'), {}, guestCart);
    expect(result.status).toBe("partial");
    expect(result.data).toEqual({ guestCart: null });
    expect(result.error.kind).toBe("not-found");
  });
  test.each([400, 401, 403, 404, 409, 429, 500, 503])("handles HTTP %i independently of its body", async (status) => {
    const result = await run(async () => response("internal HTML", status));
    expect(result.status).toBe("failure");
    expect(result.error.httpStatus).toBe(status);
    expect(JSON.stringify(result)).not.toContain("internal HTML");
  });
  test.each(["not JSON", "[]", "{}", '{"data":{}}', '{"data":null}', '{"errors":[]}', '{"errors":[{}]}', '{"data":{"checkoutShippingOptions":null}}'])(
    "rejects malformed response %s", async (body) => {
      expect((await run(async () => response(body))).error.kind).toBe("protocol");
    },
  );
  test("rejects wrong content type, oversized bodies and invalid decimal fields", async () => {
    expect((await run(async () => response(shipping, 200, { "Content-Type": "text/html" }))).error.kind).toBe("protocol");
    expect((await run(async () => response(" ".repeat(1_048_577)))).error.kind).toBe("protocol");
    expect((await run(async () => response(shipping.replace("4.99", '"bad"')))).error.kind).toBe("protocol");
  });
  test("network failure is safe and never automatically retried", async () => {
    let calls = 0;
    const fetcher = async () => { calls++; throw new TypeError("private network detail"); };
    const result = await run(fetcher);
    expect(result.error.kind).toBe("transport");
    expect(result.error.retryable).toBe(true);
    const mutation = { ...shippingOptions, kind: "mutation" };
    expect((await run(fetcher, {}, mutation)).error.retryable).toBe(false);
    expect(calls).toBe(2);
  });
  test("a failed response-body connection remains a retryable transport failure", async () => {
    const result = await run(async () => new Response(new ReadableStream({
      start(controller) { controller.error(new TypeError("private connection details")); },
    }), { headers: { "Content-Type": "application/json" } }));
    expect(result.error.kind).toBe("transport");
    expect(result.error.retryable).toBe(true);
    expect(JSON.stringify(result)).not.toContain("private connection");
  });
  test("rejects deep JSON before recursive parsing without confusing quoted brackets", async () => {
    expect(parseJson('{"text":"[\\\\\\"{}]"}')).toEqual({ text: '[\\"{}]' });
    const deeplyNested = "[".repeat(65) + "null" + "]".repeat(65);
    expect(() => parseJson(deeplyNested)).toThrow();
    expect((await run(async () => response(deeplyNested))).error.kind).toBe("protocol");
  });
});

describe("bounded lifetime and stale-result protection", () => {
  test("times out even if an adapter ignores cancellation", async () => {
    let signal;
    const result = await run(async (_, options) => { signal = options.signal; return new Promise(() => {}); }, { timeoutMs: 5 });
    expect(result.error.kind).toBe("timeout");
    expect(signal.aborted).toBe(true);
  });
  test("bounds response body reads and cancels stalled streams", async () => {
    let cancelled = false;
    const result = await run(async () => new Response(new ReadableStream({
      cancel() { cancelled = true; },
    }), { headers: { "Content-Type": "application/json" } }), { timeoutMs: 5 });
    expect(result.error.kind).toBe("timeout");
    expect(cancelled).toBe(true);
  });
  test("pre-abort and in-flight abort are distinct from timeout", async () => {
    const controller = new AbortController();
    controller.abort();
    let calls = 0;
    expect((await run(async () => { calls++; return response(); }, { signal: controller.signal })).error.kind).toBe("cancelled");
    expect(calls).toBe(0);
    const pending = new AbortController();
    const request = run(async () => new Promise(() => {}), { signal: pending.signal });
    pending.abort();
    expect((await request).error.kind).toBe("cancelled");
  });
  test("rejects unbounded or invalid timeout configuration", async () => {
    for (const timeoutMs of [0, -1, Infinity, 30_001, 1.5]) {
      expect((await run(async () => response(), { timeoutMs })).error.kind).toBe("configuration");
    }
  });
  test("does not replay a timed-out mutation", async () => {
    let calls = 0;
    const result = await run(async () => { calls++; return new Promise(() => {}); },
      { timeoutMs: 5 }, { ...shippingOptions, kind: "mutation" });
    expect(result.error.kind).toBe("timeout");
    expect(result.error.retryable).toBe(false);
    expect(calls).toBe(1);
  });
  test("old responses cannot overwrite newer results even when cancellation is ignored", async () => {
    const latest = latestRequest();
    let finish;
    let firstSignal;
    const first = latest.run((signal) => {
      firstSignal = signal;
      return new Promise((resolve) => { finish = resolve; });
    });
    expect(await latest.run(async () => ({ status: "success", data: "new" }))).toEqual({ status: "success", data: "new" });
    expect(firstSignal.aborted).toBe(true);
    finish({ status: "success", data: "old" });
    expect((await first).error.kind).toBe("stale");
    const next = latest.run(async () => ({ status: "success", data: "cancelled generation" }));
    latest.cancel();
    expect((await next).error.kind).toBe("stale");
  });
});

describe("request isolation and same-origin guest boundary", () => {
  test("requires explicit valid server configuration", () => {
    expect(hiveConfig({ HIVE_GRAPHQL_URL: config.endpoint, STOREFRONT_ORIGIN: config.origin })).toEqual(config);
    for (const environment of [
      {}, { HIVE_GRAPHQL_URL: "http://user:secret@localhost/graphql", STOREFRONT_ORIGIN: config.origin },
      { HIVE_GRAPHQL_URL: config.endpoint, STOREFRONT_ORIGIN: "http://example.com" },
      { HIVE_GRAPHQL_URL: config.endpoint, STOREFRONT_ORIGIN: config.origin + "/" },
    ]) expect(() => hiveConfig(environment)).toThrow();
  });
  test("selects only guest cookies and rejects duplicate credentials", () => {
    expect(guestCookies("other=secret; retail_guest_cart=one; retail_guest_orders=two")).toBe("retail_guest_cart=one; retail_guest_orders=two");
    expect(() => guestCookies("retail_guest_cart=one; retail_guest_cart=two")).toThrow();
    expect(() => guestCookies("retail_guest_cart")).toThrow();
    expect(guestCookies("retail_guest_cartX")).toBe("");
  });
  test("concurrent private requests carry only their own cookie and always no-store", async () => {
    const seen = [];
    const fetcher = async (_, options) => {
      seen.push(options);
      await new Promise((resolve) => setTimeout(resolve, 2));
      return response('{"data":{"guestCart":null}}');
    };
    const results = await Promise.all(["one", "two"].map((value) => requestHive(guestCart, {}, config, {
      kind: "guest", cookieHeader: `other=secret; retail_guest_cart=${value}`,
    }, { fetch: fetcher })));
    expect(results.every(({ result }) => result.status === "success")).toBe(true);
    expect(seen.map((options) => options.headers.get("Cookie"))).toEqual(["retail_guest_cart=one", "retail_guest_cart=two"]);
    for (const options of seen) {
      expect(options.cache).toBe("no-store");
      expect(options.credentials).toBe("omit");
      expect(options.redirect).toBe("error");
      expect(options.headers.get("Origin")).toBe(config.origin);
      expect(options.headers.get("X-Guest-Cart-Request")).toBe("1");
      expect(options.headers.has("Authorization")).toBe(false);
    }
    const third = await requestHive(guestCart, {}, config, { kind: "guest" }, { fetch: fetcher });
    expect(third.result.status).toBe("success");
    expect(seen[2].headers.has("Cookie")).toBe(false);
  });
  test("SSR returns Set-Cookie separately and the proxy preserves multiple safe fields", async () => {
    const headers = new Headers({ "Content-Type": "application/json" });
    headers.append("Set-Cookie", "retail_guest_cart=one; Path=/graphql; HttpOnly; SameSite=Lax");
    headers.append("Set-Cookie", "retail_guest_orders=two; Path=/graphql; HttpOnly; SameSite=Lax");
    const fetcher = async () => new Response('{"data":{"guestCart":null}}', { headers });
    expect((await requestHive(guestCart, {}, config, { kind: "guest" }, { fetch: fetcher })).setCookies).toHaveLength(2);
    const result = await handleGuestRequest(proxyRequest(guestCart), config, fetcher);
    expect(result.headers.getSetCookie()).toHaveLength(2);
    expect(result.headers.get("Cache-Control")).toBe("private, no-store");
    expect((await run(async () => result, {}, guestCart)).status).toBe("success");
  });
  test("rejects unsafe response cookie attributes", () => {
    for (const cookie of [
      "unrelated=secret; Path=/graphql; HttpOnly; SameSite=Lax",
      "retail_guest_cart=one; Path=/; HttpOnly; SameSite=Lax",
      "retail_guest_cart=one; Path=/graphql; SameSite=Lax",
      "retail_guest_cart=one; Domain=localhost; Path=/graphql; HttpOnly; SameSite=Lax",
      "retail_guest_cart=one; Domain = localhost; Path=/graphql; HttpOnly; SameSite=Lax",
      "retail_guest_cart=one; Path=/graphql; Path=/; HttpOnly; SameSite=Lax",
      "retail_guest_cart=one; Path=/graphql; HttpOnly; SameSite=Lax; SameSite=None",
    ]) expect(() => responseCookies(new Headers({ "Set-Cookie": cookie }), false)).toThrow();
    expect(() => responseCookies(new Headers({ "Set-Cookie": "retail_guest_cart=one; Path=/graphql; HttpOnly; SameSite=Lax" }), true)).toThrow();
  });
  test("never forwards arbitrary query text, privileged headers or foreign Origin", async () => {
    let calls = 0;
    const fetcher = async () => { calls++; return response(); };
    for (const headers of [
      { Origin: "https://foreign.example" }, { "X-Guest-Cart-Request": "" }, { Authorization: "Bearer private" },
    ]) {
      expect((await handleGuestRequest(proxyRequest(shippingOptions, {}, headers), config, fetcher)).status).toBe(403);
    }
    const arbitrary = new Request("http://localhost:3000/graphql", {
      method: "POST", headers: { Origin: config.origin, "X-Guest-Cart-Request": "1", "Content-Type": "application/json" },
      body: JSON.stringify({ operationName: shippingOptions.name, query: "query { categories { id } }" }),
    });
    expect((await handleGuestRequest(arbitrary, config, fetcher)).status).toBe(400);
    expect(calls).toBe(0);
  });
  test("proxy sends selected cookies only, ignores incoming forwarding headers, and sanitizes errors", async () => {
    let forwarded;
    const result = await handleGuestRequest(proxyRequest(guestCart, {}, {
      Cookie: "private_session=secret; retail_guest_cart=one",
      "X-Forwarded-Host": "foreign.example", "X-Api-Key": "private",
    }), config, async (_, options) => {
      forwarded = options.headers;
      return response('{"errors":[{"message":"secret database details","extensions":{"errorType":"FORBIDDEN"}}]}');
    });
    expect(forwarded.get("Cookie")).toBe("retail_guest_cart=one");
    expect(forwarded.has("X-Forwarded-Host")).toBe(false);
    expect(forwarded.has("X-Api-Key")).toBe(false);
    expect((await result.clone().text())).not.toContain("secret");
    const decoded = await run(async () => result, {}, guestCart);
    expect(decoded.error.kind).toBe("forbidden");
  });
  test("authenticated seam requires request-specific token; there is no fixture-token fallback", async () => {
    let headers;
    const operation = { ...shippingOptions, access: "authenticated" };
    const fetcher = async (_, options) => { headers = options.headers; return response(); };
    expect((await requestHive(operation, {}, config, { kind: "guest" }, { fetch: fetcher })).result.error.kind).toBe("unauthenticated");
    expect(headers).toBeUndefined();
    expect((await requestHive(operation, {}, config, { kind: "authenticated", accessToken: "request-token" }, { fetch: fetcher })).result.status).toBe("success");
    expect(headers.get("Authorization")).toBe("Bearer request-token");
    expect(headers.has("Cookie")).toBe(false);
  });
  test("a guest read with an explicit bearer context retains deliberate guest protection", async () => {
    let headers;
    const result = await requestHive(guestCart, {}, config, {
      kind: "authenticated", accessToken: "request-token",
      guestCookieHeader: "unrelated=private; retail_guest_cart=one",
    }, { fetch: async (_, options) => { headers = options.headers; return response('{"data":{"guestCart":null}}'); } });
    expect(result.result.status).toBe("success");
    expect(headers.get("Authorization")).toBe("Bearer request-token");
    expect(headers.get("Origin")).toBe(config.origin);
    expect(headers.get("X-Guest-Cart-Request")).toBe("1");
    expect(headers.get("Cookie")).toBe("retail_guest_cart=one");
  });
  test("bad cookies and malformed/batched/oversized proxy input never reach Hive", async () => {
    let calls = 0;
    const fetcher = async () => { calls++; return response(); };
    const headers = { Origin: config.origin, "X-Guest-Cart-Request": "1", "Content-Type": "application/json" };
    for (const body of [
      "not JSON", "[]", " ".repeat(32_769),
      JSON.stringify({ query: shippingOptions.source, operationName: shippingOptions.name, variables: { countryCode: "bad" } }),
    ]) {
      const result = await handleGuestRequest(new Request("http://localhost:3000/graphql", { method: "POST", headers, body }), config, fetcher);
      expect(result.status).toBe(400);
      expect(result.headers.get("Cache-Control")).toBe("private, no-store");
    }
    const invalid = await handleGuestRequest(proxyRequest(guestCart, {}, { Cookie: "retail_guest_cart=one; retail_guest_cart=two" }), config, fetcher);
    expect((await run(async () => invalid, {}, guestCart)).error.kind).toBe("validation");
    expect(calls).toBe(0);
  });
  test("concurrent proxy callers remain isolated with no hidden cookie jar", async () => {
    const cookies = [];
    const fetcher = async (_, options) => { cookies.push(options.headers.get("Cookie")); return response('{"data":{"guestCart":null}}'); };
    const results = await Promise.all(["one", "two"].map((value) => handleGuestRequest(
      proxyRequest(guestCart, {}, { Cookie: `retail_guest_cart=${value}` }), config, fetcher,
    )));
    expect(cookies).toEqual(["retail_guest_cart=one", "retail_guest_cart=two"]);
    expect(results.every((result) => result.headers.get("Cache-Control") === "private, no-store")).toBe(true);
  });
});
