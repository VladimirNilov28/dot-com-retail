import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

if (!process.env.PLAYWRIGHT_MODULE) {
  throw new Error("Set PLAYWRIGHT_MODULE to an existing Playwright index.mjs; see README.");
}
const { chromium } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href);
const baseURL = process.env.FRONTEND_URL ?? "http://127.0.0.1:3100";
const artifacts = process.env.BROWSER_ARTIFACTS;
if (artifacts) await mkdir(artifacts, { recursive: true });
const routes = [
  { path: "/", label: "Home", heading: "A small start. A consistent foundation." },
  { path: "/catalog", label: "Catalog", heading: "Catalog" },
  { path: "/search", label: "Search", heading: "Search" },
  { path: "/account", label: "Account", heading: "Account" },
  { path: "/cart", label: "Cart", heading: "Cart" },
];
const errors = [];
const results = [];
const browser = await chromium.launch();
let zoomContext;
let temporary;

function captureErrors(page) {
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error" || /hydration|did not match/i.test(message.text())) {
      errors.push(`${message.type()}: ${message.text()}`);
    }
  });
}

async function inspect(page, route, mobile) {
  assert.equal(await page.locator("main").count(), 1);
  assert.equal(await page.locator("#main-content").count(), 1);
  assert.equal(await page.getByRole("banner").count(), 1);
  assert.equal(await page.getByRole("contentinfo").count(), 1);
  assert.equal(await page.locator('a[href="#main-content"]').count(), 1);
  assert.equal(await page.getByRole("heading", { level: 1 }).textContent(), route.heading);
  if (route.path !== "/") {
    assert.match(await page.locator("main").textContent(), /not available yet/i);
    assert.equal(await page.locator("main input, main form, main button").count(), 0);
    assert.equal(await page.getByRole("link", { name: "Return to home" }).getAttribute("href"), "/");
    assert.equal(await page.title(), `${route.label} | ByteCore`);
  }
  const metrics = await page.evaluate(() => {
    const root = document.documentElement;
    const color = getComputedStyle(document.body).backgroundColor;
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("2d");
    context.fillStyle = color;
    context.fillRect(0, 0, 1, 1);
    const rgb = [...context.getImageData(0, 0, 1, 1).data].slice(0, 3);
    return {
      width: root.clientWidth,
      scrollWidth: root.scrollWidth,
      dark: root.classList.contains("dark"),
      theme: root.dataset.theme,
      scheme: getComputedStyle(root).colorScheme,
      rgb,
      horizontalOverflow: root.scrollWidth > root.clientWidth ||
        document.body.scrollWidth > root.clientWidth,
    };
  });
  assert.equal(metrics.horizontalOverflow, false, JSON.stringify(metrics));
  assert.equal(metrics.dark, true);
  assert.equal(metrics.theme, "dark");
  assert.equal(metrics.scheme, "dark");
  assert.ok(Math.max(...metrics.rgb) < 40);
  assert.equal(await page.getByRole("button", { name: "Menu", exact: true }).isVisible(), mobile);
  const icons = await page.locator(".store-icon").evaluateAll((elements) =>
    elements.map((element) => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      const parent = element.parentElement.getBoundingClientRect();
      return {
        hidden: element.getAttribute("aria-hidden"),
        focusable: element.getAttribute("focusable"),
        stroke: element.getAttribute("stroke"),
        strokeWidth: element.getAttribute("stroke-width"),
        width: style.width,
        height: style.height,
        flexShrink: style.flexShrink,
        visible: rect.width > 0 && rect.height > 0,
        aligned: Math.abs(rect.y + rect.height / 2 - parent.y - parent.height / 2) < 1,
        fits: rect.x >= parent.x && rect.right <= parent.right,
      };
    }),
  );
  assert.equal(icons.length, 7, "Three decorative navigation icons per layout plus the trigger icon");
  for (const icon of icons) {
    assert.equal(icon.hidden, "true");
    assert.equal(icon.focusable, "false");
    assert.equal(icon.stroke, "currentColor");
    assert.equal(icon.strokeWidth, "2");
    assert.equal(icon.width, "20px");
    assert.equal(icon.height, "20px");
    assert.equal(icon.flexShrink, "0");
    if (icon.visible) {
      assert.equal(icon.aligned, true, "Icon is vertically aligned with its control");
      assert.equal(icon.fits, true, "Icon fits inside its control");
    }
  }
  for (const nav of [".store-desktop-nav", ".store-mobile-panel"]) {
    for (const label of ["Search", "Account", "Cart"]) {
      const link = page.locator(nav).getByRole("link", { name: label, exact: true, includeHidden: true });
      assert.equal(await link.locator(".store-icon").count(), 1);
    }
  }
  return metrics;
}

