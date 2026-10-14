import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import {
  GuestCartStartSource, GuestCartAddSource,
  GuestCheckoutPreviewSource, GuestCheckoutPlaceSource, GuestCheckoutConfirmationSource,
  GuestPaymentSessionSource, CustomerCheckoutConfirmationSource, CustomerPaymentSessionSource, CustomerCheckoutPlaceSource,
} from "../src/lib/graphql/generated.ts";

const base = process.env.STOREFRONT_BASE_URL ?? "http://127.0.0.1:3000";
assert.equal(base, "http://127.0.0.1:3000", "Use the normal local BFF/Hive stack, not a remote storefront");
const variant = process.env.CHECKOUT_FIXTURE_VARIANT_ID;
assert.match(variant ?? "", /^[1-9][0-9]*$/, "An explicitly created disposable fixture variant is required");
const captures = process.env.STOREFRONT_ARTIFACTS;
assert.ok(captures, "Choose a private artifact directory outside source control");
await mkdir(captures, { recursive: true });
assert.ok(process.env.PLAYWRIGHT_MODULE, "Provide the installed Playwright module");
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const browser = await chromium.launch({ headless: true });
const selected = new Set((process.env.CHECKOUT_SCENARIOS ?? "guest,retry,sca,validation,customer,pending").split(","));
for (const scenario of selected) assert.ok(["guest", "retry", "sca", "validation", "customer", "pending", "account-switch", "guest-recovery"].includes(scenario));
let previous = {};
try { previous = JSON.parse(await readFile(join(captures, "evidence.json"), "utf8")); }
catch (error) { if (error.code !== "ENOENT") throw error; }
const evidence = previous.scenarios ?? [];
const unexpected = [];
const contexts = [];
const customerCredentials = new Map();
let currentPage;
let stage = "startup";

function sql(query) {
  return execFileSync("docker", ["exec", "-i", "postgres", "sh", "-c",
    'psql -U "$POSTGRES_USER" -d retail -v ON_ERROR_STOP=1 -At'], { input: query, encoding: "utf8" }).trim();
}
assert.equal(sql(`SELECT p.name FROM products p JOIN product_variants v ON v.product_id=p.id WHERE v.id=${variant};`),
  "Sandbox checkout fixture", "Do not use or modify an owner catalog product");
assert.equal(sql(`SELECT w.name FROM warehouses w JOIN inventory i ON i.warehouse_id=w.id WHERE i.product_variant_id=${variant};`),
  "Sandbox checkout verification", "Stock changes must be confined to the dedicated fixture warehouse");

