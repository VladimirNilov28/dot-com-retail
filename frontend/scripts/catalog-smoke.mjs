import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";

if (!process.env.PLAYWRIGHT_MODULE) throw new Error("Set PLAYWRIGHT_MODULE to an external Playwright installation.");
const { chromium } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href);
const base = process.env.FRONTEND_URL ?? "http://localhost:3000";
const control = process.env.CATALOG_TEST_CONTROL;
const shots = process.env.BROWSER_ARTIFACTS;
if (shots) await mkdir(shots, { recursive: true });
const browser = await chromium.launch();
const errors = [];
const results = [];
async function mode(value) {
  if (!control) throw new Error("Controlled state checks require the verification-only CATALOG_TEST_CONTROL URL.");
  const response = await fetch(`${control}?mode=${value}`);
  assert.ok(response.ok);
  return response.json();
}
async function capture(page, route, viewport, state, fullPage = true) {
  if (shots) {
    await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
    await page.screenshot({ path: join(shots, `issue-63-${route}-${viewport}-${state}.png`), fullPage });
  }
}
async function loaded(page) {
  await page.getByRole("group", { name: "Product view" }).waitFor();
  await page.waitForFunction(() => !document.querySelector(".catalog-link-pending"));
}
async function fits(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
}
try {
  if (control) await mode("normal");
  for (const width of [1440, 390]) {
    const viewport = width === 1440 ? "desktop-1440" : "mobile-390";
    const page = await browser.newPage({ viewport: { width, height: width === 1440 ? 900 : 844 }, colorScheme: "light" });
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error" && !/Failed to load resource.*404/.test(message.text())) errors.push(message.text());
    });
    await page.goto(`${base}/catalog/laptops`);
    await loaded(page);
    await page.getByRole("button", { name: width === 1440 ? "Catalog" : "Menu", exact: true }).press("Enter");
    const dialog = page.getByRole("dialog", { name: width === 1440 ? "Category navigation" : "ByteCore navigation" });
    const categoryLink = dialog.getByRole("navigation", { name: "Catalog categories" }).getByRole("link", { name: "Laptops", exact: true });
    await categoryLink.focus();
    assert.ok(await categoryLink.evaluate((element) => getComputedStyle(element).outlineStyle !== "none"));
    await categoryLink.press("Enter");
    await dialog.waitFor({ state: "hidden" });
    assert.equal(await page.getByRole("list", { name: "Products" }).getByRole("listitem").count(), 20);
    assert.match(await page.locator(".catalog-page > .catalog-toolbar").textContent(), /1–20 of 23 products/);
    assert.equal(await page.title(), "Laptops | ByteCore");
    assert.equal(await page.locator("ul.catalog-products .product-card").first().getByRole("link").getAttribute("href"), "/products/laptop-1");
    assert.match(await page.locator("ul.catalog-products .product-card").first().textContent(), /From €499.00.*Availability unknown/);
    await fits(page);
    await capture(page, "category", viewport, "default");
    if (control) await mode("delay");
    const next = page.getByRole("navigation", { name: "Pagination" }).getByRole("link", { name: "Next", exact: true });
    await next.focus();
    assert.ok(await next.evaluate((element) => getComputedStyle(element).outlineStyle !== "none"));
    await next.press("Enter");
    if (control && width === 1440) {
      await page.locator(".catalog-transition-loading").waitFor({ state: "visible" });
      assert.equal(await page.locator(".catalog-transition-loading .product-card").count(), 20);
      await capture(page, "category", viewport, "loading", false);
    }
    await page.waitForURL(`${base}/catalog/laptops?page=2`);
    await loaded(page);
    if (control) await mode("normal");
    assert.equal(await page.getByRole("list", { name: "Products" }).getByRole("listitem").count(), 3);
    assert.match(await page.locator(".catalog-page > .catalog-toolbar").textContent(), /21–23 of 23/);
    await page.reload(); await loaded(page);
    assert.match(page.url(), /page=2$/);
    await page.getByRole("button", { name: "List view" }).focus();
    await page.keyboard.press("Space");
    await page.waitForURL(`${base}/catalog/laptops?page=2&view=list`);
    await page.waitForFunction(() => document.querySelector(".catalog-list") !== null);
    assert.equal(await page.getByRole("button", { name: "List view" }).getAttribute("aria-pressed"), "true");
    await fits(page);
    await capture(page, "category", viewport, "list");
    await page.goBack(); await loaded(page);
    assert.equal(await page.getByRole("button", { name: "Grid view" }).getAttribute("aria-pressed"), "true");
    await page.goForward(); await loaded(page);
    assert.equal(await page.getByRole("button", { name: "List view" }).getAttribute("aria-pressed"), "true");
    await page.reload(); await loaded(page);
    assert.equal(await page.getByRole("button", { name: "List view" }).getAttribute("aria-pressed"), "true");
    await page.getByRole("navigation", { name: "Breadcrumb" }).getByRole("link", { name: "Computers", exact: true }).click();
    await page.waitForURL(`${base}/catalog/computers?view=list`); await loaded(page);
    assert.match(await page.locator("ul.catalog-products .product-card").textContent(), /Modular Computer Kit.*Price unavailable.*Availability unknown/);
    assert.equal(await page.locator("ul.catalog-products .product-card").count(), 1, "Parent includes direct members only");
    // The "All products" CTA leads to the relocated bounded listing (#63 step 2);
    // it is no longer the catalog root.
    await page.locator(".catalog-categories").getByRole("link", { name: "All products", exact: true }).click();
    await page.waitForURL(`${base}/catalog/all-products?view=list`);
    await loaded(page);
    assert.equal(await page.getByRole("button", { name: "List view" }).getAttribute("aria-pressed"), "true");
    // The breadcrumb's "Catalog" crumb has no discovery page to link to any
    // more (that composition moved into the header megamenu); it is a plain,
    // non-linked label now, not a dead link to a removed page.
    const breadcrumb = page.getByRole("navigation", { name: "Breadcrumb" });
    assert.equal(await breadcrumb.getByRole("link", { name: "Catalog", exact: true }).count(), 0,
      "The Catalog crumb is a plain label, not a link to a removed page");
    assert.match(await breadcrumb.textContent(), /Catalog/);
    // A bare /catalog request redirects to the homepage; the standalone
    // discovery page is never revived (owner's revised megamenu decision).
    await page.goto(`${base}/catalog`);
    await page.waitForURL(`${base}/`);
    await fits(page);
    await page.goto(`${base}/catalog/gaming`); await loaded(page);
    assert.equal(await page.locator("ul.catalog-products .product-card").count(), 0);
    assert.equal(await page.getByRole("list", { name: "Products" }).count(), 0);
    assert.match(await page.locator("main").textContent(), /No products assigned directly/);
    assert.equal(await page.getByRole("navigation", { name: "Subcategories" }).getByRole("link", { name: "Components" }).count(), 1);
    await capture(page, "category", viewport, "parent-empty");
    await page.goto(`${base}/catalog/monitors`); await loaded(page);
    assert.match(await page.locator("main").textContent(), /0 products.*No products yet/);
    await fits(page); await capture(page, "category", viewport, "empty");
    const missing = await page.goto(`${base}/catalog/missing-category`);
    assert.equal(missing.status(), 404);
    await page.getByRole("heading", { name: "Category not found" }).waitFor();
    assert.equal(await page.locator('meta[name="robots"]').getAttribute("content"), "noindex");
    await fits(page); await capture(page, "category", viewport, "not-found");
    await page.goto(`${base}/catalog?page=0`);
    // Legacy bookmarked query strings, including invalid ones, still reach the
    // relocated listing's recovery flow (#63 step 2).
    await page.waitForURL(`${base}/catalog/all-products?page=0`);
    await page.getByRole("heading", { name: "Invalid catalog URL" }).waitFor();
    await page.getByRole("link", { name: "Go to first page" }).click();
    await page.waitForURL(`${base}/catalog/all-products`); await loaded(page);
    await page.close();
    results.push(`PASS ${width}px: real bounded prices, direct categories, pagination/reload/history, keyboard view controls, empty/not-found and overflow`);
  }
  const noJs = await browser.newPage({ javaScriptEnabled: false });
  await noJs.goto(`${base}/catalog/laptops`);
  assert.equal(await noJs.getByRole("list", { name: "Products" }).getByRole("listitem").count(), 20);
  assert.match(await noJs.locator("ul.catalog-products .product-card").first().textContent(), /NovaBook 14.*499.00/);
  await noJs.close();
  results.push("PASS meaningful initial SSR listing with JavaScript disabled");
  if (control) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const before = await mode("error");
    await page.goto(`${base}/catalog/laptops?page=777`);
    await page.getByRole("heading", { name: "Unable to load products" }).waitFor();
    await capture(page, "category", "desktop-1440", "error");
    await mode("normal");
    await page.getByRole("button", { name: "Retry", exact: true }).click();
    await page.waitForURL(`${base}/catalog/laptops?page=2`);
    await loaded(page);
    const after = await mode("normal");
    assert.ok(after.counts.CatalogListing >= (before.counts.CatalogListing ?? 0) + 2, "Failure must be retried, not cached");
    await page.goto(`${base}/catalog/laptops`);
    await loaded(page);
    await page.getByRole("button", { name: "List view" }).click();
    await page.waitForURL(`${base}/catalog/laptops?view=list`);
    const stable = await mode("normal");
    await page.reload(); await loaded(page);
    const reused = await mode("normal");
    assert.equal(reused.counts.CatalogListing, stable.counts.CatalogListing, "Validated success reused within freshness interval");
    await page.goto(`${base}/catalog/all-products?view=list`);
    await loaded(page);
    await mode("delay");
    await page.getByRole("navigation", { name: "Pagination" }).getByRole("link", { name: "Next", exact: true }).click();
    await page.locator(".catalog-transition-loading").waitFor({ state: "visible" });
    assert.equal(await page.locator(".catalog-transition-loading .product-card").first().evaluate((element) => getComputedStyle(element).flexDirection), "row");
    await capture(page, "catalog", "desktop-1440", "list-loading", false);
    await page.waitForURL(`${base}/catalog/all-products?page=2&view=list`);
    await loaded(page);
    await mode("normal");
    await page.close();
    results.push("PASS controlled GraphQL error/retry without cached failure; successful public result reuse");
  }
  assert.deepEqual(errors, [], "No unexpected console/hydration/runtime errors");
  console.log(`Chromium ${browser.version()}\n${results.join("\n")}\nPASS unexpected console/hydration/runtime errors: 0`);
} finally {
  if (control) await mode("normal");
  await browser.close();
}
