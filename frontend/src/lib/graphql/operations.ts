import {
  IntegrationGuestCartSource,
  IntegrationGuestOrderSource,
  IntegrationShippingOptionsSource,
  type CheckoutShippingMethod,
  type IntegrationGuestCartQuery,
  type IntegrationGuestCartQueryVariables,
  type IntegrationGuestOrderQuery,
  type IntegrationGuestOrderQueryVariables,
  type IntegrationShippingOptionsQuery,
  type IntegrationShippingOptionsQueryVariables,
  type OrderStatus,
} from "./generated";
import { boolean, decimal, integer, json, list, object, text, uuid } from "./scalars";

export interface Operation<Data, Variables> {
  readonly name: string;
  readonly source: string;
  readonly kind: "query" | "mutation";
  readonly access: "guest" | "authenticated" | "public";
  readonly decode: (value: unknown) => Data;
  readonly variables: (value: unknown) => Variables;
}

function variables(value: unknown, keys: readonly string[]) {
  const input = object(value);
  if (Object.keys(input).some((key) => !keys.includes(key))) throw new TypeError("Unexpected variables");
  return input;
}

function shippingMethod(value: unknown): CheckoutShippingMethod {
  if (value === "STANDARD" || value === "EXPRESS" || value === "PICKUP") return value;
  throw new TypeError("Unknown shipping method");
}

function orderStatus(value: unknown): OrderStatus {
  if (value === "PENDING" || value === "PAID" || value === "SHIPPING" ||
      value === "COMPLETED" || value === "CANCELLED") return value;
  throw new TypeError("Unknown order status");
}

export const shippingOptions: Operation<IntegrationShippingOptionsQuery, IntegrationShippingOptionsQueryVariables> = {
  name: "IntegrationShippingOptions", source: IntegrationShippingOptionsSource, kind: "query", access: "guest",
  variables(value) {
    const input = variables(value, ["countryCode"]);
    const countryCode = input.countryCode == null ? input.countryCode : text(input.countryCode);
    if (countryCode != null && !/^[A-Z]{2}$/.test(countryCode)) throw new TypeError("Invalid country code");
    return countryCode === undefined ? {} : { countryCode };
  },
  decode(value) {
    const root = object(value);
    return {
      checkoutShippingOptions: list(root.checkoutShippingOptions, (item) => {
        const option = object(item);
        return {
          method: shippingMethod(option.method), charge: decimal(option.charge),
          currency: text(option.currency), estimate: text(option.estimate),
          supportedCountries: list(option.supportedCountries, text),
        };
      }),
    };
  },
};

export const guestCart: Operation<IntegrationGuestCartQuery, IntegrationGuestCartQueryVariables> = {
  name: "IntegrationGuestCart", source: IntegrationGuestCartSource, kind: "query", access: "guest",
  variables(value) { variables(value, []); return {}; },
  decode(value) {
    const root = object(value);
    if (root.guestCart === null) return { guestCart: null };
    const cart = object(root.guestCart);
    const totals = object(cart.totals);
    return {
      guestCart: {
        id: text(cart.id), expiresAt: text(cart.expiresAt),
        totals: { subtotal: decimal(totals.subtotal), currency: text(totals.currency) },
        items: list(cart.items, (value) => {
          const item = object(value);
          const variant = object(item.productVariant);
          return {
            id: text(item.id), quantity: integer(item.quantity), subtotal: decimal(item.subtotal),
            productVariant: {
              id: text(variant.id), sku: text(variant.sku), price: decimal(variant.price),
              attributes: json(variant.attributes), isActive: boolean(variant.isActive),
            },
          };
        }),
      },
    };
  },
};

export const guestOrder: Operation<IntegrationGuestOrderQuery, IntegrationGuestOrderQueryVariables> = {
  name: "IntegrationGuestOrder", source: IntegrationGuestOrderSource, kind: "query", access: "guest",
  variables(value) {
    const input = variables(value, ["publicId", "requestId"]);
    if ((input.publicId != null) === (input.requestId != null)) throw new TypeError("Exactly one order reference required");
    return input.publicId != null ? { publicId: uuid(input.publicId) } : { requestId: uuid(input.requestId) };
  },
  decode(value) {
    const root = object(value);
    if (root.guestOrder === null) return { guestOrder: null };
    const order = object(root.guestOrder);
    const totals = object(order.totals);
    return {
      guestOrder: {
        publicId: uuid(order.publicId), requestId: uuid(order.requestId), status: orderStatus(order.status),
        totals: {
          merchandiseSubtotal: decimal(totals.merchandiseSubtotal), shippingCharge: decimal(totals.shippingCharge),
          total: decimal(totals.total), currency: text(totals.currency),
        },
      },
    };
  },
};

export const guestOperations = [shippingOptions, guestCart, guestOrder] as const;
