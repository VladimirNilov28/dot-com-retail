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
  { path: "/", label: "Home", heading: "Electronics, clearly connected." },
  { path: "/catalog", label: "Catalog", heading: "Catalog" },
  { path: "/search", label: "Search", heading: "Search" },
  { path: "/account", label: "Account", heading: "Account" },
  { path: "/cart", label: "Cart", heading: "Cart" },
];
// Desktop entries that open a nonmodal popover instead of linking directly.
const popovers = { Catalog: "Category navigation", Account: "Account navigation" };
// Shell slots that exist only at the desktop breakpoint.
const DESKTOP_ONLY = ".store-catalog-slot, .store-actions";
const errors = [];
const results = [];
const browser = await chromium.launch();
let zoomContext;
let temporary;

function captureErrors(page) {
  page.on("pageerror", (error) => errors.push(`${page.url()}: ${error.stack ?? error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error" || /hydration|did not match/i.test(message.text())) {
      errors.push(`${page.url()} ${message.type()}: ${message.text()}`);
    }
  });
}

async function inspect(page, route, mobile) {
  await page.waitForFunction(() => document.querySelector(".store-popover, .store-drawer-backdrop") === null);
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
        scope: element.closest(".store-navigation") ? "shell" :
          element.closest(".store-popover, .store-drawer-dialog") ? "overlay" : "unexpected",
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
  assert.equal(icons.filter((icon) => icon.scope === "shell").length, 7, "Stable shell icons");
  assert.equal(icons.some((icon) => icon.scope === "unexpected"), false, "Additional icons belong only to mounted overlays");
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
  for (const label of ["Search", "Cart"]) {
    const link = page.locator(label === "Search" ? ".store-search-slot" : ".store-actions")
      .getByRole("link", { name: label, exact: true, includeHidden: true });
    assert.equal(await link.locator(".store-icon").count(), 1);
  }
  // The header is a single row: the brand and every control share one baseline.
  // Below 16rem the deliberate stacking fallback takes over, so skip it there.
  const row = await page.getByRole("banner").evaluate((banner) => {
    const header = banner.querySelector(".store-header");
    const controls = [...header.querySelectorAll(".store-brand, .store-catalog-trigger, .store-search-slot > a, .store-panel-trigger, .store-actions > a, .store-mobile-trigger")]
      .map((element) => element.getBoundingClientRect())
      .filter((rect) => rect.width > 0 && rect.height > 0);
    const centers = controls.map((rect) => rect.y + rect.height / 2);
    return {
      stacked: window.innerWidth < 256,
      height: header.getBoundingClientRect().height,
      controls: controls.length,
      spread: Math.max(...centers) - Math.min(...centers),
    };
  });
  assert.ok(row.controls >= 3, `Header renders its controls (${row.controls})`);
  if (!row.stacked) {
    assert.ok(row.spread < 1, `Header controls share one row (spread ${row.spread}px)`);
    assert.ok(row.height < 90, `Header stays compact (${row.height}px)`);
  }
  return metrics;
}

async function assertFocused(locator) {
  const element = await locator.elementHandle();
  try {
    await locator.page().waitForFunction((target) => document.activeElement === target, element);
  } finally {
    await element.dispose();
  }
  assert.equal(await locator.evaluate((element) => document.activeElement === element), true);
}

async function assertFocus(locator) {
  await assertFocused(locator);
  const visible = await locator.evaluate((element) => {
    const style = getComputedStyle(element);
    return (style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0) ||
      style.boxShadow !== "none";
  });
  assert.equal(visible, true, "Keyboard focus must be visibly styled");
}

async function assertActive(page, route) {
  // The wordmark is the home link, so Home is not duplicated in the navigation.
  const brand = page.getByRole("banner").locator(".store-brand");
  assert.equal(await brand.getAttribute("aria-current"), route.path === "/" ? "page" : null,
    "The wordmark marks the home route");
  const drawer = page.getByRole("dialog", { name: "ByteCore navigation" });
  const navigation = await drawer.count()
    ? drawer.getByRole("navigation", { name: "Primary", exact: true })
    : page.locator(".store-navigation");
  assert.equal(await navigation.count(), 1);
  const active = navigation.locator('[aria-current="page"]');
  if (route.path === "/") {
    assert.equal(await active.count(), 0, "Primary navigation does not repeat the home link");
    return;
  }
  assert.equal(await active.count(), 1);
  if (await active.evaluate((element) => element.tagName === "BUTTON")) {
    assert.equal(await active.getAttribute("aria-haspopup"), "dialog");
    assert.ok(popovers[route.label], `${route.label} is a known popover entry`);
  } else {
    assert.equal(await active.getAttribute("href"), route.path);
  }
  assert.equal(await active.textContent(), route.label);
}

async function checkMobile(page, route) {
  const menu = page.locator(".store-mobile-trigger");
  await menu.focus();
  await menu.press("Enter");
  assert.equal(await menu.getAttribute("aria-expanded"), "true");
  const dialog = page.getByRole("dialog", { name: "ByteCore navigation" });
  await dialog.waitFor();
  assert.equal(await dialog.getAttribute("role"), "dialog");
  await page.waitForFunction(() => document.activeElement?.closest('[role="dialog"]') !== null);
  assert.equal(await page.locator("main").evaluate((element) => element.closest('[inert], [aria-hidden="true"]') !== null), true,
    "Modal hides background content from assistive technology");
  await assertActive(page, route);
  const first = dialog.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Catalog", exact: true });
  await first.focus();
  await assertFocus(first);
  for (let i = 0; i < 9; i++) {
    await page.keyboard.press("Tab");
    assert.equal(await dialog.evaluate((element) => element.contains(document.activeElement)), true, "Focus stays in drawer");
  }
  await page.keyboard.press("Shift+Tab");
  assert.equal(await dialog.evaluate((element) => element.contains(document.activeElement)), true);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.keyboard.press("Escape");
  assert.equal(await menu.getAttribute("aria-expanded"), "false");
  await dialog.waitFor({ state: "hidden" });
  await assertFocus(menu);
  await menu.press("Space");
  assert.equal(await menu.getAttribute("aria-expanded"), "true");
  await dialog.getByRole("button", { name: "Close navigation" }).click();
  await dialog.waitFor({ state: "hidden" });
  assert.equal(await menu.getAttribute("aria-expanded"), "false");
  await assertFocused(menu);
  await menu.press("Enter");
  const panel = dialog.getByRole("navigation", { name: "Primary" });
  if (route.path === "/") {
    // Home has no drawer entry; verify a destination link closes the drawer and
    // that the wordmark is the way back home.
    await panel.getByRole("link", { name: "Catalog", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
    assert.equal(await menu.getAttribute("aria-expanded"), "false", "Navigation closes the menu");
    await page.waitForURL(`${baseURL}/catalog`);
    await page.locator(".store-brand").click();
    await page.waitForURL(`${baseURL}/`);
    await page.getByRole("heading", { level: 1, name: route.heading, exact: true }).waitFor();
  } else {
    const current = panel.getByRole("link", { name: route.label, exact: true });
    await current.click();
    await dialog.waitFor({ state: "hidden" });
    assert.equal(await menu.getAttribute("aria-expanded"), "false", "Same-route navigation closes the menu");
    await assertFocused(menu);
  }
  await menu.press("Enter");
  await dialog.waitFor();
  const backdrop = page.locator(".store-drawer-backdrop");
  const bounds = await backdrop.boundingBox();
  await backdrop.click({ position: { x: bounds.width - 2, y: Math.min(bounds.height - 2, 250) } });
  await dialog.waitFor({ state: "hidden" });
  assert.equal(await menu.getAttribute("aria-expanded"), "false");
  await assertFocused(menu);
  await inspect(page, route, true);
}

async function dismissOutside(page) {
  const point = await page.evaluate(() => {
    const main = document.querySelector("main").getBoundingClientRect();
    const popover = document.querySelector(".store-popover")?.getBoundingClientRect();
    const x = Math.min(innerWidth - 5, Math.max(5, main.right - 5));
    let y = Math.max(main.top + 5, Math.min(innerHeight - 5, main.bottom - 5));
    if (popover && x >= popover.left && x <= popover.right && y >= popover.top && y <= popover.bottom) {
      y = Math.min(innerHeight - 5, popover.bottom + 20);
    }
    return { x, y };
  });
  await page.mouse.click(point.x, point.y);
}

async function checkDesktopPopovers(page) {
  for (const [buttonName, dialogName] of Object.entries(popovers)) {
    const button = page.getByRole("navigation", { name: "Primary" }).getByRole("button", { name: buttonName, exact: true });
    await button.focus();
    await button.press("Enter");
    const dialog = page.getByRole("dialog", { name: dialogName });
    await dialog.waitFor();
    assert.notEqual(await dialog.getAttribute("aria-modal"), "true");
    assert.equal(await page.getByRole("main").count(), 1, "Nonmodal popover leaves background exposed");
    const link = dialog.getByRole("link", { name: buttonName, exact: true });
    await link.focus();
    await assertFocus(link);
    await page.keyboard.press("Escape");
    await dialog.waitFor({ state: "hidden" });
    await assertFocus(button);
    await button.press("Space");
    await dialog.waitFor();
    await dismissOutside(page);
    await dialog.waitFor({ state: "hidden" });
  }
  const account = page.getByRole("button", { name: "Account", exact: true });
  await account.click();
  const dialog = page.getByRole("dialog", { name: "Account navigation" });
  await dialog.getByRole("link", { name: "Account", exact: true }).focus();
  await page.setViewportSize({ width: 390, height: 1000 });
  await dialog.waitFor({ state: "hidden" });
  await assertFocused(page.locator(".store-mobile-trigger"));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.waitForFunction((selector) => document.activeElement?.closest(selector) !== null, DESKTOP_ONLY);
}

async function verifyNavigation(page, mobile) {
  for (const route of routes) {
    if (route.path === "/") {
      await page.locator(".store-brand").click();
    } else if (mobile) {
      await page.getByRole("button", { name: "Menu", exact: true }).click();
      await page.locator(".store-mobile-panel")
        .getByRole("link", { name: route.label, exact: true }).click();
    } else if (popovers[route.label]) {
      await page.locator(".store-navigation")
        .getByRole("button", { name: route.label, exact: true }).click();
      await page.getByRole("dialog", { name: popovers[route.label] })
        .getByRole("link", { name: route.label, exact: true }).click();
    } else {
      await page.locator(".store-navigation")
        .getByRole("link", { name: route.label, exact: true }).click();
    }
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
  await page.getByRole("heading", { level: 1, name: "Cart", exact: true }).waitFor();
  if (mobile) {
    assert.equal(await page.getByRole("button", { name: "Menu", exact: true }).getAttribute("aria-expanded"), "false");
  }
  await page.goForward();
  await page.waitForURL(`${baseURL}/`);
  await page.getByRole("heading", { level: 1, name: routes[0].heading, exact: true }).waitFor();
}

try {
  for (const colorScheme of ["light", "dark"]) {
    for (const width of [1440, 390, 320]) {
      console.log(`Checking ${width}px/${colorScheme}`);
      const context = await browser.newContext({
        viewport: { width, height: 1000 }, colorScheme, reducedMotion: "reduce", hasTouch: true,
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
      // Tab order follows the visual left-to-right order of the single header row.
      await page.keyboard.press("Tab");
      if (!mobile) {
        await assertFocus(page.locator(".store-catalog-slot").getByRole("button", { name: "Catalog", exact: true }));
        await page.keyboard.press("Tab");
      }
      await assertFocus(page.locator(".store-search-slot").getByRole("link", { name: "Search", exact: true }));
      await page.keyboard.press("Tab");
      if (mobile) {
        await assertFocus(page.getByRole("button", { name: "Menu", exact: true }));
      } else {
        await assertFocus(page.locator(".store-actions").getByRole("button", { name: "Account", exact: true }));
        await page.keyboard.press("Tab");
        await assertFocus(page.locator(".store-actions").getByRole("link", { name: "Cart", exact: true }));
      }
      if (!mobile) await checkDesktopPopovers(page);
      if (mobile) {
        await page.locator(".store-mobile-trigger").tap();
        await page.getByRole("dialog", { name: "ByteCore navigation" }).getByRole("button", { name: "Close navigation" }).tap();
        await assertFocused(page.locator(".store-mobile-trigger"));
      } else {
        await page.getByRole("button", { name: "Catalog", exact: true }).tap();
        await page.getByRole("dialog", { name: "Category navigation" }).waitFor();
        await dismissOutside(page);
        await page.getByRole("dialog", { name: "Category navigation" }).waitFor({ state: "hidden" });
      }
      await verifyNavigation(page, mobile);
      if (mobile) {
        const menu = page.getByRole("button", { name: "Menu", exact: true });
        await menu.click();
        await page.locator(".store-mobile-panel").getByRole("link", { name: "Catalog", exact: true }).focus();
        if (artifacts) await page.screenshot({ path: join(artifacts, `${width}-${colorScheme}-menu.png`), fullPage: true });
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.waitForFunction((selector) => document.activeElement?.closest(selector) !== null, DESKTOP_ONLY);
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
      // The component playground now lives on a noindex development route.
      await page.goto(`${baseURL}/dev/foundation`);
      assert.match(await page.locator('meta[name="robots"]').getAttribute("content"), /noindex/);
      assert.equal(await page.locator(".store-navigation").getByRole("link", { name: "Component playground" }).count(), 0,
        "The playground is not advertised in storefront navigation");
      const demo = page.getByRole("button", { name: "Show details", exact: true });
      await demo.click();
      assert.equal(await page.getByRole("button", { name: "Hide details", exact: true }).getAttribute("aria-expanded"), "true");
      await page.goto(baseURL);
      assert.equal(await page.getByRole("button", { name: "Show details", exact: true }).count(), 0,
        "The storefront home page no longer renders the component playground");
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
      results.push(`PASS ${width}px/${colorScheme}: routes, reloads, navigation, focus, skip link, dark paints, overflow${mobile ? ", modal drawer/history/resize/reduced motion" : ", nonmodal popovers"}`);
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
