import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
assert.equal(process.env.STOREFRONT_BASE_URL, "http://127.0.0.1:3000");
const inbox = await (await fetch("http://127.0.0.1:8025/api/v1/messages")).json();
const recipient = inbox.messages.flatMap((message) => message.To).find((to) =>
  /^authentry-[a-f0-9]{12}@bytecore\.example$/.test(to.Address));
assert.ok(recipient, "A disposable designated browser-test identity must exist");
const browser = await chromium.launch();
const context = await browser.newContext();
const page = await context.newPage();
try {
  const existing = new Set(inbox.messages.map((message) => message.ID));
  await page.goto("http://127.0.0.1:3000/recover");
  await page.getByLabel("Email", { exact: true }).fill(recipient.Address);
  await page.getByRole("button", { name: "Send recovery email", exact: true }).click();
  await page.getByLabel("Recovery code", { exact: true }).waitFor();
  let proof;
  for (let attempt = 0; attempt < 60; attempt++) {
    const list = await (await fetch("http://127.0.0.1:8025/api/v1/messages")).json();
    const item = list.messages.find((message) => !existing.has(message.ID) &&
      message.To.some((to) => to.Address === recipient.Address) && message.Subject.toLowerCase().includes("recover"));
    if (item) {
      const mail = await (await fetch(`http://127.0.0.1:8025/api/v1/message/${item.ID}`)).json();
      proof = mail.Text.match(/\b\d{6}\b/)?.[0];
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  assert.ok(proof, "Genuine recovery email required");
  console.log("WAIT genuine recovery proof expiry; production lifespan unchanged");
  await new Promise((resolve) => setTimeout(resolve, 16 * 60 * 1000));
  await page.getByLabel("Recovery code", { exact: true }).fill(proof);
  await page.getByRole("button", { name: "Continue to password reset", exact: true }).click();
  await page.getByRole("heading", { name: "This form has expired", exact: true }).waitFor();
  assert.ok(!new URL(page.url()).pathname.includes("/reset"));
  console.log("PASS naturally expired genuine recovery proof rejected without extending or altering expiry");
} catch (error) {
  console.error("FAIL recovery expiry", error.name, "private values suppressed");
  process.exitCode = 1;
} finally {
  await context.close();
  await browser.close();
}