async function assertFocus(locator) {
  assert.equal(await locator.evaluate((element) => document.activeElement === element), true);
  const visible = await locator.evaluate((element) => {
    const style = getComputedStyle(element);
    return (style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0) ||
      style.boxShadow !== "none";
  });
  assert.equal(visible, true, "Keyboard focus must be visibly styled");
}

async function assertActive(page, route) {
  const navigation = page.getByRole("navigation", { name: "Primary", exact: true });
  assert.equal(await navigation.count(), 1);
  const active = navigation.locator('[aria-current="page"]');
  assert.equal(await active.count(), 1);
  assert.equal(await active.getAttribute("href"), route.path);
  assert.equal(await active.textContent(), route.label);
}

async function checkMobile(page, route) {
  const menu = page.getByRole("button", { name: "Menu", exact: true });
  await menu.focus();
  await menu.press("Enter");
  assert.equal(await menu.getAttribute("aria-expanded"), "true");
  assert.equal(await menu.locator("svg.lucide-x").count(), 1);
  const id = await menu.getAttribute("aria-controls");
  assert.equal(await page.locator(`[id="${id}"]`).isVisible(), true);
  await assertActive(page, route);
  await inspect(page, route, true);
  await page.keyboard.press("Tab");
  const home = page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Home", exact: true });
  await assertFocus(home);
  await page.keyboard.press("Escape");
  assert.equal(await menu.getAttribute("aria-expanded"), "false");
  assert.equal(await menu.locator("svg.lucide-menu").count(), 1);
  await assertFocus(menu);
  assert.equal(await page.locator(`[id="${id}"]`).isVisible(), false);
  await menu.press("Space");
  assert.equal(await menu.getAttribute("aria-expanded"), "true");
  await menu.press("Space");
  assert.equal(await menu.getAttribute("aria-expanded"), "false");
  await assertFocus(menu);
  await menu.press("Enter");
  const current = page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: route.label, exact: true });
  await current.click();
  assert.equal(await menu.getAttribute("aria-expanded"), "false", "Same-route navigation closes the menu");
  assert.equal(await menu.evaluate((element) => element === document.activeElement), true);
  await menu.press("Enter");
  const cart = page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Cart", exact: true });
  await cart.focus();
  await page.keyboard.press("Tab");
  assert.equal(await page.evaluate(() => document.activeElement.closest("nav") === null), true, "No focus trap");
  await page.keyboard.press("Escape");
  // Escape applies within the disclosure, not to unrelated page controls.
  await menu.focus();
  await menu.press("Escape");
  assert.equal(await menu.getAttribute("aria-expanded"), "false");
}

