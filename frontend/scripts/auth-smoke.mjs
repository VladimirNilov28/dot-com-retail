import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const base = process.env.STOREFRONT_BASE_URL;
assert.equal(base, "http://127.0.0.1:3300", "Authentication writes must use the isolated stack");
const captures = process.env.STOREFRONT_ARTIFACTS;
assert.ok(captures);
await mkdir(captures, { recursive: true });
const python = process.env.AUTH_FIXTURE_PYTHON;
assert.ok(python);
const browser = await chromium.launch({ headless: true });
const unexpected = [];
const secrets = new Set();
let currentPage;
let zoom;
let temporary;

function observe(page) {
  page.on("pageerror", () => unexpected.push("pageerror"));
  page.on("console", (message) => {
    if (message.type() === "error" && !(
      message.location().url.includes("/auth/session") &&
      /Failed to load resource.*401/.test(message.text())
    )) unexpected.push("console error");
  });
}
async function layout(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  const exposed = await page.evaluate(() => ({
    html: document.documentElement.outerHTML, url: location.href,
    cookies: document.cookie, local: JSON.stringify(localStorage), session: JSON.stringify(sessionStorage),
  }));
  for (const secret of secrets) {
    assert.ok(!Object.values(exposed).some((value) => value.includes(secret)), "Credential exposure; values suppressed");
  }
}
async function capture(page, name) {
  await layout(page);
  await page.screenshot({ path: join(captures, `${name}.png`), fullPage: true });
}
async function mailCode(email) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const inbox = await (await fetch("http://127.0.0.1:28025/api/v1/messages")).json();
    const message = inbox.messages.find((item) => item.To.some((recipient) => recipient.Address === email));
    if (message) {
      const mail = await (await fetch(`http://127.0.0.1:28025/api/v1/message/${message.ID}`)).json();
      const code = mail.Text.match(/\b[0-9]{6}\b/);
      assert.ok(code, "A genuine courier verification code is required");
      secrets.add(code[0]);
      return code[0];
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("Genuine verification email was not delivered");
}
async function signIn(page, email, password) {
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
}
async function securityCheck(page) {
  const started = Date.now();
  const checkbox = page.locator("altcha-widget").getByRole("checkbox");
  await checkbox.focus();
  await page.keyboard.press("Space");
  await page.waitForFunction(() => Boolean(document.querySelector("input[name=altcha]")?.value), null, { timeout: 90000 });
  return Date.now() - started;
}
async function registerFields(page, email, username, password) {
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Username", { exact: true }).fill(username);
  await page.getByLabel("Date of birth", { exact: true }).fill("1990-06-01");
  await page.getByLabel("Password", { exact: true }).fill(password);
}
async function enroll(email, password) {
  const result = JSON.parse(execFileSync(python, ["-c", `
import json,sys
sys.path.insert(0, "../infrastructure/oauth/service/tests/real")
import totp_flow_test as fixture
fixture.KRATOS="http://127.0.0.1:24433"
client=fixture.RealTotpFlowTest()
client.setUp()
data=json.load(sys.stdin)
token=client.password_login(data["email"],data["password"])
secret=client.enroll(token)
completed=token
session=client.http("GET",fixture.KRATOS+"/sessions/whoami",completed)
client.require_status(session,200)
client.assertEqual(session.json()["authenticator_assurance_level"],"aal2")
flow=client.settings(completed)
response=client.update_settings(flow,completed,{"method":"lookup_secret","lookup_secret_regenerate":True})
client.require_status(response,200)
codes=client.lookup_codes(response.json())
client.require_status(client.update_settings(response.json(),completed,{"method":"lookup_secret","lookup_secret_confirm":True}),200)
client.revoke_test_credentials()
sys.stdout.write(json.dumps({"secret":secret,"recovery":codes[0]}))
`], { input: JSON.stringify({ email, password }), encoding: "utf8" }));
  secrets.add(result.secret);
  secrets.add(result.recovery);
  return result;
}
function totp(secret) {
  const result = execFileSync(python, ["-c",
    "import json,sys,time,pyotp;v=json.load(sys.stdin);remaining=30-time.time()%30;"
    + "time.sleep(remaining+1 if remaining<5 else 0);sys.stdout.write(pyotp.TOTP(v['secret']).now())"],
  { input: JSON.stringify({ secret }), encoding: "utf8" });
  secrets.add(result);
  return result;
}

