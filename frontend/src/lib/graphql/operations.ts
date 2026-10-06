import {
  CatalogCategoriesSource,
  CatalogListingSource,
  type CatalogCategoriesQuery,
  type CatalogCategoriesQueryVariables,
  type CatalogListingQuery,
  type CatalogListingQueryVariables,
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

export const catalogCategories: Operation<CatalogCategoriesQuery, CatalogCategoriesQueryVariables> = {
  name: "CatalogCategories", source: CatalogCategoriesSource, kind: "query", access: "public",
  variables(value) { variables(value, []); return {}; },
  decode(value) {
    return { categories: list(object(value).categories, (value) => {
      const category = object(value);
      return {
        id: text(category.id), name: text(category.name), slug: text(category.slug),
        parent: category.parent === null ? null : { id: text(object(category.parent).id) },
      };
    }) };
  },
};

export const catalogListing: Operation<CatalogListingQuery, CatalogListingQueryVariables> = {
  name: "CatalogListing", source: CatalogListingSource, kind: "query", access: "public",
  variables(value) {
    const root = variables(value, ["input"]);
    const input = variables(root.input, ["page", "size", "sort", "filters"]);
    const page = integer(input.page);
    if (page < 0 || integer(input.size) !== 20 || input.sort !== "PRICE_ASC") throw new TypeError("Invalid listing input");
    const filters = input.filters === undefined ? undefined : variables(input.filters, ["categoryId"]);
    const categoryId = filters ? text(filters.categoryId) : undefined;
    if (categoryId !== undefined && !/^[1-9]\d*$/.test(categoryId)) throw new TypeError("Invalid category id");
    return { input: {
      page, size: 20, sort: "PRICE_ASC",
      ...(categoryId === undefined ? {} : { filters: { categoryId } }),
    } };
  },
  decode(value) {
    const result = object(object(value).searchProducts);
    const info = object(result.pageInfo);
    const pageInfo = {
      page: integer(info.page), size: integer(info.size),
      totalItems: integer(info.totalItems), totalPages: integer(info.totalPages),
    };
    if (pageInfo.page < 0 || pageInfo.size !== 20 || pageInfo.totalItems < 0 ||
        pageInfo.totalPages !== Math.ceil(pageInfo.totalItems / pageInfo.size)) {
      throw new TypeError("Invalid pagination");
    }
    const items = list(result.items, (value) => {
      const product = object(value);
      return {
        id: text(product.id), name: text(product.name), slug: text(product.slug),
        variants: list(product.variants, (value) => {
          const variant = object(value);
          return { id: text(variant.id), price: decimal(variant.price), isActive: boolean(variant.isActive) };
        }),
      };
    });
    if (items.length > pageInfo.size) throw new TypeError("Unbounded listing response");
    return { searchProducts: { items, pageInfo } };
  },
};

export const publicOperations = [catalogCategories, catalogListing] as const;
