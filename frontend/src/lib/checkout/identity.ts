import { requestCustomer, requestGuest } from "@/lib/graphql/browser";
import type { Operation } from "@/lib/graphql/operations";
import { failure } from "@/lib/graphql/errors";
import type { TransportOptions } from "@/lib/graphql/transport";
import type { CheckoutIdentity } from "./model";

export const identityChanged = "bytecore.checkout.identity-changed";

export function invalidateCheckout() {
  window.dispatchEvent(new Event(identityChanged));
  const channel = new BroadcastChannel(identityChanged);
  channel.postMessage("invalidate");
  channel.close();
}

export async function checkoutIdentity(signal?: AbortSignal): Promise<CheckoutIdentity> {
  const response = await fetch("/graphql/checkout-identity", {
    method: "POST", credentials: "same-origin", cache: "no-store", referrerPolicy: "no-referrer",
    headers: { "X-Guest-Cart-Request": "1" }, signal,
  });
  if (!response.ok) throw new Error("Checkout identity is unavailable");
  const value: unknown = await response.json();
  if (!value || typeof value !== "object" || !("mode" in value) || !("scope" in value) ||
      typeof value.scope !== "string" || (value.mode !== "user" && value.mode !== "guest") ||
      !(value.mode === "user" ? /^user:[1-9][0-9]*$/ : /^guest:[a-f0-9]{64}$/).test(value.scope))
    throw new Error("Invalid checkout identity");
  return { mode: value.mode, scope: value.scope };
}

export async function verifyCheckoutIdentity(owner: CheckoutIdentity) {
  const current = await checkoutIdentity();
  if (current.scope !== owner.scope) {
    invalidateCheckout();
    throw new Error("Checkout identity changed");
  }
}

export async function requestCheckout<Data, Variables>(owner: CheckoutIdentity,
  operation: Operation<Data, Variables>, variables: Variables,
  options: Pick<TransportOptions, "signal" | "timeoutMs"> = {}) {
  const result = operation.access === "authenticated" ?
    await requestCustomer(operation, variables, { ...options, checkoutScope: owner.scope }) :
    await requestGuest(operation, variables, { ...options, checkoutScope: owner.scope });
  if (result.status !== "success" && result.error.kind === "unauthenticated") {
    window.dispatchEvent(new Event(identityChanged));
    return { status: "failure" as const, error: failure("unauthenticated", false) };
  }
  return result;
}
