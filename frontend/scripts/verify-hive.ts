import assert from "node:assert/strict";
import { shippingOptions, guestCart } from "../src/lib/graphql/operations";
import { hiveConfig, requestHive } from "../src/lib/graphql/server-transport";
import { execute } from "../src/lib/graphql/transport";

const config = hiveConfig(process.env);
const direct = await requestHive(shippingOptions, { countryCode: "EE" }, config, { kind: "guest" });
assert.equal(direct.result.status, "success", JSON.stringify(direct.result));
if (direct.result.status !== "success") throw new Error("Live Hive read failed");
assert.ok(direct.result.data.checkoutShippingOptions.length > 0, "Actual shipping options required");
assert.deepEqual(direct.setCookies, [], "Shipping read must not create credentials");
console.log("Actual Hive shipping read (no token or cookies):", direct.result.data.checkoutShippingOptions.map(
  ({ method, charge, currency }) => ({ method, charge, currency }),
));
const cart = await requestHive(guestCart, {}, config, { kind: "guest" });
assert.equal(cart.result.status, "success", JSON.stringify(cart.result));
if (cart.result.status !== "success") throw new Error("Live guest read failed");
assert.equal(cart.result.data.guestCart, null, "Credential-free read must not create or expose a cart");
console.log("Actual Hive credential-free guestCart read: null; no cart mutation performed.");

if (process.env.FRONTEND_URL) {
  const endpoint = new URL("/graphql", process.env.FRONTEND_URL).href;
  let responseHeaders: Headers | undefined;
  const throughProxy = await execute(shippingOptions, { countryCode: "EE" }, {
    endpoint,
    headers: { Origin: config.origin, "X-Guest-Cart-Request": "1" },
    onResponse(response) { responseHeaders = response.headers; },
  });
  assert.equal(throughProxy.status, "success", JSON.stringify(throughProxy));
  if (throughProxy.status !== "success") throw new Error("Same-origin boundary read failed");
  assert.deepEqual(throughProxy.data, direct.result.data);
  assert.equal(responseHeaders?.get("Cache-Control"), "private, no-store");
  console.log("Actual Next.js /graphql -> Hive read matches server adapter; private/no-store verified.");
}
