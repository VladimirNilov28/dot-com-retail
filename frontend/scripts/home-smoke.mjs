import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";

assert.ok(process.env.PLAYWRIGHT_MODULE, "Supply the existing external PLAYWRIGHT_MODULE.");
const { chromium } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href);
const base = process.env.FRONTEND_URL;
assert.ok(base, "Supply FRONTEND_URL matching the production build.");
const state = process.env.HOME_TEST_STATE ?? "populated";
assert.ok(["populated", "sparse", "empty", "no-products", "categories-error", "probe-error", "section-error", "loading"].includes(state));
const control = process.env.CATALOG_TEST_CONTROL;
const artifacts = process.env.BROWSER_ARTIFACTS;
if (artifacts) await mkdir(artifacts, { recursive: true });
const browser = await chromium.launch();
const errors = [];
const recoveryPages = [];
const failures = ["categories-error", "probe-error", "section-error"];
const homeOperations = ["CatalogCategories", "HomeDiscovery", "HomeCategoryProducts"];

async function controls(parameters = {}) {
  assert.ok(control, "Controlled verification needs the isolated CATALOG_TEST_CONTROL.");
  const url = new URL(control);
  assert.equal(url.hostname, "127.0.0.1");
  assert.equal(url.port, "4064");
  for (const [key, value] of Object.entries(parameters)) url.searchParams.set(key, value);
  const response = await fetch(url);
  assert.ok(response.ok, "Isolated verification control failed");
  const data = await response.json();
  assert.match(data.identity, /^bytecore-catalog-/);
  return data;
}

function captureErrors(page) {
  page.on("pageerror", (error) => errors.push(`${page.url()}: ${error.message}`));
  page.on("console", (message) => {
    const text = message.text();
    if (message.type() === "error" || /hydration|did not match/i.test(text)) errors.push(`${page.url()}: ${text}`);
  });
}

async function capture(page, width, name) {
  if (!artifacts) return;
  await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
  await page.screenshot({ path: join(artifacts, `issue-86-home-${width === 1440 ? "desktop-1440" : "mobile-390"}-${name}.png`), fullPage: true });
}

async function fits(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth ||
    document.body.scrollWidth > document.documentElement.clientWidth), false, "No homepage horizontal overflow");
}

async function loaded(page) {
  await page.getByRole("heading", { level: 1, name: "Find your next connection.", exact: true }).waitFor();
  await page.waitForFunction(() => !document.querySelector(".home-loading"));
  await page.waitForFunction((title) => document.title === title, "Browse electronics | ByteCore");
  assert.equal(await page.title(), "Browse electronics | ByteCore");
  assert.equal(await page.locator("main").count(), 1);
  assert.ok(await page.locator(".home-selection").count() <= 3);
  assert.ok(await page.locator(".home-selection .product-card").count() <= 12);
  for (const section of await page.locator(".home-selection").all()) assert.equal(await section.isVisible(), true);
  assert.doesNotMatch(await page.locator("main").textContent(), /Directly assigned products|lowest price first/);
  assert.doesNotMatch(await page.getByRole("contentinfo").textContent(), /Sign-in, cart and checkout/);
  for (const section of await page.locator(".home-selection").all()) {
    const link = section.getByRole("link", { name: "View all", exact: true });
    assert.match(await link.getAttribute("href"), /^\/catalog\/[^/?]+$/);
    assert.ok((await section.locator("h2").textContent()).trim().length > 0);
  }
  await fits(page);
}

async function categoryTiles(page) {
  const tiles = page.locator(".home-category");
  for (const tile of await tiles.all()) {
    const root = tile.locator("h2 a");
    assert.match(await root.getAttribute("href"), /^\/catalog\/[^/?]+$/);
    assert.equal(await root.locator("svg[aria-hidden=true][focusable=false]").count(), 1);
    assert.equal(await tile.locator("ul, details").count(), 0, "Owner's root-only homepage is preserved");
  }
  if (await tiles.count() && await page.evaluate(() => innerWidth) === 390) {
    assert.equal(await page.locator(".home-categories").evaluate((element) => getComputedStyle(element).columnCount), "2");
  }
}

async function focused(locator) {
  const element = await locator.elementHandle();
  try {
    await locator.page().waitForFunction((target) => document.activeElement === target, element);
  } finally {
    await element.dispose();
  }
  assert.equal(await locator.evaluate((element) => {
    const style = getComputedStyle(element);
    return document.activeElement === element &&
      ((style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0) || style.boxShadow !== "none");
  }), true, "Visible keyboard focus");
}