async function pageFor() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  contexts.push(context);
  const page = await context.newPage();
  page.setDefaultTimeout(35_000);
  page.on("pageerror", () => unexpected.push("pageerror"));
  page.on("console", (message) => {
    if (message.type() === "error" && message.text().includes("Account status could not be loaded")) unexpected.push("account-status");
  });
  currentPage = page;
  await page.goto(base + "/cart");
  return page;
}
async function op(page, source, name, variables = {}) {
  return page.evaluate(async ({ source, name, variables }) => {
    const identity = await (await fetch("/graphql/checkout-identity", {
      method: "POST", headers: { "X-Guest-Cart-Request": "1" },
    })).json();
    const response = await fetch("/graphql", { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-Guest-Cart-Request": "1", "X-Checkout-Scope": identity.scope },
      body: JSON.stringify({ query: source, operationName: name, variables }) });
    return { http: response.status, body: await response.json() };
  }, { source, name, variables });
}
async function seed(page) {
  assert.ok((await op(page, GuestCartStartSource, "GuestCartStart")).body.data);
  const added = await op(page, GuestCartAddSource, "GuestCartAdd", { input: { productVariantId: variant, quantity: 1 } });
  assert.ok(added.body.data, "Cart add must succeed through the registered BFF operation");
  assert.ok(JSON.stringify(added.body.data).includes("Sandbox checkout fixture"));
}
async function layout(page, name) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, "No viewport overflow");
  if (name) await page.screenshot({ path: join(captures, name + ".png"), fullPage: true });
}
async function checkoutForm(page, email, prefilled = false) {
  await page.goto(base + "/checkout");
  await page.getByRole("heading", { name: "Checkout", exact: true }).waitFor();
  const shipping = page.locator("fieldset").filter({ has: page.locator("legend", { hasText: "Shipping" }) });
  if (prefilled) {
    await page.getByLabel("Saved shipping address").waitFor();
    await page.waitForFunction((email) => document.querySelector('input[type="email"]')?.value === email, email);
    assert.equal(await shipping.getByLabel(/First name/).inputValue(), "Sandbox");
    assert.equal(await shipping.getByLabel(/^Address/).inputValue(), "Fixture 2");
    assert.ok(await page.getByLabel("Saved shipping address").inputValue());
  } else {
    await page.locator('input[type="email"]').fill(email);
    await shipping.getByLabel(/First name/).fill("Sandbox");
    await shipping.getByLabel(/Last name/).fill("Example");
    await shipping.getByLabel(/^Address/).fill("Fixture 1");
    await shipping.getByLabel(/^City/).fill("Tallinn");
    await shipping.getByLabel(/^Postal code/).fill("10111");
    await shipping.getByLabel(/^Country/).selectOption("EE");
  }
  await page.getByText("I accept this current item and shipping total.").waitFor();
}
async function place(page, customer = false, capture = false) {
  await page.getByText("I accept this current item and shipping total.").click();
  if (capture) {
    await layout(page, "checkout-desktop");
    await page.setViewportSize({ width: 390, height: 1000 });
    await layout(page, "checkout-mobile");
    await page.setViewportSize({ width: 1440, height: 1000 });
  }
  await page.getByRole("button", { name: /^Place order ·/ }).click();
  await page.getByRole("heading", { name: "Complete your order", exact: true }).waitFor();
  const attempt = await page.evaluate(async () => {
    const identity = await (await fetch("/graphql/checkout-identity", {
      method: "POST", headers: { "X-Guest-Cart-Request": "1" },
    })).json();
    return JSON.parse(sessionStorage.getItem(`bytecore.checkout.attempt:${identity.scope}`)).attempt;
  });
  assert.equal(attempt.mode, customer ? "user" : "guest");
  assert.equal(attempt.input.checkout.paymentSelection, "STRIPE");
  return attempt;
}
async function losePlacementResponse(page, customer) {
  await page.evaluate(({ operationName, field }) => {
    const original = window.fetch.bind(window);
    window.fetch = async (resource, options) => {
      const response = await original(resource, options);
      const input = typeof options?.body === "string" ? JSON.parse(options.body) : null;
      if (input?.operationName === operationName) {
        const result = await response.clone().json();
        if (!result.data?.[field]) throw new Error("The real placement did not commit");
        window.__lostPlacementInput = input.variables.input;
        window.fetch = original;
        throw new TypeError("Deliberately lost committed placement response");
      }
      return response;
    };
  }, { operationName: customer ? "CustomerCheckoutPlace" : "GuestCheckoutPlace",
    field: customer ? "createOrder" : "createGuestOrder" });
}
async function hostedFrame(page) {
  for (let read = 0; read < 140; read++) {
    for (const frame of page.frames()) {
      if (await frame.getByPlaceholder("1234 1234 1234 1234").count()) return frame;
    }
    await page.waitForTimeout(250);
  }
  throw new Error("Stripe's hosted card component did not become ready");
}
async function pay(page, number, sca = false) {
  const element = await hostedFrame(page);
  await element.getByPlaceholder("1234 1234 1234 1234").fill(number);
  await element.getByPlaceholder("MM / YY").fill("12/30");
  await element.getByPlaceholder("CVC").fill("123");
  const zip = element.getByPlaceholder("12345");
  if (await zip.count()) await zip.fill("10111");
  await page.getByRole("button", { name: "Pay securely", exact: true }).click();
  if (sca) {
    let completed = 0;
    for (let read = 0; read < 160 && !page.url().includes("/order-confirmation/"); read++) {
      let challenge;
      for (const frame of page.frames()) {
        const button = frame.getByRole("button", { name: /^Complete( authentication)?$/i });
        if (await button.count() && await button.isVisible()) { challenge = button; break; }
      }
      if (challenge) {
        await page.waitForTimeout(750);
        await challenge.click();
        ++completed;
        assert.ok(completed <= 4, "Provider authentication must not loop indefinitely");
        await page.waitForTimeout(1500);
      } else await page.waitForTimeout(250);
    }
    assert.ok(completed, "Real provider authentication must be observed, not simulated by the store");
  }
}
async function order(page, attempt) {
  const guest = attempt.mode === "guest";
  const result = await op(page, guest ? GuestCheckoutConfirmationSource : CustomerCheckoutConfirmationSource,
    guest ? "GuestCheckoutConfirmation" : "CustomerCheckoutConfirmation", { requestId: attempt.input.requestId });
  assert.ok(!result.body.errors?.length, "Authorized confirmation must be readable through BFF/Hive");
  return guest ? result.body.data.guestOrder : result.body.data.checkoutOrder;
}
async function paid(page, attempt) {
  await page.waitForURL(/order-confirmation/);
  await page.getByRole("heading", { name: "Payment confirmed", exact: true }).waitFor({ timeout: 90_000 });
  const value = await order(page, attempt);
  assert.equal(value.status, "PAID");
  assert.equal(value.payment.status, "SUCCEEDED");
  assert.equal(value.totals.total, "24.98");
  assert.equal(value.lines[0].quantity, 1);
  assert.equal(value.details.paymentSelection, "STRIPE");
  return value;
}
async function retryRequired(page, attempt) {
  for (let read = 0; read < 60; read++) {
    if ((await order(page, attempt)).payment.status === "UNRESOLVED") return;
    await page.waitForTimeout(500);
  }
  throw new Error("Verified decline was not consumed by the order queue");
}
async function session(page, attempt) {
  const result = await op(page, GuestPaymentSessionSource, "GuestPaymentSession", { requestId: attempt.input.requestId });
  assert.ok(result.body.data?.guestPaymentSession);
  return result.body.data.guestPaymentSession;
}
function remember(name, attempt, value) {
  evidence.push({ name, requestId: attempt.input.requestId, publicId: value.publicId,
    status: value.status, payment: value.payment.status, total: value.totals.total });
}
async function disposableCustomer(page) {
  const suffix = randomBytes(6).toString("hex");
  const email = `sandbox-${suffix}@checkout.example.test`;
  const password = `Fixture!${randomBytes(24).toString("base64url")}9`;
  customerCredentials.set(email, password);
  await page.goto(base + "/register?returnTo=%2Faccount");
  await page.getByRole("heading", { name: "Create your account", exact: true }).waitFor();
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Username", { exact: true }).fill(`sandbox${suffix}`);
  await page.getByLabel("Date of birth", { exact: true }).fill("1990-06-01");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.locator("altcha-widget").getByRole("checkbox").focus();
  await page.keyboard.press("Space");
  await page.waitForFunction(() => Boolean(document.querySelector("input[name=altcha]")?.value), null, { timeout: 90_000 });
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await page.getByRole("heading", { name: "Verify your email", exact: true }).waitFor();
  let code;
  for (let read = 0; read < 60 && !code; read++) {
    const inbox = await (await fetch("http://127.0.0.1:8025/api/v1/messages")).json();
    const message = inbox.messages.find((item) => item.To.some((recipient) => recipient.Address === email));
    if (message) {
      const mail = await (await fetch(`http://127.0.0.1:8025/api/v1/message/${message.ID}`)).json();
      code = mail.Text.match(/\b[0-9]{6}\b/)?.[0];
    }
    if (!code) await page.waitForTimeout(500);
  }
  assert.ok(code, "Use the genuine verification message delivered by existing courier SMTP");
  await page.getByLabel("Verification code", { exact: true }).fill(code);
  await page.getByRole("button", { name: "Verify email", exact: true }).click();
  await page.getByRole("heading", { name: "Sign in", exact: true }).waitFor();
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.waitForURL(/\/account/);
  assert.equal(sql(`INSERT INTO user_address(user_id,first_name,last_name,city,country,postal_code,address_line1)
    SELECT id,'Sandbox','Customer','Tallinn','EE','10111','Fixture 2' FROM users WHERE email='${email}' RETURNING user_id;`).split("\n").length, 2);
  return email;
}

