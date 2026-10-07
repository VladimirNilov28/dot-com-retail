import {
  CatalogCategoriesSource,
  CatalogListingSource,
  CatalogSuggestionsSource,
  type CatalogCategoriesQuery,
  type CatalogCategoriesQueryVariables,
  type CatalogListingQuery,
  type CatalogListingQueryVariables,
  type CatalogSuggestionsQuery,
  type CatalogSuggestionsQueryVariables,
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
  type ProductSort,
} from "./generated";
import { boolean, decimal, float, integer, json, list, object, text, uuid } from "./scalars";

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

function productSort(value: unknown): ProductSort {
  if (value === "RELEVANCE" || value === "PRICE_ASC" || value === "PRICE_DESC" || value === "RATING_DESC") return value;
  throw new TypeError("Unknown product sort");
}

function nullableFloat(value: unknown): number | null {
  return value === null ? null : float(value);
}

function attributeFilter(value: unknown) {
  const input = variables(value, ["name", "value"]);
  const name = text(input.name);
  const attributeValue = text(input.value);
  if (!name || name.length > 100 || !attributeValue || attributeValue.length > 200) {
    throw new TypeError("Invalid attribute filter");
  }
  return { name, value: attributeValue };
}

function categoryFacet(value: unknown) {
  const facet = object(value);
  const count = integer(facet.count);
  if (count < 0) throw new TypeError("Invalid facet count");
  return { id: text(facet.id), name: text(facet.name), count };
}

function attributeValueFacet(value: unknown) {
  const facet = object(value);
  const count = integer(facet.count);
  if (count < 0) throw new TypeError("Invalid facet count");
  return { value: text(facet.value), count };
}

function attributeFacet(value: unknown) {
  const facet = object(value);
  return { name: text(facet.name), values: list(facet.values, attributeValueFacet) };
}

function priceFacet(value: unknown) {
  const facet = object(value);
  return {
    min: facet.min === null ? null : decimal(facet.min),
    max: facet.max === null ? null : decimal(facet.max),
  };
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
    const input = variables(root.input, ["page", "size", "sort", "query", "filters"]);
    const page = integer(input.page);
    if (page < 0 || integer(input.size) !== 20) throw new TypeError("Invalid listing input");
    const sort = productSort(input.sort);
    const query = input.query === undefined ? undefined : text(input.query).trim();
    if (query !== undefined && (query.length > 200)) throw new TypeError("Invalid search query");
    if (sort === "RELEVANCE" && !query) throw new TypeError("RELEVANCE requires a non-blank query");
    const filters = input.filters === undefined ? undefined
      : variables(input.filters, ["categoryId", "minPrice", "maxPrice", "attributes"]);
    const categoryId = filters?.categoryId === undefined ? undefined : text(filters.categoryId);
    if (categoryId !== undefined && !/^[1-9]\d*$/.test(categoryId)) throw new TypeError("Invalid category id");
    const minPrice = filters?.minPrice === undefined ? undefined : decimal(filters.minPrice);
    const maxPrice = filters?.maxPrice === undefined ? undefined : decimal(filters.maxPrice);
    if ((minPrice !== undefined && Number(minPrice) < 0) || (maxPrice !== undefined && Number(maxPrice) < 0)) {
      throw new TypeError("Invalid price bound");
    }
    const attributes = filters?.attributes === undefined ? undefined : list(filters.attributes, attributeFilter);
    if (attributes !== undefined && attributes.length > 20) throw new TypeError("Too many attribute filters");
    const hasFilters = categoryId !== undefined || minPrice !== undefined || maxPrice !== undefined || attributes !== undefined;
    return { input: {
      page, size: 20, sort,
      ...(query ? { query } : {}),
      ...(hasFilters ? { filters: {
        ...(categoryId === undefined ? {} : { categoryId }),
        ...(minPrice === undefined ? {} : { minPrice }),
        ...(maxPrice === undefined ? {} : { maxPrice }),
        ...(attributes === undefined ? {} : { attributes }),
      } } : {}),
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
      const ratingCount = integer(product.ratingCount);
      if (ratingCount < 0) throw new TypeError("Invalid rating count");
      return {
        id: text(product.id), name: text(product.name), slug: text(product.slug),
        averageRating: nullableFloat(product.averageRating), ratingCount,
        variants: list(product.variants, (value) => {
          const variant = object(value);
          return { id: text(variant.id), price: decimal(variant.price), isActive: boolean(variant.isActive) };
        }),
      };
    });
    if (items.length > pageInfo.size) throw new TypeError("Unbounded listing response");
    const facets = object(result.facets);
    return { searchProducts: { items, pageInfo, facets: {
      categories: list(facets.categories, categoryFacet),
      attributes: list(facets.attributes, attributeFacet),
      price: priceFacet(facets.price),
    } } };
  },
};

export const catalogSuggestions: Operation<CatalogSuggestionsQuery, CatalogSuggestionsQueryVariables> = {
  name: "CatalogSuggestions", source: CatalogSuggestionsSource, kind: "query", access: "public",
  variables(value) {
    const root = variables(value, ["query", "limit"]);
    const query = text(root.query).trim();
    if (!query || query.length > 200) throw new TypeError("Invalid suggestion query");
    if (root.limit === undefined) return { query };
    const limit = integer(root.limit);
    if (limit < 1 || limit > 10) throw new TypeError("Invalid suggestion limit");
    return { query, limit };
  },
  decode(value) {
    return { productSearchSuggestions: list(object(value).productSearchSuggestions, (value) => {
      const suggestion = object(value);
      return { productId: text(suggestion.productId), name: text(suggestion.name), slug: text(suggestion.slug) };
    }) };
  },
};

export const publicOperations = [catalogCategories, catalogListing, catalogSuggestions] as const;