async function navigation(page) {
  const roots = page.getByRole("navigation", { name: "Shop by category" });
  assert.ok(await roots.getByRole("link").count() > 0);
  for (const selector of [".home-category h2 a"]) {
    const link = page.locator(selector).first();
    assert.ok(await link.count(), `Real category hierarchy supplies ${selector}`);
    const href = await link.getAttribute("href");
    assert.match(href, /^\/catalog\/[^/?]+$/);
    await page.keyboard.press("Tab");
    await link.focus(); await focused(link);
    await link.press("Enter");
    await page.waitForURL(new URL(href, base).href);
    await page.getByRole("group", { name: "Product view" }).waitFor();
    await page.goBack(); await loaded(page);
  }
  const product = page.locator(".home-selection .product-name").first();
  const href = await product.getAttribute("href");
  const name = (await product.textContent()).trim();
  const query = name.slice(0, 100);
  assert.match(href, /^\/products\/[^/?]+$/);
  await product.focus(); await focused(product); await product.press("Enter");
  await page.waitForURL(new URL(href, base).href);
  assert.equal((await page.reload()).status(), 200, "Actual product slug resolves to public product details");
  assert.equal(await page.locator(".product-page h1").textContent(), name);
  await page.goto(base); await loaded(page);

  const trigger = page.locator(".store-search-slot").getByRole("link", { name: "Search", exact: true });
  await trigger.focus(); await trigger.press("Enter");
  const field = page.locator(".store-search-overlay").getByRole("combobox", { name: "Search the catalog" });
  await focused(field);
  assert.equal(await page.locator(".store-brand").isVisible(), false, "Accepted search replaces header contents");
  await field.fill(""); await field.fill(query);
  const suggestions = page.getByRole("listbox", { name: "Search suggestions" });
  await suggestions.waitFor();
  assert.ok(await suggestions.getByRole("option").count() > 0);
  assert.ok(await suggestions.getByRole("option").count() <= 8);
  await field.press("ArrowDown");
  assert.equal(await suggestions.getByRole("option").first().getAttribute("aria-selected"), "true");
  assert.ok(await field.getAttribute("aria-activedescendant"));
  await field.press("Escape");
  await suggestions.waitFor({ state: "hidden" });
  assert.equal(await field.inputValue(), query, "Escape dismisses suggestions without clearing the query");
  assert.equal(await page.locator(".store-search-overlay").isVisible(), true);
  await field.press("Escape");
  await page.locator(".store-search-overlay").waitFor({ state: "hidden" });
  await focused(trigger);
  await trigger.press("Enter");
  await field.fill(""); await field.fill(query);
  await suggestions.waitFor();
  await field.press("Escape");
  assert.equal(await field.inputValue(), query, "The query survives dismissal before submission");
  await field.press("Enter");
  await page.waitForURL((url) => url.pathname === "/search" && url.searchParams.get("q") === query);
  await page.getByRole("group", { name: "Product view" }).waitFor();
  assert.equal(await page.getByRole("combobox", { name: "Search products" }).count(), 1);
  assert.ok(await page.locator("ul.catalog-products .product-card").count() > 0);
  await page.goto(base); await loaded(page);
}