try {
  if (selected.has("account-switch")) {
    stage = "same-browser account-switch regression";
    const page = await pageFor();
    await seed(page);
    const emailA = await disposableCustomer(page);
    const idA = sql(`SELECT id FROM users WHERE email='${emailA}';`);
    await checkoutForm(page, emailA, true);
    const attemptA = await place(page, true);
    await pay(page, "4242424242424242");
    remember("account-A-paid-before-switch", attemptA, await paid(page, attemptA));
    stage = "completed recovery cleanup and A lost response";
    await page.goto(base + "/checkout");
    await page.getByText("Your cart is empty.", { exact: false }).waitFor();
    assert.equal(await page.getByText(attemptA.input.requestId, { exact: false }).count(), 0);
    await page.goto(base + "/products/sandbox-checkout-25-74-75");
    await page.getByRole("button", { name: "Add to cart", exact: true }).click();
    await checkoutForm(page, emailA, true);
    await losePlacementResponse(page, true);
    await page.getByText("I accept this current item and shipping total.").click();
    await page.getByRole("button", { name: /^Place order ·/ }).click();
    await page.waitForFunction(() => window.__lostPlacementInput);
    await page.getByRole("button", { name: "Retry saved placement", exact: true }).waitFor();
    const uncertainA = await page.evaluate(() => window.__lostPlacementInput);
    assert.ok(uncertainA);
    await page.reload();
    await page.getByRole("heading", { name: "Complete your order", exact: true }).waitFor();
    const pendingA = { mode: "user", input: uncertainA };
    assert.equal((await order(page, pendingA)).status, "PENDING");
    await page.evaluate((legacy) => sessionStorage.setItem("bytecore.checkout.attempt", JSON.stringify(legacy)), attemptA);
    stage = "stale A confirmation tab";
    const staleTab = await page.context().newPage();
    await staleTab.goto(base + `/order-confirmation/${pendingA.input.requestId}`);
    await staleTab.getByRole("heading", { name: "Payment pending", exact: true }).waitFor();
    await page.bringToFront();
    stage = "A logout and B independent checkout";
    await page.goto(base + "/account");
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await page.waitForURL(base + "/");
    const emailB = await disposableCustomer(page);
    const idB = sql(`SELECT id FROM users WHERE email='${emailB}';`);
    assert.notEqual(idA, idB);
    await page.goto(base + "/products/sandbox-checkout-25-74-75");
    await page.getByRole("button", { name: "Add to cart", exact: true }).click();
    await page.goto(base + "/checkout");
    await page.getByRole("heading", { name: "Checkout", exact: true }).waitFor();
    await page.getByLabel("Email", { exact: true }).waitFor({ timeout: 15_000 });
    assert.equal(await page.getByText(uncertainA.requestId, { exact: false }).count(), 0,
      "B must never display A's request");
    for (const [source, name, variables] of [
      [CustomerCheckoutConfirmationSource, "CustomerCheckoutConfirmation", { requestId: uncertainA.requestId }],
      [CustomerPaymentSessionSource, "CustomerPaymentSession", { requestId: uncertainA.requestId }],
      [CustomerCheckoutPlaceSource, "CustomerCheckoutPlace", { input: uncertainA }],
    ]) {
      assert.ok((await op(page, source, name, variables)).body.errors?.length,
        "B cannot manually read, replay or obtain payment for A's request");
    }
    await checkoutForm(page, emailB, true);
    await staleTab.bringToFront();
    await staleTab.locator(".checkout-page > section[role='alert']").waitFor();
    assert.equal(await staleTab.getByRole("heading", { name: "Payment pending", exact: true }).count(), 0);
    assert.equal(await staleTab.getByText(pendingA.input.requestId, { exact: false }).count(), 0);
    for (const frame of staleTab.frames()) {
      assert.equal(await frame.getByPlaceholder("1234 1234 1234 1234").count(), 0,
        "A's hosted payment fields must be unmounted");
    }
    await staleTab.close();
    await page.bringToFront();
    await page.getByRole("heading", { name: "Checkout", exact: true }).waitFor();
    await layout(page, "account-B-checkout-desktop");
    await page.setViewportSize({ width: 390, height: 1000 });
    await layout(page, "account-B-checkout-mobile");
    await page.setViewportSize({ width: 1440, height: 1000 });
    const attemptB = await place(page, true);
    assert.notEqual(attemptB.input.requestId, uncertainA.requestId);
    await pay(page, "4242424242424242");
    remember("account-B-independent-payment", attemptB, await paid(page, attemptB));
    stage = "A resumes original unresolved placement after B payment";
    await page.goto(base + "/account");
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await page.waitForURL(base + "/");
    await page.goto(base + "/login?returnTo=%2Faccount");
    await page.getByLabel("Email", { exact: true }).fill(emailA);
    await page.getByLabel("Password", { exact: true }).fill(customerCredentials.get(emailA));
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.waitForURL(base + "/account");
    await page.goto(base + "/checkout");
    await page.getByRole("heading", { name: "Complete your order", exact: true }).waitFor();
    assert.equal((await order(page, pendingA)).requestId, uncertainA.requestId);
    assert.equal(sql(`SELECT count(*) FROM checkout_requests WHERE request_id='${uncertainA.requestId}';`), "1");
    const recovered = await op(page, CustomerCheckoutPlaceSource, "CustomerCheckoutPlace", { input: uncertainA });
    assert.equal(recovered.body.data.createOrder.checkout.requestId, uncertainA.requestId);
    await page.goto(base + `/order-confirmation/${uncertainA.requestId}`);
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByRole("button", { name: "Cancel pending order", exact: true }).click();
    await page.getByRole("heading", { name: "Order cancelled", exact: true }).waitFor();
    await page.goto(base + "/checkout");
    await page.getByText("Your cart is empty.", { exact: false }).waitFor();
    assert.equal(await page.evaluate(() => sessionStorage.getItem("bytecore.checkout.attempt")), null,
      "Only A can verify and retire its legacy completed request");
    previous.accountSwitch = true;
  }
  if (selected.has("guest-recovery")) {
    stage = "protected guest lost-response recovery";
    const page = await pageFor();
    await seed(page);
    await page.context().clearCookies({ name: "retail_guest_orders" });
    const email = `guest-recovery-${Date.now()}@example.test`;
    await checkoutForm(page, email);
    await page.evaluate(() => {
      Object.defineProperty(document, "hidden", { value: true, configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await page.getByRole("heading", { name: "Checkout", exact: true }).waitFor({ state: "hidden" });
    await page.evaluate(() => {
      delete document.hidden;
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await page.getByRole("heading", { name: "Checkout", exact: true }).waitFor();
    assert.equal(await page.getByLabel("Email", { exact: true }).inputValue(), email,
      "Same-owner visibility revalidation must not discard safe form input");
    const otherTab = await page.context().newPage();
    await otherTab.goto(base + "/");
    await page.bringToFront();
    await page.getByRole("heading", { name: "Checkout", exact: true }).waitFor();
    assert.equal(await page.getByLabel("Email", { exact: true }).inputValue(), email,
      "Checking the same identity after a tab switch must preserve safe form input");
    await otherTab.close();
    const proofBefore = (await page.context().cookies(base + "/graphql")).find((cookie) => cookie.name === "retail_guest_orders");
    assert.ok(proofBefore?.httpOnly, "The order proof must be prepared before placement");
    const stock = Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`));
    await losePlacementResponse(page, false);
    await page.getByText("I accept this current item and shipping total.").click();
    await page.getByRole("button", { name: /^Place order ·/ }).click();
    await page.waitForFunction(() => window.__lostPlacementInput);
    await page.getByRole("button", { name: "Retry saved placement" }).waitFor();
    const lost = await page.evaluate(() => window.__lostPlacementInput);
    assert.ok(lost?.requestId);
    const attempt = { mode: "guest", input: lost };
    await page.reload();
    await page.getByRole("heading", { name: "Complete your order", exact: true }).waitFor();
    const proofAfter = (await page.context().cookies(base + "/graphql")).find((cookie) => cookie.name === "retail_guest_orders");
    assert.equal(proofAfter?.value, proofBefore.value);
    const replay = await op(page, GuestCheckoutPlaceSource, "GuestCheckoutPlace", { input: lost });
    assert.equal(replay.body.data.createGuestOrder.requestId, lost.requestId);
    assert.equal((await order(page, attempt)).requestId, lost.requestId);
    assert.equal(Number(sql(`SELECT count(*) FROM checkout_requests WHERE request_id='${lost.requestId}';`)), 1);
    assert.equal(Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`)), stock - 1);
    await page.goto(base + `/order-confirmation/${lost.requestId}?guest=1`);
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByRole("button", { name: "Cancel pending order", exact: true }).click();
    await page.getByRole("heading", { name: "Order cancelled", exact: true }).waitFor();
    assert.equal(Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`)), stock);
    previous.guestRecovery = true;
  }
  if (selected.has("guest")) {
  stage = "guest success / replay / pending";
  const page = await pageFor();
  if (process.env.CHECKOUT_ABANDON_GOOGLE === "1") {
    await page.goto(base + "/login");
    await page.getByRole("button", { name: /Google/ }).click();
    await page.waitForURL("https://accounts.google.com/**");
    assert.equal(new URL(page.url()).searchParams.get("prompt"), "select_account");
    await page.goto(base + "/");
    const anonymous = await page.evaluate(async () => (await (await fetch("/auth/session")).json()).kind);
    assert.equal(anonymous, "anonymous", "Abandoned Google authorization is not a storefront login");
  }
  await seed(page);
  await checkoutForm(page, "sandbox-success-25-74-75@example.test");
  const attempt = await place(page, false, true);
  assert.equal((await order(page, attempt)).status, "PENDING");
  await hostedFrame(page);
  await page.setViewportSize({ width: 390, height: 1000 });
  await layout(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  const replay = await op(page, GuestCheckoutPlaceSource, "GuestCheckoutPlace", { input: attempt.input });
  assert.equal(replay.body.data.createGuestOrder.publicId, (await order(page, attempt)).publicId);
  await pay(page, "4242424242424242");
  const success = await paid(page, attempt);
  remember("guest-success-replay", attempt, success);
  await page.reload();
  await page.getByRole("heading", { name: "Payment confirmed", exact: true }).waitFor();
  await layout(page, "confirmation-desktop");
  await page.setViewportSize({ width: 390, height: 1000 });
  await layout(page, "confirmation-mobile");
  const foreign = await pageFor();
  for (const [source, name] of [[GuestCheckoutConfirmationSource, "GuestCheckoutConfirmation"], [GuestPaymentSessionSource, "GuestPaymentSession"]]) {
    const denied = await op(foreign, source, name, { requestId: attempt.input.requestId });
    assert.ok(denied.body.errors?.length, "A guest reference without its independent proof must not authorize access");
  }
  }
  if (selected.has("retry")) {
  for (const [name, number, message] of [
    ["insufficient-funds-retry", "4000000000009995", /insufficient funds/i],
    ["declined-card-retry", "4000000000000002", /declined/i],
  ]) {
    stage = name;
    const retryPage = await pageFor();
    const stock = Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`));
    await seed(retryPage);
    await checkoutForm(retryPage, `sandbox-${name}@example.test`);
    const retryAttempt = await place(retryPage);
    await hostedFrame(retryPage);
    const originalIntent = (await session(retryPage, retryAttempt)).clientSecret.split("_secret_")[0];
    await pay(retryPage, number);
    await retryPage.getByRole("alert").filter({ hasText: message }).waitFor();
    await retryRequired(retryPage, retryAttempt);
    assert.equal((await order(retryPage, retryAttempt)).status, "PENDING");
    assert.equal(Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`)), stock - 1,
      "A retryable decline must retain the original allocation");
    await retryPage.goto(base + `/order-confirmation/${retryAttempt.input.requestId}?guest=1`);
    await retryPage.getByRole("alert").filter({ hasText: /Retri?ying uses this same order/i }).waitFor();
    const retrySession = await session(retryPage, retryAttempt);
    assert.equal(retrySession.status, "requires_payment_method");
    assert.equal(retrySession.clientSecret.split("_secret_")[0], originalIntent);
    await pay(retryPage, "4242424242424242");
    remember(name, retryAttempt, await paid(retryPage, retryAttempt));
    assert.equal(Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`)), stock - 1,
      "Successful same-order retry must not allocate twice");
  }
  }
  if (selected.has("sca")) {
  stage = "additional provider authentication";
  const scaPage = await pageFor();
  await seed(scaPage);
  await checkoutForm(scaPage, "sandbox-authentication@example.test");
  const scaAttempt = await place(scaPage);
  await pay(scaPage, "4000002500003155", true);
  remember("provider-authentication", scaAttempt, await paid(scaPage, scaAttempt));
  }

  if (selected.has("validation")) {
  stage = "cart edits, structural validation, changed quote and stock";
  const validationPage = await pageFor();
  await seed(validationPage);
  await checkoutForm(validationPage, "sandbox-validation@example.test");
  await validationPage.getByText("I accept this current item and shipping total.").click();
  await validationPage.getByRole("button", { name: /^Increase quantity/ }).click();
  await validationPage.getByRole("button", { name: "Place order · €44.97", exact: true }).waitFor();
  assert.equal(await validationPage.getByRole("checkbox", { name: "I accept this current item and shipping total." }).isChecked(), false);
  await validationPage.getByRole("button", { name: /^Decrease quantity/ }).click();
  await validationPage.getByRole("button", { name: "Place order · €24.98", exact: true }).waitFor();
  await validationPage.getByRole("button", { name: /^Remove/ }).click();
  await validationPage.getByText("Your cart is empty.", { exact: false }).waitFor();
  assert.equal(await validationPage.getByRole("button", { name: /^Place order/ }).count(), 0);
  await seed(validationPage);
  const selection = { contactEmail: "sandbox-validation@example.test", shippingMethod: "STANDARD", billingSameAsShipping: true,
    shippingAddress: { firstName: "Sandbox", lastName: "Example", city: "Tallinn", countryCode: "EE", postalCode: "10111",
      addressLine1: "Fixture 1" }, paymentSelection: "STRIPE" };
  for (const shippingAddress of [{ ...selection.shippingAddress, postalCode: "" }, { ...selection.shippingAddress, countryCode: "DE" }]) {
    const invalid = await op(validationPage, GuestCheckoutPreviewSource, "GuestCheckoutPreview",
      { input: { ...selection, shippingAddress } });
    assert.ok(invalid.body.errors?.length, "Invalid address/destination must be rejected by the server");
  }
  const quote = await op(validationPage, GuestCheckoutPreviewSource, "GuestCheckoutPreview", { input: selection });
  assert.equal(quote.body.data.guestCheckoutPreview.placeable, true);
  const old = { requestId: randomUUID(), acceptedQuoteVersion: quote.body.data.guestCheckoutPreview.quoteVersion, checkout: selection };
  const originalPrice = sql(`SELECT price FROM product_variants WHERE id=${variant};`);
  try {
    sql(`UPDATE product_variants SET price=20.99 WHERE id=${variant};`);
    const changed = await op(validationPage, GuestCheckoutPlaceSource, "GuestCheckoutPlace", { input: old });
    assert.ok(changed.body.errors?.some((error) => error.extensions.code === "CHECKOUT_QUOTE_CHANGED"));
    assert.equal(sql(`SELECT count(*) FROM checkout_requests WHERE request_id='${old.requestId}';`), "0");
  } finally { sql(`UPDATE product_variants SET price=${originalPrice} WHERE id=${variant};`); }
  const originalStock = sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`);
  try {
    sql(`UPDATE inventory SET quantity=0 WHERE product_variant_id=${variant};`);
    const unavailable = await op(validationPage, GuestCheckoutPreviewSource, "GuestCheckoutPreview", { input: selection });
    assert.equal(unavailable.body.data.guestCheckoutPreview.placeable, false);
    assert.ok(unavailable.body.data.guestCheckoutPreview.issues.length);
  } finally { sql(`UPDATE inventory SET quantity=${originalStock} WHERE product_variant_id=${variant};`); }
  previous.cartAndValidation = true;
  }

  if (selected.has("customer")) {
  stage = "authenticated prefill and payment";
  const customer = await pageFor();
  await seed(customer);
  const email = await disposableCustomer(customer);
  await checkoutForm(customer, email, true);
  const customerAttempt = await place(customer, true);
  assert.ok(customerAttempt.input.checkout.shippingAddressId);
  assert.equal(customerAttempt.input.checkout.shippingAddress, null);
  assert.equal((await order(customer, customerAttempt)).status, "PENDING");
  stage = "foreign customer ownership";
  const otherCustomer = await pageFor();
  await disposableCustomer(otherCustomer);
  for (const [source, name] of [[CustomerCheckoutConfirmationSource, "CustomerCheckoutConfirmation"], [CustomerPaymentSessionSource, "CustomerPaymentSession"]]) {
    const denied = await op(otherCustomer, source, name, { requestId: customerAttempt.input.requestId });
    assert.ok(denied.body.errors?.length, "Another authenticated customer must not read or pay this order");
  }
  await otherCustomer.context().addCookies([{ name: "retail_guest_orders", value: randomBytes(32).toString("base64url"),
    domain: "127.0.0.1", path: "/graphql", httpOnly: true, sameSite: "Lax", secure: false }]);
  const guestReference = evidence.find((item) => item.name === "guest-success-replay")?.requestId;
  if (guestReference) {
    for (const [source, name] of [[GuestCheckoutConfirmationSource, "GuestCheckoutConfirmation"], [GuestPaymentSessionSource, "GuestPaymentSession"]]) {
      const denied = await op(otherCustomer, source, name, { requestId: guestReference });
      assert.ok(denied.body.errors?.length, "A validly formatted but foreign guest proof must be rejected");
    }
  }
  stage = "authenticated payment";
  await pay(customer, "4242424242424242");
  remember("customer-prefill-payment", customerAttempt, await paid(customer, customerAttempt));
  }
  if (selected.has("pending")) {
  stage = "bounded pending confirmation and explicit cancellation";
  const pendingPage = await pageFor();
  await seed(pendingPage);
  await checkoutForm(pendingPage, "sandbox-pending@example.test");
  const pendingStock = Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`));
  const pendingAttempt = await place(pendingPage);
  await hostedFrame(pendingPage);
  await pendingPage.setViewportSize({ width: 390, height: 1000 });
  await pendingPage.waitForTimeout(500);
  await layout(pendingPage, "payment-mobile");
  assert.equal(await pendingPage.getByRole("button", { name: "Pay securely", exact: true }).isDisabled(), true,
    "Incomplete hosted payment fields must block payment submission");
  let reads = 0;
  pendingPage.on("request", (request) => {
    if (new URL(request.url()).pathname === "/graphql" &&
      request.postData()?.includes('"operationName":"GuestCheckoutConfirmation"')) ++reads;
  });
  await pendingPage.goto(base + `/order-confirmation/${pendingAttempt.input.requestId}?guest=1`);
  await pendingPage.getByText("Automatic refresh has paused.", { exact: false }).waitFor({ timeout: 135_000 });
  const count = reads;
  await pendingPage.waitForTimeout(3000);
  assert.equal(reads, count, "Automatic order reads must stop");
  assert.ok(reads <= 24);
  assert.equal((await order(pendingPage, pendingAttempt)).status, "PENDING");
  pendingPage.once("dialog", (dialog) => void dialog.accept());
  await pendingPage.getByRole("button", { name: "Cancel pending order", exact: true }).click();
  await pendingPage.getByRole("heading", { name: "Order cancelled", exact: true }).waitFor();
  assert.equal(Number(sql(`SELECT quantity FROM inventory WHERE product_variant_id=${variant};`)), pendingStock,
    "Explicit cancellation must restore the original fixture allocation once");
  previous.boundedPendingReads = count;
  previous.pendingCancellation = true;
  }
  assert.deepEqual(unexpected, []);
  await writeFile(join(captures, "evidence.json"), JSON.stringify({ scenarios: evidence,
    cartAndValidation: previous.cartAndValidation, boundedPendingReads: previous.boundedPendingReads,
    pendingCancellation: previous.pendingCancellation, accountSwitch: previous.accountSwitch,
    guestRecovery: previous.guestRecovery, unexpected }, null, 2));
  console.log(`PASS normal-stack checkout checks: ${[...selected].join(", ")}. Safe evidence saved; no provider secrets recorded.`);
} catch (error) {
  if (currentPage) await currentPage.screenshot({ path: join(captures, "failure.png"), fullPage: true });
  console.error(`Checkout check failed at ${stage}: ${error.message.split("\n")[0].replace(/pi_\w+_secret_\w+/g, "[suppressed]")}`);
  await writeFile(join(captures, "evidence.json"), JSON.stringify({ scenarios: evidence, stage, incomplete: true,
    cartAndValidation: previous.cartAndValidation, boundedPendingReads: previous.boundedPendingReads,
    pendingCancellation: previous.pendingCancellation, accountSwitch: previous.accountSwitch,
    guestRecovery: previous.guestRecovery }, null, 2));
  process.exitCode = 1;
} finally {
  for (const context of contexts) await context.close();
  await browser.close();
}
