import {
  CheckoutConfigurationSource, CheckoutPrefillSource, CustomerCheckoutPreviewSource,
  GuestCheckoutPreviewSource, CustomerCheckoutPlaceSource, GuestCheckoutPlaceSource,
  CustomerCheckoutConfirmationSource, GuestCheckoutConfirmationSource,
  CustomerPaymentSessionSource, GuestPaymentSessionSource,
  CustomerCheckoutCancelSource, GuestCheckoutCancelSource,
  type CheckoutOrderFieldsFragment, type CheckoutPreviewFieldsFragment,
  type CheckoutDetailsFieldsFragment, type CheckoutInput, type PlaceOrderInput,
  type CheckoutPrefillQuery, type CheckoutConfigurationQuery,
  type CustomerCheckoutPreviewQuery, type GuestCheckoutPreviewQuery,
  type CustomerCheckoutPlaceMutation, type GuestCheckoutPlaceMutation,
  type CustomerCheckoutConfirmationQuery, type GuestCheckoutConfirmationQuery,
  type CustomerPaymentSessionQuery, type GuestPaymentSessionQuery,
  type CustomerCheckoutCancelMutation, type GuestCheckoutCancelMutation, type CancelOrderInput,
} from "./generated";
import type { Operation } from "./operations";
import { boolean, decimal, integer, json, list, object, text, uuid, type UUID } from "./scalars";

export type CheckoutOrder = CheckoutOrderFieldsFragment;
export type CheckoutPreview = CheckoutPreviewFieldsFragment;
export type PaymentSession = { status: string; publishableKey: string; clientSecret: string | null; failureCode: string | null };
const nullableText = (value: unknown) => value === null ? null : text(value);

function address(value: unknown) {
  if (value === null) return null;
  const a = object(value);
  return {
    firstName: text(a.firstName), lastName: text(a.lastName), city: text(a.city),
    countryCode: text(a.countryCode), postalCode: text(a.postalCode), addressLine1: text(a.addressLine1),
    addressLine2: nullableText(a.addressLine2), phone: nullableText(a.phone),
  };
}
function shipping(value: unknown): Omit<CheckoutDetailsFieldsFragment["shipping"], "pickupLocation"> {
  const s = object(value);
  const method = text(s.method);
  if (method !== "STANDARD" && method !== "EXPRESS" && method !== "PICKUP") throw new TypeError("Invalid shipping method");
  return {
    method, charge: decimal(s.charge), currency: text(s.currency), estimate: text(s.estimate),
    supportedCountries: list(s.supportedCountries, text),
  };
}
function details(value: unknown): CheckoutDetailsFieldsFragment {
  const d = object(value);
  const paymentSelection = text(d.paymentSelection);
  if (paymentSelection !== "SIMULATED" && paymentSelection !== "STRIPE") throw new TypeError("Invalid payment selection");
  const s = object(d.shipping);
  const pickup = s.pickupLocation === null ? null : object(s.pickupLocation);
  const pickupAddress = pickup ? address(pickup.address) : null;
  if (pickup && !pickupAddress) throw new TypeError("Missing pickup address");
  return {
    contactEmail: text(d.contactEmail), contactPhone: nullableText(d.contactPhone), paymentSelection,
    shippingAddress: address(d.shippingAddress), billingAddress: address(d.billingAddress),
    shipping: { ...shipping(s), pickupLocation: pickup && pickupAddress ? { name: text(pickup.name), address: pickupAddress } : null },
  };
}
function lines(value: unknown) {
  return list(value, (value) => {
    const l = object(value);
    const quantity = integer(l.quantity);
    if (quantity < 1) throw new TypeError("Invalid checkout quantity");
    return {
      productVariantId: text(l.productVariantId), productName: text(l.productName), sku: text(l.sku),
      attributes: json(l.attributes), quantity, unitPrice: decimal(l.unitPrice), subtotal: decimal(l.subtotal),
    };
  });
}
function totals(value: unknown) {
  const t = object(value);
  if (t.currency !== "EUR") throw new TypeError("Unsupported checkout currency");
  return { merchandiseSubtotal: decimal(t.merchandiseSubtotal), shippingCharge: decimal(t.shippingCharge),
    total: decimal(t.total), currency: text(t.currency) };
}
export function decodeOrder(value: unknown): CheckoutOrder {
  const o = object(value), p = object(o.payment), r = object(o.refund), c = object(o.cancellationEligibility);
  const status = text(o.status);
  if (status !== "PENDING" && status !== "PAID" && status !== "PROCESSING" && status !== "SHIPPING" && status !== "COMPLETED" && status !== "CANCELLED") throw new TypeError("Invalid order state");
  return {
    publicId: uuid(o.publicId), requestId: uuid(o.requestId), status, createdAt: text(o.createdAt),
    cancellationReason: nullableText(o.cancellationReason), paymentInteraction: text(o.paymentInteraction),
    payment: { status: paymentStatus(p.status) }, refund: { status: refundStatus(r.status), amount: r.amount === null ? null : decimal(r.amount),
      currency: nullableText(r.currency), failureReason: nullableText(r.failureReason) },
    cancellationEligibility: { allowed: boolean(c.allowed), reason: nullableText(c.reason) },
    lines: lines(o.lines), totals: totals(o.totals), details: details(o.details),
  };
}
function paymentStatus(value: unknown): CheckoutOrder["payment"]["status"] {
  if (value === "PENDING" || value === "UNRESOLVED" || value === "SUCCEEDED" || value === "FAILED" || value === "UNKNOWN") return value;
  throw new TypeError("Invalid payment state");
}
function refundStatus(value: unknown): CheckoutOrder["refund"]["status"] {
  if (value === "NONE" || value === "PENDING" || value === "UNRESOLVED" || value === "SUCCEEDED" || value === "FAILED") return value;
  throw new TypeError("Invalid refund state");
}
export function decodePreview(value: unknown): CheckoutPreview {
  const p = object(value);
  return {
    quoteVersion: text(p.quoteVersion), placeable: boolean(p.placeable), lines: lines(p.lines),
    totals: totals(p.totals), details: details(p.details),
    availableShippingOptions: list(p.availableShippingOptions, shipping),
    issues: list(p.issues, (value) => { const i = object(value); return {
      code: text(i.code), message: text(i.message), productVariantId: nullableText(i.productVariantId),
    }; }),
  };
}

