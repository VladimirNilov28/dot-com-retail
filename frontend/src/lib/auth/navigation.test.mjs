import { expect, test } from "bun:test";
import { abandonsAuthentication } from "./navigation";

test("document and actual RSC storefront navigation abandon login, never auth transitions or prefetch", () => {
  for (const route of ["/", "/cart", "/checkout", "/products/fixture", "/account", "/order-confirmation/fixture"]) {
    for (const headers of [{ "Sec-Fetch-Dest": "document" }, { RSC: "1" }]) {
      expect(abandonsAuthentication(new Request(`http://127.0.0.1:3000${route}`, { headers }))).toBe(true);
    }
  }
  for (const route of ["/login?flow=fixture", "/register", "/auth/resume", "/auth/callback", "/self-service/methods/oidc/callback/google"]) {
    expect(abandonsAuthentication(new Request(`http://127.0.0.1:3000${route}`, { headers: { "Sec-Fetch-Dest": "document" } }))).toBe(false);
  }
  for (const headers of [{ "Next-Router-Prefetch": "1", RSC: "1" }, { Purpose: "prefetch", "Sec-Fetch-Dest": "document" }, {}]) {
    expect(abandonsAuthentication(new Request("http://127.0.0.1:3000/", { headers }))).toBe(false);
  }
});
