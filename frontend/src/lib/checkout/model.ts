import type { CheckoutAddressInput, CheckoutInput, PlaceOrderInput } from "@/lib/graphql/generated";
import type { SafeFailure } from "@/lib/graphql/errors";
import { uuid, type UUID } from "@/lib/graphql/scalars";
import { guestPlace } from "@/lib/graphql/checkout-operations";

export const emptyAddress = (): CheckoutAddressInput => ({
  firstName: "", lastName: "", city: "", countryCode: "", postalCode: "", addressLine1: "", addressLine2: "", phone: "",
});
export const addressFields = [
  ["firstName", "First name", 50, "given-name"], ["lastName", "Last name", 50, "family-name"],
  ["addressLine1", "Address", 255, "address-line1"], ["addressLine2", "Apartment / address line 2", 255, "address-line2"],
  ["city", "City", 100, "address-level2"], ["postalCode", "Postal code", 10, "postal-code"],
] as const;

export function requestSelection(input: CheckoutInput): CheckoutInput {
  return {
    ...input,
    shippingAddress: input.shippingAddressId ? null : input.shippingAddress,
    billingAddress: input.billingAddressId ? null : input.billingAddress,
  };
}

export function validation(input: CheckoutInput, countries: string[]): Record<string, string> {
  const errors: Record<string, string> = {};
  const email = input.contactEmail?.trim() ?? "";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 255) errors.contactEmail = "Enter a valid email address.";
  if ((input.contactPhone?.length ?? 0) > 20 || /[\u0000-\u001f\u007f]/.test(input.contactPhone ?? "")) errors.contactPhone = "Use at most 20 characters, without control characters.";
  const check = (address: CheckoutAddressInput | null | undefined, prefix: string) => {
    if (!address) { errors[prefix] = "Enter an address."; return; }
    for (const [key, label, max] of addressFields) {
      const value = address[key]?.trim() ?? "";
      if ((!value && key !== "addressLine2") || value.length > max || /[\u0000-\u001f\u007f]/.test(value)) errors[`${prefix}.${key}`] = `${label} ${!value ? "is required." : `must use at most ${max} characters without control characters.`}`;
    }
    if (!countries.includes(address.countryCode)) errors[`${prefix}.countryCode`] = "Select a supported destination.";
    if ((address.phone?.length ?? 0) > 20) errors[`${prefix}.phone`] = "Use at most 20 characters.";
  };
  if (input.shippingMethod !== "PICKUP" && !input.shippingAddressId) check(input.shippingAddress, "shipping");
  if (!input.billingSameAsShipping && !input.billingAddressId) check(input.billingAddress, "billing");
  return errors;
}

export function checkoutError(error: SafeFailure): string {
  if (error.codes?.includes("CHECKOUT_QUOTE_CHANGED")) return "Prices, stock or shipping changed. Review the refreshed quote and accept it before placing your order.";
  if (error.codes?.includes("CHECKOUT_INVALID")) return "An item is unavailable or stock changed. Adjust your cart and review the new quote.";
  if (error.codes?.includes("CHECKOUT_REQUEST_CONFLICT")) return "This request belongs to an existing checkout. Check its order status before doing anything else.";
  if (error.codes?.includes("PAYMENT_NOT_CONFIGURED")) return "Stripe sandbox is not configured yet. No order was placed.";
  if (error.codes?.includes("GUEST_ORDER_UNAVAILABLE")) return "This guest confirmation is unavailable or its access cookie expired. Use the original browser; an order reference alone cannot restore access.";
  if (error.kind === "unauthenticated" || error.kind === "forbidden") return "Your session cannot access this checkout. Sign in again with the original customer account; guest orders still require their original browser cookie.";
  if (error.kind === "validation") return "Check the contact, address and shipping fields and try again.";
  return "The checkout service could not complete this request. Your order may already exist; check its status or retry the same request, not a new order.";
}

export type Attempt = { mode: "guest" | "user"; input: PlaceOrderInput };
const key = "bytecore.checkout.attempt";
export function readAttempt(): Attempt | null {
  const saved = sessionStorage.getItem(key);
  if (!saved) return null;
  const value: unknown = JSON.parse(saved);
  if (!value || typeof value !== "object" || !("mode" in value) || !("input" in value) ||
      (value.mode !== "guest" && value.mode !== "user")) throw new TypeError("Invalid saved checkout");
  return { mode: value.mode, input: guestPlace.variables({ input: value.input }).input };
}
export function saveAttempt(attempt: Attempt) { sessionStorage.setItem(key, JSON.stringify(attempt)); }
export function clearAttempt() { sessionStorage.removeItem(key); }
export function confirmationHref(requestId: UUID, mode: "guest" | "user") {
  return `/order-confirmation/${uuid(requestId)}${mode === "guest" ? "?guest=1" : ""}`;
}