function allowed(value: unknown, keys: string[]) {
  const v = object(value);
  if (Object.keys(v).some((key) => !keys.includes(key))) throw new TypeError("Unexpected checkout variables");
  return v;
}
function input(value: unknown): CheckoutInput {
  const v = allowed(value, ["contactEmail", "contactPhone", "shippingMethod", "shippingAddressId", "shippingAddress",
    "billingAddressId", "billingAddress", "billingSameAsShipping", "paymentSelection"]);
  const method = text(v.shippingMethod);
  if (method !== "STANDARD" && method !== "EXPRESS" && method !== "PICKUP") throw new TypeError("Invalid shipping method");
  if (v.paymentSelection !== "STRIPE") throw new TypeError("Storefront requires Stripe sandbox");
  const decodeAddress = (value: unknown) => {
    if (value == null) return null;
    const a = allowed(value, ["firstName", "lastName", "city", "countryCode", "postalCode", "addressLine1", "addressLine2", "phone"]);
    return {
      firstName: text(a.firstName), lastName: text(a.lastName), city: text(a.city),
      countryCode: text(a.countryCode), postalCode: text(a.postalCode), addressLine1: text(a.addressLine1),
      addressLine2: a.addressLine2 == null ? null : text(a.addressLine2), phone: a.phone == null ? null : text(a.phone),
    };
  };
  return {
    contactEmail: v.contactEmail == null ? null : text(v.contactEmail),
    contactPhone: v.contactPhone == null ? null : text(v.contactPhone), shippingMethod: method,
    shippingAddressId: v.shippingAddressId == null ? null : text(v.shippingAddressId),
    billingAddressId: v.billingAddressId == null ? null : text(v.billingAddressId),
    shippingAddress: decodeAddress(v.shippingAddress), billingAddress: decodeAddress(v.billingAddress),
    billingSameAsShipping: boolean(v.billingSameAsShipping), paymentSelection: "STRIPE",
  };
}
function previewVariables(value: unknown) { return { input: input(allowed(value, ["input"]).input) }; }
function placementVariables(value: unknown): { input: PlaceOrderInput } {
  const v = allowed(allowed(value, ["input"]).input, ["requestId", "acceptedQuoteVersion", "checkout"]);
  const quote = text(v.acceptedQuoteVersion);
  if (!/^[a-f0-9]{64}$/.test(quote)) throw new TypeError("Invalid accepted quote");
  return { input: { requestId: uuid(v.requestId), acceptedQuoteVersion: quote, checkout: input(v.checkout) } };
}
function reference(value: unknown) { return { requestId: uuid(allowed(value, ["requestId"]).requestId) }; }
function cancelVariables(value: unknown): { input: CancelOrderInput } {
  const v = allowed(allowed(value, ["input"]).input, ["requestId", "publicId"]);
  return { input: { requestId: uuid(v.requestId), publicId: uuid(v.publicId) } };
}
function cancelled(value: unknown) {
  const c = object(value), o = object(c.order);
  const status = text(o.status);
  if (status !== "CANCELLED") throw new TypeError("Unexpected cancellation state");
  return { requestId: uuid(c.requestId), order: { publicId: uuid(o.publicId), status } } as const;
}
function session(value: unknown): PaymentSession {
  const p = object(value);
  return { status: text(p.status), publishableKey: text(p.publishableKey),
    clientSecret: nullableText(p.clientSecret), failureCode: nullableText(p.failureCode) };
}