async function verifyNavigation(page, mobile) {
  for (const route of routes) {
    if (mobile) await page.getByRole("button", { name: "Menu", exact: true }).click();
    await page.getByRole("navigation", { name: "Primary", exact: true })
      .getByRole("link", { name: route.label, exact: true }).click();
    await page.waitForURL(`${baseURL}${route.path}`);
    await page.getByRole("heading", { level: 1, name: route.heading, exact: true }).waitFor();
    await inspect(page, route, mobile);
    if (mobile) {
      assert.equal(await page.getByRole("button", { name: "Menu", exact: true }).getAttribute("aria-expanded"), "false");
    } else {
      await assertActive(page, route);
    }
  }
  await page.getByRole("link", { name: "Return to home", exact: true }).click();
  await page.waitForURL(`${baseURL}/`);
  await page.goBack();
  await page.waitForURL(`${baseURL}/cart`);
  if (mobile) {
    assert.equal(await page.getByRole("button", { name: "Menu", exact: true }).getAttribute("aria-expanded"), "false");
  }
  await page.goForward();
  await page.waitForURL(`${baseURL}/`);
}

try {
  for (const colorScheme of ["light", "dark"]) {
    for (const width of [1440, 390, 320]) {
      const context = await browser.newContext({
        viewport: { width, height: 1000 }, colorScheme, reducedMotion: "reduce",
      });
      await context.addInitScript(() => {
        window.__paintTime = null;
        window.__darkFrames = [];
        new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if (entry.name === "first-contentful-paint") window.__paintTime = entry.startTime;
          }
        }).observe({ type: "paint", buffered: true });
        let frames = 0;
        function sample() {
          if (document.body) {
            window.__darkFrames.push({
              time: performance.now(),
              dark: document.documentElement.classList.contains("dark"),
              theme: document.documentElement.dataset.theme,
              color: getComputedStyle(document.body).backgroundColor,
              scheme: getComputedStyle(document.documentElement).colorScheme,
            });
          }
          if (++frames < 160) requestAnimationFrame(sample);
        }
        requestAnimationFrame(sample);
      });
      await context.route("**/*.css", async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 150));
        await route.continue();
      });
      await context.route("**/*.js", async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 300));
        await route.continue();
      });
      const page = await context.newPage();
      captureErrors(page);
      const mobile = width < 768;
      for (const route of routes) {
        assert.equal((await page.goto(`${baseURL}${route.path}`)).status(), 200);
        await inspect(page, route, mobile);
        assert.equal((await page.reload()).status(), 200);
        await inspect(page, route, mobile);
        if (mobile) await checkMobile(page, route);
        else await assertActive(page, route);
      }
      await page.goto(baseURL);
      await page.keyboard.press("Tab");
      const skip = page.getByRole("link", { name: "Skip to content" });
      await assertFocus(skip);
      assert.ok((await skip.boundingBox()).y >= 0);
      await page.keyboard.press("Enter");
      assert.equal(await page.evaluate(() => document.activeElement.id), "main-content");
      await page.locator(".store-brand").focus();
      await page.keyboard.press("Tab");
      if (mobile) await assertFocus(page.getByRole("button", { name: "Menu", exact: true }));
      else await assertFocus(page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Home", exact: true }));
      await verifyNavigation(page, mobile);
      if (mobile) {
        const menu = page.getByRole("button", { name: "Menu", exact: true });
        await menu.click();
        await page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Catalog", exact: true }).focus();
        if (artifacts) await page.screenshot({ path: join(artifacts, `${width}-${colorScheme}-menu.png`), fullPage: true });
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.waitForFunction(() => document.activeElement?.closest(".store-desktop-nav") !== null);
        await page.setViewportSize({ width, height: 1000 });
        await page.waitForFunction(() => document.activeElement?.textContent === "Menu");
        assert.equal(await menu.getAttribute("aria-expanded"), "false");
        const style = await menu.evaluate((element) => ({
          transition: getComputedStyle(element).transitionProperty,
          animation: getComputedStyle(element).animationName,
        }));
        assert.equal(style.transition, "none");
        assert.equal(style.animation, "none");
      }
      await page.goto(baseURL);
      const demo = page.getByRole("button", { name: "Show details", exact: true });
      await demo.click();
      assert.equal(await page.getByRole("button", { name: "Hide details", exact: true }).getAttribute("aria-expanded"), "true");
      if (artifacts) await page.screenshot({ path: join(artifacts, `${width}-${colorScheme}.png`), fullPage: true });
      await page.waitForFunction(() => window.__paintTime !== null);
      const paints = await page.evaluate(() => window.__darkFrames.filter((frame) => frame.time >= window.__paintTime));
      assert.ok(paints.length > 0);
      const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
      for (const frame of paints) {
        assert.equal(frame.dark, true);
        assert.equal(frame.theme, "dark");
        assert.equal(frame.color, background);
        assert.equal(frame.scheme, "dark");
      }
      results.push(`PASS ${width}px/${colorScheme}: routes, reloads, navigation, focus, skip link, dark paints, overflow${mobile ? ", disclosure/history/resize/reduced motion" : ""}`);
      await context.close();
    }
  }
  for (const width of [1440, 320]) {
    const context = await browser.newContext({ javaScriptEnabled: false, colorScheme: "light", viewport: { width, height: 1000 } });
    const page = await context.newPage();
    captureErrors(page);
    for (const route of routes) {
      assert.equal((await page.goto(`${baseURL}${route.path}`)).status(), 200);
      await inspect(page, route, width < 768);
    }
    results.push(`PASS ${width}px: JavaScript-disabled fixed-dark server rendering`);
    await context.close();
  }

  temporary = await mkdtemp(join(tmpdir(), "bytecore-browser-"));
  const extension = join(temporary, "zoom-extension");
  await mkdir(extension);
  await writeFile(join(extension, "manifest.json"), JSON.stringify({
    manifest_version: 3, name: "Storefront zoom verification", version: "1.0",
    permissions: ["tabs"], background: { service_worker: "worker.js" },
  }));
  await writeFile(join(extension, "worker.js"), "chrome.runtime.onInstalled.addListener(() => {});");
  zoomContext = await chromium.launchPersistentContext(join(temporary, "profile"), {
    channel: "chromium", headless: true, viewport: { width: 1440, height: 1000 },
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
  });
  const worker = zoomContext.serviceWorkers()[0] ?? await zoomContext.waitForEvent("serviceworker");
  const page = await zoomContext.newPage();
  captureErrors(page);
  await page.goto(baseURL);
  const initial = await page.evaluate(() => ({ width: innerWidth, dpr: devicePixelRatio }));
  await worker.evaluate(async (url) => {
    const tabs = await chrome.tabs.query({});
    const tab = tabs.find((candidate) => candidate.url.startsWith(url));
    if (!tab) throw new Error("Browser zoom test tab not found");
    await chrome.tabs.setZoom(tab.id, 2);
    if (await chrome.tabs.getZoom(tab.id) !== 2) throw new Error("Browser did not apply 200% zoom");
  }, baseURL);
  await page.waitForFunction((before) => innerWidth === before.width / 2 && devicePixelRatio === before.dpr * 2, initial);
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.waitForFunction((physicalWidth) => innerWidth === physicalWidth / 2, width);
    for (const route of routes) {
      assert.equal((await page.goto(`${baseURL}${route.path}`)).status(), 200);
      await inspect(page, route, true);
      await checkMobile(page, route);
    }
    await verifyNavigation(page, true);
    if (artifacts) await page.screenshot({ path: join(artifacts, `${width}-200-percent-browser-zoom.png`) });
    results.push(`PASS actual Chromium 200% browser zoom: CSS viewport ${width} -> ${width / 2}, DPR doubled, all routes/menu/navigation without overflow`);
  }
  assert.deepEqual(errors, [], "Browser console/hydration/runtime errors");
  console.log(`Browser: Chromium ${browser.version()}\n${results.join("\n")}\nPASS console/hydration/runtime errors: 0\n${results.length} scenarios passed against ${baseURL}`);
} finally {
  await zoomContext?.close();
  await browser.close();
  if (temporary) await rm(temporary, { recursive: true, force: true });
}