try {
  const suffix = randomBytes(6).toString("hex");
  const email = `review-${suffix}@bytecore.example`;
  const username = `review${suffix}`;
  const password = `Fixture!${randomBytes(24).toString("base64url")}9`;
  secrets.add(password);
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  currentPage = page;
  observe(page);
  await page.goto(`${base}/login?returnTo=%2Faccount`);
  await page.getByRole("heading", { name: "Sign in", exact: true }).waitFor();
  await capture(page, "issue-66-login-desktop-1440-default");
  await page.setViewportSize({ width: 390, height: 1000 });
  await capture(page, "issue-66-login-mobile-390-default");
  await page.getByRole("link", { name: "Create an account", exact: true }).click();
  await page.getByRole("heading", { name: "Create your account", exact: true }).waitFor();
  await capture(page, "issue-67-register-mobile-390-default");
  const flow = new URL(page.url()).searchParams.get("flow");
  const foreign = await browser.newContext();
  const foreignPage = await foreign.newPage();
  observe(foreignPage);
  await foreignPage.goto(page.url());
  await foreignPage.getByRole("heading", { name: "This session has expired", exact: true }).waitFor();
  assert.equal((await foreign.request.get(`${base}/auth/captcha?flow=${flow}`)).status(), 401);
  await foreign.close();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await capture(page, "issue-67-register-desktop-1440-default");
  await registerFields(page, email, username, "short");
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  assert.equal(new URL(page.url()).pathname, "/register");
  assert.match(await page.locator("main").textContent(), /Complete the security check/);
  await securityCheck(page);
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await page.locator("#auth-error-summary").waitFor();
  assert.equal(await page.getByLabel("Password", { exact: true }).inputValue(), "");
  await capture(page, "issue-67-register-desktop-1440-validation");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await securityCheck(page);
  const pendingSnapshots = [];
  const pendingLog = (message) => {
    if (message.text().startsWith("bytecore-native-pending:")) {
      pendingSnapshots.push(JSON.parse(message.text().slice("bytecore-native-pending:".length)));
    }
  };
  page.on("console", pendingLog);
  await page.evaluate(() => {
    document.addEventListener("submit", (event) => {
      const form = event.target;
      const button = form.querySelector("button[type=submit]");
      console.info("bytecore-native-pending:" + JSON.stringify({
        busy: form.getAttribute("aria-busy"), disabled: button.disabled, text: button.textContent,
      }));
    }, { once: true });
  });
  await page.route("**/auth/submit?kind=registration*", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    await route.continue();
  });
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  page.off("console", pendingLog);
  assert.deepEqual(pendingSnapshots, [{ busy: "true", disabled: true, text: "Submitting..." }]);
  await page.getByRole("heading", { name: "Verify your email", exact: true }).waitFor();
  await page.unroute("**/auth/submit?kind=registration*");
  assert.equal((await context.request.get(`${base}/auth/session`)).status(), 200);
  assert.equal((await (await context.request.get(`${base}/auth/session`)).json()).kind, "anonymous");
  await capture(page, "issue-67-verify-desktop-1440-default");
  await page.setViewportSize({ width: 390, height: 1000 });
  await capture(page, "issue-67-verify-mobile-390-default");
  await page.getByLabel("Verification code", { exact: true }).fill("not-a-code");
  await page.getByRole("button", { name: "Verify email", exact: true }).click();
  await page.locator("#auth-error-summary").waitFor();
  assert.equal(await page.getByLabel("Verification code", { exact: true }).inputValue(), "");
  await page.getByLabel("Verification code", { exact: true }).fill(await mailCode(email));
  await page.getByRole("button", { name: "Verify email", exact: true }).click();
  await page.getByRole("heading", { name: "Sign in", exact: true }).waitFor();
  await signIn(page, email, "ThisIsNotThePassword!19");
  await page.locator("#auth-error-summary").waitFor();
  await page.waitForFunction(() => document.activeElement?.id === "auth-error-summary");
  assert.match(await page.locator("#auth-error-summary").textContent(), /Email or password is incorrect/);
  assert.equal(await page.getByLabel("Password", { exact: true }).inputValue(), "");
  await capture(page, "issue-66-login-mobile-390-invalid");
  await page.setViewportSize({ width: 1440, height: 1000 });
  await capture(page, "issue-66-login-desktop-1440-invalid");
  await signIn(page, email, password);
  await page.getByRole("heading", { name: "Your account", exact: true }).waitFor();
  assert.match(await page.locator("main").textContent(), new RegExp(username));
  const state = await (await context.request.get(`${base}/auth/session`)).json();
  assert.equal(state.kind, "authenticated");
  assert.equal(state.user.username, username);
  await capture(page, "issue-40-account-desktop-1440-authenticated");
  await page.getByRole("button", { name: "Account", exact: true }).click();
  await page.getByRole("dialog", { name: "Account navigation", exact: true }).waitFor();
  await page.getByRole("dialog", { name: "Account navigation", exact: true }).evaluate(async (element) => {
    const animations = [];
    for (let node = element; node; node = node.parentElement) animations.push(...node.getAnimations());
    await Promise.all(animations.map((animation) => animation.finished));
  });
  await capture(page, "issue-66-account-popover-desktop-1440-authenticated");
  await page.keyboard.press("Escape");
  const second = await browser.newContext({ viewport: { width: 390, height: 1000 } });
  const secondPage = await second.newPage();
  observe(secondPage);
  await secondPage.goto(`${base}/register?returnTo=%2Faccount`);
  const otherEmail = `other-${suffix}@bytecore.example`;
  const otherName = `other${suffix}`;
  await registerFields(secondPage, otherEmail, otherName, password);
  await securityCheck(secondPage);
  await secondPage.getByRole("button", { name: "Create account", exact: true }).click();
  await secondPage.getByRole("heading", { name: "Verify your email", exact: true }).waitFor();
  await secondPage.getByLabel("Verification code", { exact: true }).fill(await mailCode(otherEmail));
  await secondPage.getByRole("button", { name: "Verify email", exact: true }).click();
  await secondPage.getByRole("heading", { name: "Sign in", exact: true }).waitFor();
  await signIn(secondPage, otherEmail, password);
  await secondPage.getByRole("heading", { name: "Your account", exact: true }).waitFor();
  const other = await (await second.request.get(`${base}/auth/session`)).json();
  assert.equal(other.user.username, otherName);
  assert.notEqual(other.user.id, state.user.id);
  const original = await (await context.request.get(`${base}/auth/session?userId=${other.user.id}`)).json();
  assert.equal(original.user.id, state.user.id);
  assert.equal((await second.request.post(`${base}/auth/logout`, {
    form: { _csrf: state.csrf }, headers: { Origin: base },
  })).status(), 403);
  await secondPage.getByRole("button", { name: "Sign out", exact: true }).click();
  await secondPage.waitForURL(`${base}/`);
  await second.close();
  assert.equal((await (await context.request.get(`${base}/auth/session`)).json()).user.id, state.user.id);
  assert.equal((await context.request.post(`${base}/auth/logout`, {
    form: { _csrf: state.csrf }, headers: { Origin: "https://foreign.invalid" },
  })).status(), 403);
  assert.equal((await (await context.request.get(`${base}/auth/session`)).json()).kind, "authenticated");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.waitForURL(`${base}/`);
  assert.equal((await (await context.request.get(`${base}/auth/session`)).json()).kind, "anonymous");
  console.log("PASS real browser registration, keyboard CAPTCHA, genuine courier verification, login validation, safe return, account, CSRF and logout");
  console.log("PASS weak-password/provider validation, invalid verification code, pending/duplicate-submit protection and two authenticated browser contexts");

  await page.goto(`${base}/register?returnTo=%2Faccount`);
  await registerFields(page, email, `duplicate${suffix}`, password);
  await securityCheck(page);
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await page.locator("#auth-error-summary").waitFor();
  assert.equal((await (await context.request.get(`${base}/auth/session`)).json()).kind, "anonymous");
  await page.setViewportSize({ width: 390, height: 1000 });
  await capture(page, "issue-67-register-mobile-390-duplicate");
  console.log("PASS existing-user registration remains a safe provider error without authenticating or granting roles");
  await page.goto(`${base}/login?returnTo=%2Faccount`);
  await page.getByRole("heading", { name: "Sign in", exact: true }).waitFor();
  const expiredFlow = new URL(page.url()).searchParams.get("flow");
  assert.match(expiredFlow, /^[0-9a-f-]{36}$/);
  execFileSync("docker", ["exec", "bytecore-auth-eb7151-postgres-1", "psql", "-U", "auth_fixture",
    "-d", "kratos", "-v", "ON_ERROR_STOP=1", "-c",
    `UPDATE selfservice_login_flows SET expires_at=now()-interval '1 minute' WHERE id='${expiredFlow}';`],
  { stdio: "ignore" });
  await page.reload();
  await page.getByRole("heading", { name: "This session has expired", exact: true }).waitFor();
  assert.equal(new URL(await page.getByRole("link", { name: "Start again", exact: true }).getAttribute("href"), base)
    .searchParams.get("returnTo"), "/account");
  await capture(page, "issue-66-login-mobile-390-expired");
  await page.goto(`${base}/login?flow=invalid-flow&returnTo=%2Faccount`);
  await page.getByRole("heading", { name: "We couldn't complete this request", exact: true }).waitFor();
  assert.equal((await page.getByRole("link", { name: "Start again", exact: true }).getAttribute("href")).includes("returnTo=%2Faccount"), true);
  const kratosContainer = "bytecore-auth-eb7151-kratos-1";
  assert.equal(execFileSync("docker", ["inspect", "--format",
    '{{index .Config.Labels "com.docker.compose.project"}}', kratosContainer], { encoding: "utf8" }).trim(), "bytecore-auth-eb7151");
  execFileSync("docker", ["pause", kratosContainer], { stdio: "ignore" });
  try {
    await page.goto(`${base}/register?returnTo=%2Faccount`);
    await page.getByRole("heading", { name: "Registration is unavailable", exact: true }).waitFor();
    await capture(page, "issue-67-register-mobile-390-unavailable");
  } finally {
    execFileSync("docker", ["unpause", kratosContainer], { stdio: "ignore" });
  }
  await page.getByRole("link", { name: "Retry", exact: true }).click();
  await page.getByRole("heading", { name: "Create your account", exact: true }).waitFor();
  console.log("PASS actual expired/invalid provider flows, preserved return context, service failure and real Retry");

  const factor = await enroll(email, password);
  await page.goto(`${base}/login?returnTo=%2Faccount`);
  await signIn(page, email, password);
  await page.getByRole("heading", { name: "Two-step verification", exact: true }).waitFor();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await capture(page, "issue-66-login-desktop-1440-second-factor");
  await page.setViewportSize({ width: 390, height: 1000 });
  await capture(page, "issue-66-login-mobile-390-second-factor");
  await page.getByLabel("Authenticator code", { exact: true }).fill("not-a-code");
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  await page.locator("#auth-error-summary").waitFor();
  assert.equal((await (await context.request.get(`${base}/auth/session`)).json()).kind, "anonymous");
  await page.getByLabel("Authenticator code", { exact: true }).fill(totp(factor.secret));
  await page.getByRole("button", { name: "Verify code", exact: true }).click();
  await page.getByRole("heading", { name: "Your account", exact: true }).waitFor();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.waitForURL(`${base}/`);
  console.log("PASS enrolled second-factor challenge, invalid code rejection and real TOTP success");

  const native = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 1000 } });
  const nativePage = await native.newPage();
  currentPage = nativePage;
  await nativePage.goto(`${base}/login?returnTo=%2Faccount`);
  await signIn(nativePage, email, password);
  await nativePage.getByRole("heading", { name: "Two-step verification", exact: true }).waitFor();
  await nativePage.getByLabel("Recovery code", { exact: true }).fill(factor.recovery);
  await nativePage.getByRole("button", { name: "Use recovery code", exact: true }).click();
  const continuation = nativePage.getByRole("button", { name: /continue|submit/i });
  if (await continuation.count()) await continuation.first().click();
  await nativePage.getByRole("heading", { name: "Your account", exact: true }).waitFor();
  await nativePage.getByRole("button", { name: "Sign out", exact: true }).click();
  await nativePage.waitForURL(`${base}/`);
  await nativePage.goto(`${base}/register`);
  assert.match(await nativePage.locator("main").textContent(), /Creating an account requires JavaScript/);
  await layout(nativePage);
  await native.close();
  await context.close();
  temporary = await mkdtemp(join(tmpdir(), "bytecore-auth-zoom-"));
  const extension = join(temporary, "extension");
  await mkdir(extension);
  await writeFile(join(extension, "manifest.json"), JSON.stringify({
    manifest_version: 3, name: "Authentication zoom verification", version: "1.0",
    permissions: ["tabs"], background: { service_worker: "worker.js" },
  }));
  await writeFile(join(extension, "worker.js"), "chrome.runtime.onInstalled.addListener(() => {});");
  zoom = await chromium.launchPersistentContext(join(temporary, "profile"), {
    channel: "chromium", headless: true, viewport: { width: 1440, height: 1000 },
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
  });
  const worker = zoom.serviceWorkers()[0] ?? await zoom.waitForEvent("serviceworker");
  const zoomPage = await zoom.newPage();
  currentPage = zoomPage;
  observe(zoomPage);
  await zoomPage.goto(`${base}/login`);
  const initial = await zoomPage.evaluate(() => ({ width: innerWidth, dpr: devicePixelRatio }));
  await worker.evaluate(async (base) => {
    const tab = (await chrome.tabs.query({})).find((tab) => tab.url.startsWith(base));
    await chrome.tabs.setZoom(tab.id, 2);
    if (await chrome.tabs.getZoom(tab.id) !== 2) throw new Error("Actual browser zoom did not apply");
  }, base);
  await zoomPage.waitForFunction((before) => innerWidth === before.width / 2 && devicePixelRatio === before.dpr * 2, initial);
  for (const width of [1440, 390]) {
    await zoomPage.setViewportSize({ width, height: 1000 });
    for (const [route, heading] of [["login", "Sign in"], ["register", "Create your account"]]) {
      await zoomPage.goto(`${base}/${route}?returnTo=%2Faccount`);
      await zoomPage.getByRole("heading", { name: heading, exact: true }).waitFor();
      await zoomPage.getByLabel("Email", { exact: true }).focus();
      assert.equal(await zoomPage.getByLabel("Email", { exact: true }).evaluate((element) => {
        const style = getComputedStyle(element);
        return element === document.activeElement && style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0;
      }), true);
      if (route === "register") {
        const duration = await securityCheck(zoomPage);
        console.log(`PASS real security check at ${width}px and 200% browser zoom: ${duration}ms`);
      }
      await layout(zoomPage);
      await zoomPage.screenshot({ path: join(captures,
        `issue-${route === "login" ? "66" : "67"}-${route}-${width}-zoom-200.png`) });
    }
  }
  assert.equal(unexpected.length, 0, "Unexpected browser errors; details intentionally suppressed");
  console.log("PASS meaningful no-JavaScript login, recovery-factor challenge, account, logout and honest registration boundary; no hydration/page errors");
} catch (error) {
  if (currentPage && !currentPage.isClosed()) {
    console.error("Authentication browser failure route", new URL(currentPage.url()).pathname);
    const content = await currentPage.locator("main").count() ? currentPage.locator("main") : currentPage.locator("body");
    console.error("Safe visible summary", (await content.textContent())?.slice(0, 1000));
    await currentPage.evaluate(() => {
      for (const input of document.querySelectorAll("input")) {
        if (["password", "totp_code", "lookup_secret", "code"].includes(input.name)) input.value = "";
      }
    });
    await currentPage.screenshot({ path: join(captures, "diagnostic-failure.png"), fullPage: true });
  }
  throw error;
} finally {
  await zoom?.close();
  await browser.close();
  if (temporary) await rm(temporary, { recursive: true, force: true });
}
