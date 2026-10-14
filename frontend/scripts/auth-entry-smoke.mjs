import assert from "node:assert/strict";
import { randomBytes, createHmac } from "node:crypto";
import { mkdir } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const base = process.env.STOREFRONT_BASE_URL;
assert.equal(base, "http://127.0.0.1:3000", "Use the authorized local stack and disposable accounts only");
const artifacts = process.env.STOREFRONT_ARTIFACTS;
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const shadow = await browser.newContext();
const page = await context.newPage();
const failures = [];
page.on("pageerror", () => failures.push("browser runtime error"));
const suffix = randomBytes(6).toString("hex");
const email = `authentry-${suffix}@bytecore.example`;
const password = `Fixture!${randomBytes(24).toString("base64url")}9`;
const changed = process.env.AUTH_ENTRY_MODE === "mfa" ? password : `Changed!${randomBytes(24).toString("base64url")}9`;
let stage = "registration fields";

async function checkLayout() {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, "Unexpected overflow");
}
async function latestMail(address, subject, previous) {
  for (let attempt = 0; attempt < 60; attempt++) {
    const inbox = await (await fetch("http://127.0.0.1:8025/api/v1/messages")).json();
    const message = inbox.messages.find((item) => item.ID !== previous &&
      item.To.some((recipient) => recipient.Address === address) && item.Subject.toLowerCase().includes(subject));
    if (message) {
      const mail = await (await fetch(`http://127.0.0.1:8025/api/v1/message/${message.ID}`)).json();
      const code = mail.Text.match(/\b[0-9]{6}\b/);
      assert.ok(code, "Genuine latest provider email must contain a code; values suppressed");
      const link = mail.Text.match(/http:\/\/127\.0\.0\.1:3000\/(?:verify|recover)\?flow=[0-9a-f-]+#code=[0-9]{6}/);
      assert.ok(link, "Genuine email must carry the bound storefront fragment link; values suppressed");
      return { code: code[0], link: link[0], id: message.ID };
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("Genuine provider email unavailable");
}
async function login(address, credential) {
  await page.goto(`${base}/login?returnTo=%2Faccount`);
  await page.getByLabel("Email", { exact: true }).fill(address);
  await page.getByLabel("Password", { exact: true }).fill(credential);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
}
async function session() {
  const response = await context.request.get(`${base}/auth/session`);
  return { status: response.status(), body: await response.json() };
}
async function logout() {
  await page.goto(`${base}/account`);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.waitForURL(`${base}/`);
  assert.equal((await session()).body.kind, "anonymous");
}
function totp(secret) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of secret) bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((byte) => parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[19] & 15;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1000000).toString().padStart(6, "0");
}
async function freshTotp(secret) {
  const remaining = 30000 - Date.now() % 30000;
  if (remaining < 3000) await new Promise((resolve) => setTimeout(resolve, remaining + 100));
  return totp(secret);
}
try {
  await page.goto(`${base}/register?returnTo=%2Faccount`);
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Username", { exact: true }).fill(`authentry${suffix}`);
  await page.getByLabel("Date of birth", { exact: true }).fill("1990-06-01");
  await page.getByLabel("Password", { exact: true }).fill(password);
  stage = "genuine CAPTCHA";
  await page.getByText("Complete the security check before creating your account.", { exact: true }).waitFor();
  await page.locator("altcha-widget").getByRole("checkbox").focus();
  await page.keyboard.press("Space");
  await page.waitForFunction(() => Boolean(document.querySelector("input[name=altcha]")?.value), null, { timeout: 90000 });
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  stage = "registration continuation";
  await page.waitForURL(/\/verify\?/);
  const verification = await latestMail(email, "verify");
  stage = "verification code";
  await page.getByLabel("Verification code", { exact: true }).fill(verification.code);
  await page.getByRole("button", { name: "Verify email", exact: true }).click();
  await page.waitForURL(/\/login\?/);
  stage = "ordinary password login";
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.waitForURL(`${base}/account`);
  const initial = await session();
  assert.equal(initial.body.kind, "authenticated");
  const userId = initial.body.user.id;
  await page.reload();
  assert.equal((await session()).body.user.id, userId);
  const shadowPage = await shadow.newPage();
  await shadowPage.goto(`${base}/login?returnTo=%2Faccount`);
  await shadowPage.getByLabel("Email", { exact: true }).fill(email);
  await shadowPage.getByLabel("Password", { exact: true }).fill(password);
  await shadowPage.getByRole("button", { name: "Sign in", exact: true }).click();
  await shadowPage.waitForURL(`${base}/account`);
  await logout();
  console.log("PASS genuine registration/CAPTCHA/verification, ordinary login/reload/logout");

  if (process.env.AUTH_ENTRY_MODE !== "mfa") {
  await page.goto(`${base}/recover`);
  stage = "recovery email";
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByRole("button", { name: "Send recovery email", exact: true }).click();
  await page.getByLabel("Recovery code", { exact: true }).waitFor();
  const first = await latestMail(email, "recover");
  stage = "invalid recovery code";
  await page.getByLabel("Recovery code", { exact: true }).fill("000000");
  await page.getByRole("button", { name: "Continue to password reset", exact: true }).click();
  await page.locator("#auth-error-summary").waitFor();
  assert.ok((await page.locator("#auth-error-summary").textContent()).includes("incorrect"));
  await page.getByRole("button", { name: "Send a new code", exact: true }).click();
  const latest = await latestMail(email, "recover", first.id);
  stage = "resend replaced proof";
  await page.getByLabel("Recovery code", { exact: true }).fill(first.code);
  await page.getByRole("button", { name: "Continue to password reset", exact: true }).click();
  await page.locator("#auth-error-summary").waitFor();
  const foreign = await browser.newContext();
  const other = await foreign.newPage();
  await other.goto(latest.link);
  await other.getByRole("heading", { name: "This form has expired" }).waitFor();
  await foreign.close();
  await page.goto(latest.link);
  stage = "genuine recovery link";
  await page.waitForFunction(() => /^\d{6}$/.test(document.querySelector("input[name=code]")?.value ?? ""));
  assert.equal(await page.evaluate(() => location.hash), "");
  const recoveryFields = await page.locator('form input[type="hidden"]').evaluateAll((inputs) =>
    Object.fromEntries(inputs.map((input) => [input.name, input.value])));
  await page.getByRole("button", { name: "Continue to password reset", exact: true }).click();
  stage = "recovery settings continuation";
  await page.waitForURL(/\/reset\?/);
  await page.getByLabel("Password", { exact: true }).waitFor();
  const consumed = await context.request.post(`${base}/auth/submit?kind=recovery`, {
    form: { ...recoveryFields, code: latest.code }, headers: { Origin: base },
  });
  assert.equal(consumed.status(), 401, "Used recovery proof must be rejected explicitly");
  assert.equal((await consumed.json()).error, "expired");
  const resetFlow = new URL(page.url()).searchParams.get("flow");
  const fields = await page.locator('form input[type="hidden"]').evaluateAll((inputs) =>
    Object.fromEntries(inputs.map((input) => [input.name, input.value])));
  const rejected = await context.request.post(`${base}/auth/submit?kind=reset`, {
    form: { ...fields, password: "short" }, headers: { Origin: base },
  });
  assert.ok(rejected.url().includes("passwordError=minimum"), "Bypassed browser policy must be rejected");
  await page.getByLabel("Password", { exact: true }).fill(changed);
  stage = "password reset";
  await page.getByRole("button", { name: "Save new password", exact: true }).click();
  await page.waitForURL(/\/login\?/);
  assert.equal((await shadow.request.get(`${base}/auth/session`)).status(), 401, "Password reset must invalidate another active storefront session");
  await login(email, password);
  stage = "old password rejection";
  await page.locator("#auth-error-summary").waitFor();
  await login(email, changed);
  stage = "new password login";
  await page.waitForURL(`${base}/account`);
  assert.equal((await session()).body.user.id, userId);
  await logout();
  await page.goto(`${base}/reset?flow=${resetFlow}`);
  await page.getByRole("heading", { name: "This form has expired" }).waitFor();
  console.log("PASS genuine recovery link/code, invalid/old proof, resend, foreign browser, reset policy, new/old password and used-flow rejection");
  }

  await login(email, changed);
  await page.waitForURL(`${base}/account`);
  await page.getByRole("link", { name: "Authenticator and backup codes" }).click();
  stage = "MFA setup";
  await page.getByLabel("Authenticator code", { exact: true }).waitFor();
  const key = await page.locator("code").textContent();
  assert.match(key, /^[A-Z2-7]+$/);
  await page.getByLabel("Authenticator code", { exact: true }).fill("000000");
  await page.getByRole("button", { name: "Confirm authenticator", exact: true }).click();
  stage = "MFA invalid code";
  await page.getByText("The change was not accepted. Check your authenticator code and try again.", { exact: true }).waitFor();
  await page.getByLabel("Authenticator code", { exact: true }).fill(await freshTotp(key));
  await page.getByRole("button", { name: "Confirm authenticator", exact: true }).click();
  stage = "MFA enrollment confirmation";
  await page.getByRole("button", { name: "Disable authenticator", exact: true }).waitFor();
  await page.getByRole("button", { name: "Generate backup codes", exact: true }).click();
  stage = "backup generation";
  await page.getByRole("button", { name: "Activate saved backup codes", exact: true }).waitFor();
  const codes = await page.getByRole("list", { name: "Backup codes" }).locator("li").allTextContents();
  assert.ok(codes.length >= 2, "Provider-issued backup codes required");
  await page.getByLabel("I saved these codes in a safe place.").check();
  await page.getByRole("button", { name: "Activate saved backup codes", exact: true }).click();
  stage = "backup activation";
  await page.getByRole("button", { name: "Disable backup codes", exact: true }).waitFor();
  await checkLayout();
  if (artifacts) {
    await mkdir(artifacts, { recursive: true });
    await page.goto(`${base}/auth/security`);
    await page.getByRole("heading", { name: "Account security", exact: true }).waitFor();
    await page.screenshot({ path: `${artifacts}/security-enabled-desktop.png`, fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await checkLayout();
    await page.screenshot({ path: `${artifacts}/security-enabled-mobile.png`, fullPage: true });
    await page.evaluate(() => document.documentElement.style.zoom = "2");
    await checkLayout();
    await page.evaluate(() => document.documentElement.style.zoom = "");
    await page.setViewportSize({ width: 1440, height: 1000 });
  }
  await logout();
  stage = "MFA subsequent password login";
  await login(email, changed);
  await page.getByRole("heading", { name: "Two-step verification", exact: true }).waitFor();
  await page.getByLabel("Authenticator code", { exact: true }).fill("000000");
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  stage = "MFA invalid challenge";
  await page.locator("#auth-error-summary").waitFor();
  await page.getByLabel("Authenticator code", { exact: true }).fill(await freshTotp(key));
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  stage = "MFA valid challenge";
  await page.waitForURL(`${base}/account`);
  assert.equal((await session()).body.user.id, userId);
  await page.reload();
  assert.equal((await session()).body.user.id, userId);
  stage = "MFA forced reauthentication";
  await page.goto(`${base}/auth/security-reauth`);
  await page.getByRole("heading", { name: "Two-step verification", exact: true }).waitFor();
  await page.getByLabel("Authenticator code", { exact: true }).fill(await freshTotp(key));
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  await page.getByRole("heading", { name: "Account security", exact: true }).waitFor();
  assert.equal((await session()).body.user.id, userId);
  await logout();
  stage = "backup password login";
  await login(email, changed);
  await page.getByRole("heading", { name: "Two-step verification", exact: true }).waitFor();
  await page.getByLabel("Recovery code", { exact: true }).fill(codes[0]);
  await page.getByRole("button", { name: "Use recovery code", exact: true }).click();
  stage = "backup login";
  await page.waitForURL(`${base}/account`);
  await logout();
  await login(email, changed);
  await page.getByRole("heading", { name: "Two-step verification", exact: true }).waitFor();
  await page.getByLabel("Recovery code", { exact: true }).fill(codes[0]);
  await page.getByRole("button", { name: "Use recovery code", exact: true }).click();
  stage = "used backup code rejected";
  await page.locator("#auth-error-summary").waitFor();
  await page.getByLabel("Authenticator code", { exact: true }).fill(await freshTotp(key));
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  await page.waitForURL(`${base}/account`);
  await page.goto(`${base}/auth/security`);
  await page.getByLabel("I understand that removing my authenticator reduces account protection. Backup codes remain enabled until removed separately.").check();
  await page.getByRole("button", { name: "Disable authenticator", exact: true }).click();
  stage = "authenticator disable";
  await page.getByRole("button", { name: "Confirm authenticator", exact: true }).waitFor();
  await page.getByLabel("I understand these codes will stop working. My authenticator stays enabled until removed separately.").check();
  await page.getByRole("button", { name: "Disable backup codes", exact: true }).click();
  stage = "backup disable";
  await page.getByRole("button", { name: "Generate backup codes", exact: true }).waitFor();
  await logout();
  await login(email, changed);
  await page.waitForURL(`${base}/account`);
  await logout();
  stage = "same-browser registration and switching";
  const emailB = `authentry-${randomBytes(6).toString("hex")}@bytecore.example`;
  await page.goto(`${base}/register?returnTo=%2Faccount`);
  await page.getByLabel("Email", { exact: true }).fill(emailB);
  await page.getByLabel("Username", { exact: true }).fill(`authentryb${suffix}`);
  await page.getByLabel("Date of birth", { exact: true }).fill("1990-06-01");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByText("Complete the security check before creating your account.", { exact: true }).waitFor();
  await page.locator("altcha-widget").getByRole("checkbox").focus();
  await page.keyboard.press("Space");
  await page.waitForFunction(() => Boolean(document.querySelector("input[name=altcha]")?.value), null, { timeout: 90000 });
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await page.waitForURL(/\/verify\?/);
  const mailB = await latestMail(emailB, "verify");
  await page.getByLabel("Verification code", { exact: true }).fill(mailB.code);
  await page.getByRole("button", { name: "Verify email", exact: true }).click();
  await page.waitForURL(/\/login\?/);
  await page.getByLabel("Email", { exact: true }).fill(emailB);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.waitForURL(`${base}/account`);
  assert.notEqual((await session()).body.user.id, userId);
  await logout();
  await login(email, changed);
  await page.waitForURL(`${base}/account`);
  assert.equal((await session()).body.user.id, userId);
  await logout();
  console.log("PASS same-browser A/logout/register B/login B/logout/login A with correct identities");

  stage = "native no-JavaScript recovery";
  const native = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const nativePage = await native.newPage();
  await nativePage.goto(`${base}/recover`);
  if (artifacts) await nativePage.screenshot({ path: `${artifacts}/recovery-mobile-native.png`, fullPage: true });
  await nativePage.getByLabel("Email", { exact: true }).fill(emailB);
  await nativePage.getByRole("button", { name: "Send recovery email", exact: true }).click();
  await nativePage.getByLabel("Recovery code", { exact: true }).waitFor();
  const nativeMail = await latestMail(emailB, "recover");
  await nativePage.getByLabel("Recovery code", { exact: true }).fill(nativeMail.code);
  await nativePage.getByRole("button", { name: "Continue to password reset", exact: true }).click();
  await nativePage.getByLabel("Password", { exact: true }).waitFor();
  await nativePage.getByLabel("Password", { exact: true }).fill(changed);
  await nativePage.getByRole("button", { name: "Save new password", exact: true }).click();
  await nativePage.waitForURL(/\/login\?/);
  await nativePage.getByLabel("Email", { exact: true }).fill(emailB);
  await nativePage.getByLabel("Password", { exact: true }).fill(changed);
  await nativePage.getByRole("button", { name: "Sign in", exact: true }).click();
  const continuation = nativePage.getByRole("button", { name: /continue|submit/i });
  await Promise.race([
    nativePage.waitForURL(`${base}/`),
    continuation.first().waitFor(),
  ]);
  if (nativePage.url() !== `${base}/`) await continuation.first().click();
  await nativePage.waitForURL(`${base}/`);
  assert.equal((await (await native.request.get(`${base}/auth/session`)).json()).kind, "authenticated");
  await native.close();
  console.log("PASS native no-JavaScript mobile recovery/reset/login");
  assert.equal(failures.length, 0, "Browser runtime errors occurred; details suppressed");
  console.log("PASS genuine TOTP enrollment, invalid-code rejection, login, reload, backup-code login and secure factor disable");
} catch (error) {
  console.error("FAIL focused auth entry check", stage, error.name, "at", new URL(page.url()).pathname,
    "reason", new URL(page.url()).searchParams.get("reason") ?? "none",
    "selector conflict", error.message.includes("strict mode violation"), "alerts", await page.getByRole("alert").count(),
    "private values suppressed");
  process.exitCode = 1;
} finally {
  await context.close();
  await shadow.close();
  await browser.close();
}
