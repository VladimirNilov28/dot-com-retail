import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

assert.ok(process.env.PLAYWRIGHT_MODULE, "Supply the existing external PLAYWRIGHT_MODULE");
const { chromium } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href);
const base = process.env.FRONTEND_URL;
assert.ok(base, "Supply FRONTEND_URL matching the production build");
const hive = process.env.PRODUCT_TEST_HIVE ?? "http://127.0.0.1:4063/graphql";
const control = process.env.CATALOG_TEST_CONTROL;
const artifacts = process.env.BROWSER_ARTIFACTS;
const liveSlug = process.env.PRODUCT_LIVE_SLUG;
const errors = [];
const missingPath = "/products/nonexistent-product-smoke-65";
const browser = await chromium.launch();
let zoom;
let temporary;
if (artifacts) await mkdir(artifacts, { recursive: true });

async function product(slug) {
  const response = await fetch(hive, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: "query($slug:String!){product(slug:$slug){name slug variants{id sku price isActive}}}", variables: { slug } }),
  });
  assert.ok(response.ok);
  const data = await response.json();
  assert.ok(!data.errors, "Real public Hive lookup failed");
  assert.ok(data.data.product, `Real product ${slug} must exist`);
  return data.data.product;
}

async function controls(mode) {
  assert.ok(control, "Failure verification requires the isolated control bridge");
  const url = new URL(control);
  assert.equal(url.origin, "http://127.0.0.1:4064");
  url.searchParams.set("mode", mode);
  const response = await fetch(url);
  assert.ok(response.ok);
  assert.match((await response.json()).identity, /^bytecore-catalog-/);
}

function diagnostics(page) {
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    const source = message.location().url;
    const expectedMissing = source && new URL(source).pathname === missingPath &&
      /Failed to load resource.*404/.test(message.text());
    if ((message.type() === "error" && !expectedMissing) || /hydration|did not match/i.test(message.text())) {
      errors.push(`${page.url()}: ${message.text()}`);
    }
  });
}

async function fits(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth ||
    document.body.scrollWidth > document.documentElement.clientWidth), false, "No product horizontal overflow");
}

async function capture(page, width, state) {
  await fits(page);
  if (artifacts) await page.screenshot({ path: join(artifacts,
    `issue-65-product-${width === 1440 ? "desktop-1440" : "mobile-390"}-${state}.png`), fullPage: true });
}

async function selected(page, variant, price) {
  await page.locator(`.product-variant-link[aria-current="true"][href*="variant=${variant.id}"]`).waitFor();
  await page.waitForFunction((sku) => document.querySelector(".product-sku")?.textContent === sku, variant.sku);
  assert.equal(await page.locator(".product-detail-price").textContent(), price);
  assert.ok((await page.locator(".product-specifications").textContent()).includes(variant.sku));
  assert.equal(await page.locator(".product-variant-link[aria-current]").count(), 1);
  assert.equal(await page.getByRole("button", { name: /Add to cart/i }).count(), 0);
  assert.match(await page.locator(".product-information").textContent(), /Availability unknown/);
  await fits(page);
}

async function focus(link) {
  await link.focus();
  assert.equal(await link.evaluate((element) => {
    const style = getComputedStyle(element);
    return document.activeElement === element && style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0;
  }), true, "Visible variant keyboard focus");
}

