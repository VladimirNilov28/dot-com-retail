import assert from "node:assert/strict";
import { createHmac, randomBytes } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { authConfig } from "../src/lib/auth/config";
import { database, identifierHash, withBrowser, closeDatabase } from "../src/lib/auth/store";
import { providerCookie } from "../src/lib/auth/provider";

const base = "http://127.0.0.1:3000";
assert.equal(authConfig().origin, base);
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.setDefaultTimeout(35000);
const suffix = randomBytes(6).toString("hex");
const email = `auth-regression-${suffix}@example.test`;
const password = `Fixture!${randomBytes(24).toString("base64url")}9`;
const errors = [];
page.on("pageerror", () => errors.push("runtime"));
page.on("console", (message) => {
  if (message.type() === "error" && message.text().includes("Account status could not be loaded")) errors.push("header");
});
let stage = "registration";
let key;
const checks = [];

async function mailCode(subject) {
  for (let read = 0; read < 60; read++) {
    const inbox = await (await fetch("http://127.0.0.1:8025/api/v1/messages")).json();
    const message = inbox.messages.find((item) => item.To.some((to) => to.Address === email) &&
      item.Subject.toLowerCase().includes(subject));
    if (message) {
      const mail = await (await fetch(`http://127.0.0.1:8025/api/v1/message/${message.ID}`)).json();
      const code = mail.Text.match(/\b[0-9]{6}\b/)?.[0];
      if (code) return code;
    }
    await page.waitForTimeout(500);
  }
  throw new Error("Disposable account email code unavailable");
}
function totp() {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const bits = [...key].map((character) => alphabet.indexOf(character).toString(2).padStart(5, "0")).join("");
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const digest = createHmac("sha1", Buffer.from(bits.match(/.{8}/g).map((byte) => parseInt(byte, 2)))).update(counter).digest();
  return ((digest.readUInt32BE(digest[19] & 15) & 0x7fffffff) % 1000000).toString().padStart(6, "0");
}
async function session() {
  const response = await context.request.get(`${base}/auth/session`);
  return { status: response.status(), body: await response.json() };
}
async function logout() {
  await page.goto(`${base}/account`);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.waitForURL(base + "/");
}
async function login() {
  await page.goto(`${base}/login?returnTo=%2Faccount`);
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
}
async function pending() {
  await login();
  await page.getByRole("heading", { name: "Two-step verification", exact: true }).waitFor();
  const form = await page.locator("form.auth-form").evaluate((element) => Object.fromEntries(new FormData(element)));
  const cookie = (await context.cookies()).find((item) => item.name === authConfig().flowCookie);
  assert.ok(cookie);
  return { form, cookie, url: page.url(),
    provider: await withBrowser(cookie.value, async (state) => providerCookie(state)) };
}
async function rejected(attempt) {
  const response = await context.request.post(`${base}/auth/submit?kind=login`, {
    form: { ...attempt.form, totp_code: totp() }, headers: { Origin: base, Accept: "application/json" },
  });
  assert.ok([401, 403].includes(response.status()), "Cancelled or foreign submissions must fail authoritatively");
}
async function providerRevoked(attempt) {
  const response = await fetch(`${authConfig().kratos}/sessions/whoami`, {
    headers: { Cookie: attempt.provider, Accept: "application/json" },
  });
  assert.equal(response.status, 401, "Cancelled AAL1 provider session must also be revoked");
}
try {
  await page.goto(`${base}/register?returnTo=%2Faccount`);
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Username", { exact: true }).fill(`authregression${suffix}`);
  await page.getByLabel("Date of birth", { exact: true }).fill("1990-06-01");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByText("Complete the security check before creating your account.", { exact: true }).waitFor();
  await page.locator("altcha-widget").getByRole("checkbox").focus();
  await page.keyboard.press("Space");
  await page.waitForFunction(() => Boolean(document.querySelector("input[name=altcha]")?.value), null, { timeout: 90000 });
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await page.getByLabel("Verification code", { exact: true }).fill(await mailCode("verify"));
  await page.getByRole("button", { name: "Verify email", exact: true }).click();
  await page.getByLabel("Email", { exact: true }).waitFor();
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.waitForURL(base + "/account");
  const user = (await session()).body.user.id;
  stage = "disposable MFA enrollment";
  await page.goto(`${base}/account/security`);
  await page.getByRole("button", { name: "Email me a code", exact: true }).click();
  await page.getByLabel("Email code", { exact: true }).fill(await mailCode("code"));
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  await page.getByLabel("6-digit code from the app", { exact: true }).waitFor();
  key = await page.locator("code").textContent();
  assert.match(key, /^[A-Z2-7]+$/);
  await page.getByLabel("6-digit code from the app", { exact: true }).fill(totp());
  await page.getByRole("button", { name: "Turn on authenticator", exact: true }).click();
  await page.getByRole("button", { name: "Remove authenticator", exact: true }).waitFor();
  await logout();

  stage = "valid in-flow refresh and validation";
  const a = await pending();
  assert.equal((await session()).body.kind, "anonymous", "AAL1 must not be a storefront login");
  await page.reload();
  await page.getByLabel("Authenticator code", { exact: true }).waitFor();
  assert.equal(new URL(page.url()).searchParams.get("flow"), a.form.flow);
  await page.getByLabel("Authenticator code", { exact: true }).fill("000000");
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  await page.locator("#auth-error-summary").waitFor();
  await page.getByLabel("Authenticator code", { exact: true }).fill(totp());
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  await page.waitForURL(base + "/account");
  assert.equal((await session()).body.user.id, user);
  checks.push("native-password-TOTP-refresh-validation-completion");

  stage = "established-session step-up cancellation";
  const established = (await context.cookies()).find((item) => item.name === authConfig().sessionCookie).value;
  await page.goto(`${base}/auth/security-reauth?section=%2Faccount%2Fsecurity`);
  await page.getByRole("button", { name: "Cancel verification", exact: true }).click();
  await page.waitForURL(/\/account\/security/);
  assert.equal((await context.cookies()).find((item) => item.name === authConfig().sessionCookie).value, established);
  assert.equal((await session()).body.user.id, user);
  checks.push("step-up-cancel-preserves-established-session");
  stage = "fresh login cancellation preserves an unrelated established session";
  const replacement = await pending();
  await page.getByRole("button", { name: "Use another account", exact: true }).click();
  await page.getByLabel("Email", { exact: true }).waitFor();
  await providerRevoked(replacement);
  assert.equal((await context.cookies()).find((item) => item.name === authConfig().sessionCookie).value, established);
  assert.equal((await session()).body.user.id, user);
  checks.push("fresh-login-cancel-revokes-only-provisional-session");
  await logout();

  stage = "TOTP header/home abandonment and history";
  const abandoned = await pending();
  await page.goto(abandoned.url);
  await page.getByLabel("Authenticator code", { exact: true }).waitFor();
  if (process.env.STOREFRONT_ARTIFACTS) {
    await mkdir(process.env.STOREFRONT_ARTIFACTS, { recursive: true });
    await page.screenshot({ path: `${process.env.STOREFRONT_ARTIFACTS}/totp-cancel-desktop.png`, fullPage: true });
    await page.setViewportSize({ width: 390, height: 1000 });
    await page.screenshot({ path: `${process.env.STOREFRONT_ARTIFACTS}/totp-cancel-mobile.png`, fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.setViewportSize({ width: 1440, height: 1000 });
  }
  await page.getByRole("link", { name: "ByteCore", exact: true }).click();
  await page.waitForURL(base + "/");
  await providerRevoked(abandoned);
  await rejected(abandoned);
  stage = "cancelled TOTP browser Back/Forward";
  await page.goBack();
  await page.getByRole("heading", { name: "This form has expired", exact: true }).waitFor();
  assert.equal(await page.getByLabel("Authenticator code", { exact: true }).count(), 0);
  await page.goForward();
  assert.equal((await session()).body.kind, "anonymous");
  await page.goto(base + "/checkout");
  assert.equal((await session()).body.kind, "anonymous");
  checks.push("header-home-abandonment-provider-revocation-history-anonymous-checkout");

  stage = "direct navigation and another-account action";
  const direct = await pending();
  await page.goto(base + "/cart");
  await providerRevoked(direct);
  await rejected(direct);
  const cancelled = await pending();
  await page.getByRole("button", { name: "Use another account", exact: true }).click();
  await page.getByLabel("Email", { exact: true }).waitFor();
  await providerRevoked(cancelled);
  await rejected(cancelled);
  const current = await page.locator("form.auth-form").first().evaluate((element) => Object.fromEntries(new FormData(element)));
  const check = await context.request.post(base + "/auth/check-attempt", {
    form: { flow: current.flow, _csrf: current._csrf }, headers: { Origin: base },
  });
  assert.equal(check.status(), 200, "A stale cancellation/submission must not cancel the new attempt");
  checks.push("direct-navigation-explicit-cancel-stale-submission-new-attempt");

  stage = "expired attempt and Google outgoing account selection";
  const expired = await pending();
  await database().query("UPDATE browser_sessions SET expires_at=now()-interval '1 second' WHERE id_hash=$1 AND authenticated=false",
    [identifierHash(expired.cookie.value)]);
  await rejected(expired);
  await page.goto(base + "/login");
  await page.getByLabel("Email", { exact: true }).waitFor();
  await page.route("https://accounts.google.com/**", async (route) => {
    await route.fulfill({ contentType: "text/plain", body: "Provider redirect observed; owner account selection required." });
  });
  await page.getByRole("button", { name: /Google/ }).click();
  await page.waitForURL("https://accounts.google.com/**");
  const google = new URL(page.url());
  assert.equal(google.searchParams.get("prompt"), "select_account", "Actual outgoing authorization must request account selection");
  const oldCallback = google.searchParams.get("state");
  await page.goto(base + "/");
  await page.goto(base + "/login");
  await page.getByLabel("Email", { exact: true }).waitFor();
  const late = await context.request.get(base + "/self-service/methods/oidc/callback/google?" +
    new URLSearchParams({ state: oldCallback, code: "not-a-real-Google-code" }), { headers: { Accept: "application/json" } });
  assert.ok([401, 403].includes(late.status()), "Late foreign Google callback must be rejected before contacting Google");
  assert.equal((await session()).body.kind, "anonymous");
  await page.goto(base + "/checkout");
  checks.push("expired-attempt-fresh-Google-select-account-outgoing-late-callback-rejected");
  assert.deepEqual(errors, []);
  if (process.env.STOREFRONT_ARTIFACTS) {
    await writeFile(`${process.env.STOREFRONT_ARTIFACTS}/auth-evidence.json`,
      JSON.stringify({ checks, GoogleConsentAndSelectedIdentityTOTP: "owner-interactive-check-required", errors }, null, 2));
  }
  console.log(`PASS focused native authentication regressions: ${checks.length}; real Google consent/selected-identity TOTP not claimed.`);
} catch (error) {
  console.error(`Authentication regression failed at ${stage}: ${error.message.split("\n")[0]}`);
  process.exitCode = 1;
} finally {
  await context.close();
  await browser.close();
  await closeDatabase();
}