try {
  const cold = process.env.HOME_VERIFY_COLD === "1";
  if (cold) await controls({ reset: "1" });
  for (const width of [1440, 390]) {
    const page = await browser.newPage({ viewport: { width, height: width === 1440 ? 900 : 844 }, reducedMotion: "reduce" });
    captureErrors(page);
    await page.goto(base);
    if (state === "loading" && width === 1440) {
      await page.getByRole("heading", { name: "Product selections unavailable", exact: true }).waitFor();
      await controls({ mode: "home-delay" });
      await page.getByRole("button", { name: "Retry", exact: true }).click();
      const pending = page.getByRole("button", { name: /Retrying/ });
      await pending.waitFor();
      assert.equal(await pending.getAttribute("data-pending"), "true");
      assert.equal(await pending.getAttribute("aria-disabled"), "true");
      assert.equal(await pending.isDisabled(), true);
      await capture(page, width, "loading");
      await page.waitForFunction(() => document.querySelectorAll(".home-selection").length === 3);
      await controls({ mode: "normal" });
    }
    await loaded(page);
    if (cold && width === 1440) {
      const data = await controls();
      assert.equal(data.counts.CatalogCategories, 1, "Header and home share one cold taxonomy request");
      assert.equal(data.counts.HomeDiscovery, 1);
      assert.equal(data.counts.HomeCategoryProducts, await page.locator(".home-selection").count());
      assert.ok(homeOperations.reduce((sum, name) => sum + (data.counts[name] ?? 0), 0) <= 5);
      const before = { ...data.counts };
      await page.reload(); await loaded(page);
      const after = await controls();
      for (const name of homeOperations) assert.equal(after.counts[name], before[name], "Warm validated home reads are reused");
      if (process.env.HOME_VERIFY_REVALIDATION === "1") {
        await page.waitForTimeout(61000);
        await page.reload(); await loaded(page);
        for (let attempt = 0; attempt < 30; attempt++) {
          const refreshed = await controls();
          if (homeOperations.every((name) => refreshed.counts[name] > before[name])) break;
          assert.ok(attempt < 29, "Every stale homepage operation must really revalidate");
          await page.waitForTimeout(250);
        }
      }
    }
    if (state === "empty") {
      await page.getByRole("heading", { name: "No categories yet", exact: true }).waitFor();
      assert.equal(await page.getByRole("navigation", { name: "Shop by category" }).count(), 0);
      assert.equal(await page.locator(".home-selection").count(), 0);
    } else if (state === "no-products") {
      await page.getByRole("heading", { name: "No category products yet", exact: true }).waitFor();
      assert.ok(await page.getByRole("navigation", { name: "Shop by category" }).getByRole("link").count() > 0);
      assert.equal(await page.locator(".home-selection").count(), 0);
    } else if (state === "categories-error") {
      await page.getByRole("heading", { name: "Categories unavailable", exact: true }).waitFor();
    } else if (state === "probe-error") {
      await page.getByRole("heading", { name: "Product selections unavailable", exact: true }).waitFor();
      assert.ok(await page.getByRole("navigation", { name: "Shop by category" }).getByRole("link").count() > 0);
    } else if (state === "section-error") {
      assert.equal(await page.getByRole("heading", { name: / products unavailable$/ }).count(), 1);
      assert.equal(await page.locator(".home-selection").count(), 2, "Successful sections survive a localized failure");
    } else {
      assert.ok(await page.locator(".home-selection .product-card").count() > 0);
      if (state === "sparse") assert.equal(await page.locator(".home-selection .product-card").count(), 1);
    }
    if (!failures.includes(state)) await categoryTiles(page);
    await capture(page, width, failures.includes(state) || ["empty", "sparse", "no-products"].includes(state) ? state : "default");
    if (failures.includes(state)) { recoveryPages.push(page); continue; }
    if (state === "populated") await navigation(page);
    await page.close();
  }
  if (recoveryPages.length) {
    await controls({ mode: "normal" });
    for (const page of recoveryPages) {
      await page.getByRole("button", { name: "Retry", exact: true }).first().click();
      await page.waitForFunction(() => document.querySelectorAll(".home-selection").length === 3 &&
        !document.querySelector(".home-loading"));
      assert.equal(await page.getByRole("heading", { name: /unavailable$/ }).count(), 0, "Failed responses are not persisted");
      await page.close();
    }
  }
  if (state === "populated" || state === "sparse") {
    const page = await browser.newPage({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
    captureErrors(page);
    assert.equal((await page.goto(base)).status(), 200);
    await loaded(page);
    assert.ok(await page.locator(".home-selection .product-card").count() > 0);
    await categoryTiles(page);
    const details = page.locator(".home-category details").first();
    let categoryLink;
    if (await details.count()) {
      await details.locator("summary").press("Enter");
      categoryLink = details.getByRole("link").first();
    } else {
      categoryLink = page.locator(".home-category h2 a").first();
    }
    const href = await categoryLink.getAttribute("href");
    const name = (await categoryLink.textContent()).trim();
    await categoryLink.press("Enter");
    await page.waitForURL(new URL(href, base).href);
    assert.equal((await page.reload()).status(), 200, "Real no-JavaScript category navigation");
    assert.equal(await page.getByRole("heading", { level: 1 }).textContent(), name);
    await page.close();
  }
  assert.deepEqual(errors, [], "No unexpected home hydration/runtime/console errors");
  console.log(`PASS real production home ${state}: 1440/390px, metadata, bounded visible selections, overflow, reduced motion${state === "populated" ? ", root-category/product destinations, header suggestions/submission/two-stage Escape, no-JavaScript rendering" : ""}${recoveryPages.length ? ", localized retry/recovery" : ""}${cold ? ", measured cold/warm request budget" : ""}${process.env.HOME_VERIFY_REVALIDATION === "1" ? ", real 60-second revalidation" : ""}; unexpected errors: 0`);
} finally {
  await browser.close();
}
