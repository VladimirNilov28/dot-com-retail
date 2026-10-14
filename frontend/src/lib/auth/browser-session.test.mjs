import { expect, test } from "bun:test";
import { headerReturnTo, loadHeaderAccount } from "./browser-session";

test("checkout and provider-return confirmation keep safe header destinations", () => {
  const confirmation = "/order-confirmation/12345678-1234-1234-1234-123456789012";
  expect(headerReturnTo("/checkout", "")).toBe("/checkout");
  expect(headerReturnTo(confirmation, "?guest=1&payment_intent=pi_test&payment_intent_client_secret=test&redirect_status=succeeded"))
    .toBe(confirmation + "?guest=1");
  expect(() => headerReturnTo("/checkout", "?payment_intent_client_secret=test")).toThrow();
});

test("anonymous, completed login, expired state and real outages remain distinct", async () => {
  const signal = new AbortController().signal;
  for (const [response, expected] of [
    [Response.json({ kind: "anonymous" }), { kind: "anonymous" }],
    [Response.json({ kind: "authenticated", user: { username: "fixture" }, csrf: "a".repeat(43) }),
      { kind: "authenticated", username: "fixture", csrf: "a".repeat(43) }],
    [Response.json({ error: "expired" }, { status: 401 }), { kind: "expired" }],
    [Response.json({ error: "unavailable" }, { status: 503 }), { kind: "unavailable" }],
  ]) expect(await loadHeaderAccount(signal, async () => response)).toEqual(expected);
  await expect(loadHeaderAccount(signal, async () => Response.json({ kind: "partial", user: { username: "fixture" } })))
    .rejects.toBeInstanceOf(TypeError);
  await expect(loadHeaderAccount(signal, async () => { throw new TypeError("Failed to fetch"); }))
    .rejects.toThrow("transport");
});

test("aborted or superseded status responses cannot restore the previous account", async () => {
  const controller = new AbortController();
  let finish;
  const response = Response.json({ kind: "authenticated", user: { username: "previous" }, csrf: "a".repeat(43) });
  response.json = () => new Promise((resolve) => { finish = resolve; });
  const pending = loadHeaderAccount(controller.signal, async () => response);
  await new Promise((resolve) => setTimeout(resolve, 0));
  controller.abort();
  finish({ kind: "authenticated", user: { username: "previous" }, csrf: "a".repeat(43) });
  expect(await pending).toBeNull();
});
