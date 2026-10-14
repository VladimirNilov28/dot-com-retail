import { expect, test } from "bun:test";
import { handleGuestRequest } from "../graphql/proxy";
import { customerConfirmation } from "../graphql/checkout-operations";
import { readAttempt, readLegacyAttempt, clearLegacyAttempt } from "./model";

test("unscoped legacy recovery is retained, not assigned to a different customer", () => {
  const previous = globalThis.sessionStorage;
  const legacy = { mode: "user", input: { requestId: crypto.randomUUID(),
    acceptedQuoteVersion: "a".repeat(64), checkout: { contactEmail: "fixture@example.test",
      shippingMethod: "PICKUP", billingSameAsShipping: false, paymentSelection: "STRIPE" } } };
  const values = new Map([["bytecore.checkout.attempt", JSON.stringify(legacy)]]);
  globalThis.sessionStorage = { getItem: (key) => values.get(key) ?? null, removeItem: (key) => values.delete(key) };
  try {
    expect(readAttempt({ mode: "user", scope: "user:75" })).toBeNull();
    expect(readLegacyAttempt().input.requestId).toBe(legacy.input.requestId);
    clearLegacyAttempt(crypto.randomUUID());
    expect(readLegacyAttempt()).not.toBeNull();
  } finally { globalThis.sessionStorage = previous; }
});

test("BFF rejects a stale checkout identity before forwarding any read or payment capability", async () => {
  let calls = 0;
  const response = await handleGuestRequest(new Request("http://127.0.0.1:3000/graphql", {
    method: "POST", headers: { "Content-Type": "application/json", Origin: "http://127.0.0.1:3000",
      "X-Guest-Cart-Request": "1", "X-Checkout-Scope": "user:74" },
    body: JSON.stringify({ operationName: customerConfirmation.name, query: customerConfirmation.source,
      variables: { requestId: crypto.randomUUID() } }),
  }), { origin: "http://127.0.0.1:3000", endpoint: "http://127.0.0.1:4002/graphql" },
  async () => { ++calls; throw new Error("Stale identity must not reach Hive"); },
  async () => "fixture", async (scope, guest) => scope === "user:75" && !guest);
  expect(response.status).toBe(401);
  expect(calls).toBe(0);
});