export const checkoutConfiguration: Operation<CheckoutConfigurationQuery, Record<string, never>> = {
  name: "CheckoutConfiguration", source: CheckoutConfigurationSource, access: "guest", kind: "query",
  variables(value) { allowed(value, []); return {}; },
  decode(value) { const c = object(object(value).checkoutPaymentConfiguration); return { checkoutPaymentConfiguration: { available: boolean(c.available), publishableKey: text(c.publishableKey) } }; },
};
export const checkoutPrefill: Operation<CheckoutPrefillQuery, Record<string, never>> = {
  name: "CheckoutPrefill", source: CheckoutPrefillSource, access: "authenticated", kind: "query",
  variables(value) { allowed(value, []); return {}; },
  decode(value) { const me = object(object(value).me); return { me: { email: text(me.email), addresses: list(me.addresses, (value) => {
    const a = object(value); return { id: text(a.id), firstName: nullableText(a.firstName), lastName: nullableText(a.lastName),
      city: nullableText(a.city), country: nullableText(a.country), postalCode: nullableText(a.postalCode),
      addressLine1: nullableText(a.addressLine1), addressLine2: nullableText(a.addressLine2), mobile: nullableText(a.mobile) };
  }) } }; },
};
export const customerPreview: Operation<CustomerCheckoutPreviewQuery, { input: CheckoutInput }> = {
  name: "CustomerCheckoutPreview", source: CustomerCheckoutPreviewSource, access: "authenticated", kind: "query",
  variables: previewVariables, decode(value) { return { checkoutPreview: decodePreview(object(value).checkoutPreview) }; },
};
export const guestPreview: Operation<GuestCheckoutPreviewQuery, { input: CheckoutInput }> = {
  name: "GuestCheckoutPreview", source: GuestCheckoutPreviewSource, access: "guest", kind: "query",
  variables: previewVariables, decode(value) { return { guestCheckoutPreview: decodePreview(object(value).guestCheckoutPreview) }; },
};
export const customerPlace: Operation<CustomerCheckoutPlaceMutation, { input: PlaceOrderInput }> = {
  name: "CustomerCheckoutPlace", source: CustomerCheckoutPlaceSource, access: "authenticated", kind: "mutation",
  variables: placementVariables, decode(value) { const order = object(object(value).createOrder);
    return { createOrder: { checkout: order.checkout === null ? null : decodeOrder(order.checkout) } }; },
};
export const guestPlace: Operation<GuestCheckoutPlaceMutation, { input: PlaceOrderInput }> = {
  name: "GuestCheckoutPlace", source: GuestCheckoutPlaceSource, access: "guest", kind: "mutation",
  variables: placementVariables, decode(value) { return { createGuestOrder: decodeOrder(object(value).createGuestOrder) }; },
};
export const customerConfirmation: Operation<CustomerCheckoutConfirmationQuery, { requestId: UUID }> = {
  name: "CustomerCheckoutConfirmation", source: CustomerCheckoutConfirmationSource, access: "authenticated", kind: "query",
  variables: reference, decode(value) { const o = object(value).checkoutOrder; return { checkoutOrder: o === null ? null : decodeOrder(o) }; },
};
export const guestConfirmation: Operation<GuestCheckoutConfirmationQuery, { requestId: UUID }> = {
  name: "GuestCheckoutConfirmation", source: GuestCheckoutConfirmationSource, access: "guest", kind: "query",
  variables: reference, decode(value) { const o = object(value).guestOrder; return { guestOrder: o === null ? null : decodeOrder(o) }; },
};
export const customerPayment: Operation<CustomerPaymentSessionQuery, { requestId: UUID }> = {
  name: "CustomerPaymentSession", source: CustomerPaymentSessionSource, access: "authenticated", kind: "query",
  variables: reference, decode(value) { return { checkoutPaymentSession: session(object(value).checkoutPaymentSession) }; },
};
export const guestPayment: Operation<GuestPaymentSessionQuery, { requestId: UUID }> = {
  name: "GuestPaymentSession", source: GuestPaymentSessionSource, access: "guest", kind: "query",
  variables: reference, decode(value) { return { guestPaymentSession: session(object(value).guestPaymentSession) }; },
};
export const customerCancel: Operation<CustomerCheckoutCancelMutation, { input: CancelOrderInput }> = {
  name: "CustomerCheckoutCancel", source: CustomerCheckoutCancelSource, access: "authenticated", kind: "mutation",
  variables: cancelVariables, decode(value) { return { cancelOrder: cancelled(object(value).cancelOrder) }; },
};
export const guestCancel: Operation<GuestCheckoutCancelMutation, { input: CancelOrderInput }> = {
  name: "GuestCheckoutCancel", source: GuestCheckoutCancelSource, access: "guest", kind: "mutation",
  variables: cancelVariables, decode(value) { return { cancelGuestOrder: cancelled(object(value).cancelGuestOrder) }; },
};
export const checkoutOperations = [checkoutConfiguration, checkoutPrefill, customerPreview, guestPreview, customerPlace,
  guestPlace, customerConfirmation, guestConfirmation, customerPayment, guestPayment, customerCancel, guestCancel] as const;
