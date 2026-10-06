import assert from "node:assert/strict";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { IntegrationShippingOptionsSource, IntegrationGuestCartSource, CatalogCategoriesSource, CatalogListingSource } from "../src/lib/graphql/generated.ts";

if (!process.env.PLAYWRIGHT_MODULE) throw new Error("Set PLAYWRIGHT_MODULE to an existing Playwright installation.");
const { chromium } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href);
const url = process.env.FRONTEND_URL ?? "http://localhost:3000";
const browser = await chromium.launch();
const errors = [];
try {
  const page = await browser.newPage();
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
  assert.equal((await page.goto(url)).status(), 200);
  for (const [operationName, query, variables] of [
    ["IntegrationShippingOptions", IntegrationShippingOptionsSource, { countryCode: "EE" }],
    ["IntegrationGuestCart", IntegrationGuestCartSource, {}],
  ]) {
    const result = await page.evaluate(async (body) => {
      const response = await fetch("/graphql", {
        method: "POST", credentials: "same-origin", cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Guest-Cart-Request": "1" },
        body: JSON.stringify(body),
      });
      return { status: response.status, cache: response.headers.get("Cache-Control"), body: await response.json() };
    }, { operationName, query, variables });
    assert.equal(result.status, 200);
    assert.equal(result.cache, "private, no-store");
    assert.equal(result.body.errors, undefined);
    if (operationName === "IntegrationShippingOptions") {
      assert.ok(result.body.data.checkoutShippingOptions.length > 0);
      assert.ok(result.body.data.checkoutShippingOptions.every((option) => typeof option.charge === "string"));
    } else {
      assert.equal(result.body.data.guestCart, null);
    }
  }
  assert.deepEqual(await page.context().cookies(), [], "Read-only probes must not create guest credentials");
  for (const [operationName, query, variables] of [
    ["CatalogCategories", CatalogCategoriesSource, {}],
    ["CatalogListing", CatalogListingSource, { input: { page: 0, size: 20, sort: "PRICE_ASC" } }],
  ]) {
    const result = await page.evaluate(async (body) => {
      const response = await fetch("/graphql", { method: "POST", credentials: "omit", cache: "no-store",
        headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      return { status: response.status, cache: response.headers.get("Cache-Control"), body: await response.json() };
    }, { operationName, query, variables });
    assert.equal(result.status, 200);
    assert.equal(result.cache, "private, no-store");
    assert.equal(result.body.errors, undefined);
    if (operationName === "CatalogListing") {
      assert.ok(result.body.data.searchProducts.items.length <= 20);
      assert.ok(result.body.data.searchProducts.items.every((product) => product.variants.every((variant) => typeof variant.price === "string")));
    }
  }
  assert.deepEqual(await page.context().cookies(), []);
  assert.deepEqual(errors, []);
  console.log(`PASS actual browser guest and anonymous public catalog reads -> Next.js /graphql -> Hive; exact money strings, private/no-store, no cookies/mutations. Chromium ${browser.version()}`);
} finally {
  await browser.close();
}