try {
  const slug = liveSlug ?? "laptop-1";
  const actual = await product(slug);
  const variants = actual.variants.filter(({ isActive }) => isActive);
  assert.ok(variants.length);
  const first = variants[0];
  const second = variants[1];
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: "reduce" });
    const page = await context.newPage();
    diagnostics(page);
    assert.equal((await page.goto(`${base}/products/${slug}`)).status(), 200);
    assert.equal(await page.locator(".product-page h1").textContent(), actual.name);
    assert.equal(await page.title(), `${actual.name} | ByteCore`);
    assert.equal(await page.getByRole("img", { name: "Product image unavailable" }).count(), 1);
    await selected(page, first, liveSlug ? `€${Number(first.price).toFixed(2)}` : "€499.00");
    await capture(page, width, "default");
    if (!liveSlug && second) {
      const link = page.locator(`.product-variant-link[href$="variant=${second.id}"]`);
      await focus(link);
      const footerBefore = (await page.getByRole("contentinfo").boundingBox()).y;
      const delayedNavigation = (url) => url.pathname === `/products/${slug}` && url.searchParams.get("variant") === second.id;
      await page.route(delayedNavigation, async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 1500));
        await route.continue();
      });
      await link.press("Enter");
      await page.locator(".product-transition-loading").waitFor({ state: "visible" });
      assert.equal(await page.locator(".product-detail-price").isVisible(), false, "Pending price and specs are hidden together");
      assert.equal(await page.locator(".product-specifications").isVisible(), false);
      assert.ok(Math.abs((await page.getByRole("contentinfo").boundingBox()).y - footerBefore) <= 1,
        "Variant skeletons preserve the selected content's layout footprint");
      await capture(page, width, "variant-loading");
      await page.waitForURL((url) => url.searchParams.get("variant") === second.id);
      await selected(page, second, "€649.00");
      await page.unroute(delayedNavigation);
      assert.match(await page.locator(".product-specifications").textContent(), /1TB/);
      await capture(page, width, "selected");
      await page.reload(); await selected(page, second, "€649.00");
      await page.goBack(); await selected(page, first, "€499.00");
      await page.goForward(); await selected(page, second, "€649.00");
      assert.match(await page.locator(".product-specifications").textContent(), /1TB/);
      await page.locator(`.product-variant-link[href$="variant=${first.id}"]`).click();
      await page.locator(`.product-variant-link[href$="variant=${second.id}"]`).click();
      await selected(page, second, "€649.00");
      for (const value of ["bad", "99999999", `${first.id}&variant=${second.id}`, ""]) {
        await page.goto(`${base}/products/${slug}?variant=${value}`);
        await selected(page, first, "€499.00");
        assert.match(await page.locator(".product-variants").textContent(), /linked variant/);
      }
      const foreign = (await product("compact-desktop")).variants[0];
      await page.goto(`${base}/products/${slug}?variant=${foreign.id}`);
      await selected(page, first, "€499.00");
      await capture(page, width, "invalid-variant");
      if (process.env.PRODUCT_REMOVED_VARIANT) {
        await page.goto(`${base}/products/${slug}?variant=${process.env.PRODUCT_REMOVED_VARIANT}`);
        await selected(page, first, "€499.00");
        assert.match(await page.locator(".product-variants").textContent(), /linked variant/);
      }
      await page.goto(`${base}/products/compact-desktop`);
      await selected(page, foreign, "€799.00");
      assert.equal(await page.locator(".product-variant-link").count(), 1);
      const equal = await product("quietkey");
      await page.goto(`${base}/products/quietkey`);
      await selected(page, equal.variants[0], "€79.90");
      await page.locator(`.product-variant-link[href$="variant=${equal.variants[1].id}"]`).click();
      await selected(page, equal.variants[1], "€79.90");
      await page.goto(`${base}/products/computer-kit`);
      assert.equal(await page.locator(".product-detail-price").textContent(), "Price unavailable");
      assert.equal(await page.locator(".product-variant-link").count(), 0);
      assert.match(await page.locator(".product-description").textContent(), /No description/);
      assert.equal(await page.locator(".product-rating").count(), 0);
      await capture(page, width, "no-variants");
      const inactive = (await product("retired-laptop")).variants[0];
      await page.goto(`${base}/products/retired-laptop`);
      assert.equal(await page.locator(".product-detail-price").textContent(), "Price unavailable");
      await page.locator(".product-variant-link").click();
      await selected(page, inactive, "€9.99");
      assert.match(await page.locator(".product-variant-unavailable").textContent(), /unavailable/);
      assert.doesNotMatch(await page.locator("main").textContent(), /out of stock/i);
      await capture(page, width, "inactive");
    }
    assert.equal((await page.goto(`${base}${missingPath}`)).status(), 404);
    assert.equal(await page.getByRole("heading", { level: 1 }).textContent(), "Product not found");
    const robots = await page.locator('meta[name="robots"]').all();
    assert.ok(robots.length);
    for (const meta of robots) assert.ok((await meta.getAttribute("content")).includes("noindex"));
    await capture(page, width, "not-found");
    await context.close();
  }

  if (!liveSlug) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: "reduce" });
    diagnostics(page);
    for (const entry of ["/", "/catalog/laptops", "/catalog/all-products", "/search?q=NovaBook"]) {
      await page.goto(`${base}${entry}`);
      const link = page.locator(".product-name").first();
      const name = await link.textContent();
      await link.click();
      await page.locator(".product-page").waitFor();
      assert.equal(await page.locator(".product-page h1").textContent(), name);
    }
    await page.goto(`${base}/search`);
    const field = page.getByRole("combobox", { name: "Search products" });
    await field.fill("NovaBook");
    await page.getByRole("listbox", { name: "Search suggestions" }).waitFor();
    await field.press("ArrowDown"); await field.press("Enter");
    await page.locator(".product-page").waitFor();
    assert.match(await page.locator(".product-page h1").textContent(), /NovaBook/);
    await page.goto(`${base}/search`);
    await field.fill("NovaBook");
    await page.getByRole("listbox", { name: "Search suggestions" }).getByRole("option").first().click();
    await page.locator(".product-page").waitFor();

    await controls("product-error");
    assert.equal((await page.goto(`${base}/products/laptop-22`)).status(), 200);
    assert.equal(await page.getByRole("heading", { level: 1 }).textContent(), "Product unavailable");
    await capture(page, 1440, "error");
    await page.setViewportSize({ width: 390, height: 900 });
    await capture(page, 390, "error");
    await controls("product-delay");
    await page.getByRole("button", { name: "Retry", exact: true }).click();
    await page.getByRole("button", { name: "Retrying…", exact: true }).waitFor();
    assert.equal(await page.getByRole("button", { name: "Retrying…", exact: true }).isDisabled(), true);
    await capture(page, 390, "retry-loading");
    await page.locator(".product-page").waitFor();
    await controls("normal");
    await page.close();
  }

  for (const width of [1440, 390]) {
    const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width, height: 900 } });
    const page = await context.newPage();
    diagnostics(page);
    if (!liveSlug && width === 1440) {
      await controls("product-error");
      await page.goto(`${base}/products/laptop-20`);
      assert.equal(await page.getByRole("heading", { level: 1 }).textContent(), "Product unavailable");
      await controls("normal");
      await page.getByRole("link", { name: "Reload this product" }).click();
      await page.locator(".product-page").waitFor();
    }
    await page.goto(`${base}/products/${slug}`);
    assert.equal(await page.locator(".product-page").isVisible(), true);
    assert.equal(await page.locator(".product-detail-price").isVisible(), true);
    const next = !liveSlug && second ? second : first;
    await page.locator(`.product-variant-link[href$="variant=${next.id}"]`).click();
    await selected(page, next, liveSlug ? `€${Number(next.price).toFixed(2)}` : (second ? "€649.00" : "€499.00"));
    assert.equal(await page.locator(".product-specifications").isVisible(), true);
    await fits(page);
    await context.close();
  }

  temporary = await mkdtemp(join(tmpdir(), "bytecore-product-zoom-"));
  const extension = join(temporary, "extension");
  await mkdir(extension);
  await writeFile(join(extension, "manifest.json"), JSON.stringify({
    manifest_version: 3, name: "Product zoom verification", version: "1.0",
    permissions: ["tabs"], background: { service_worker: "worker.js" },
  }));
  await writeFile(join(extension, "worker.js"), "chrome.runtime.onInstalled.addListener(() => {});");
  zoom = await chromium.launchPersistentContext(join(temporary, "profile"), {
    channel: "chromium", headless: true, viewport: { width: 1440, height: 900 },
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
  });
  const worker = zoom.serviceWorkers()[0] ?? await zoom.waitForEvent("serviceworker");
  const page = await zoom.newPage();
  diagnostics(page);
  await page.goto(`${base}/products/${slug}`);
  const initial = await page.evaluate(() => ({ width: innerWidth, dpr: devicePixelRatio }));
  await worker.evaluate(async (base) => {
    const tab = (await chrome.tabs.query({})).find((tab) => tab.url.startsWith(base));
    await chrome.tabs.setZoom(tab.id, 2);
    if (await chrome.tabs.getZoom(tab.id) !== 2) throw new Error("Actual browser zoom did not apply");
  }, base);
  await page.waitForFunction((before) => innerWidth === before.width / 2 && devicePixelRatio === before.dpr * 2, initial);
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await fits(page);
    await focus(page.locator(".product-variant-link").first());
    await page.locator(".product-variant-link").first().press("Enter");
    await fits(page);
  }
  assert.deepEqual(errors, [], "Unexpected product runtime/console/hydration errors");
  console.log(`PASS product ${liveSlug ? "live" : "isolated"}: desktop/mobile, actual variants/URLs/prices/specs/history, no-JS, real 200% zoom, missing product; ${liveSlug ? "read-only live Hive" : "entry paths and controlled failure/Retry"}; Chromium ${browser.version()}; unexpected errors: 0`);
} finally {
  if (control) await controls("normal");
  await zoom?.close();
  await browser.close();
  if (temporary) await rm(temporary, { recursive: true, force: true });
}
