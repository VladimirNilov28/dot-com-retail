import "server-only";
import { cache } from "react";
import { unstable_cache } from "next/cache";
import { connection } from "next/server";
import { catalogCategories, catalogListing } from "@/lib/graphql/operations";
import { hiveConfig, requestHive } from "@/lib/graphql/server";
import { failure, type SafeFailure } from "@/lib/graphql/errors";
import type { Result } from "@/lib/graphql/transport";
import { categoryIndex, productPrice, PAGE_SIZE, type AttributeFilter } from "./model";
import type { CatalogCategoriesQuery, CatalogListingQuery, ProductSort } from "@/lib/graphql/generated";
import type { Decimal } from "@/lib/graphql/scalars";

class CatalogFailure extends Error {
  constructor(readonly safe: SafeFailure) { super(safe.message); }
}

function configuration() {
  try { return hiveConfig(process.env); }
  catch (error) {
    if (!(error instanceof TypeError)) throw error;
    throw new CatalogFailure(failure("configuration", true));
  }
}

function success<Data>(result: Result<Data>): Data {
  if (result.status !== "success") throw new CatalogFailure(result.error);
  return result.data;
}

const cachedCategories = unstable_cache(async (endpoint: string, origin: string) => {
  const { result } = await requestHive(catalogCategories, {}, { endpoint, origin }, { kind: "public" });
  const data = success(result);
  try { categoryIndex(data.categories); }
  catch (error) {
    if (!(error instanceof TypeError)) throw error;
    throw new CatalogFailure(failure("protocol", true));
  }
  return data;
}, ["catalog-categories-v1"], { revalidate: 60 });

const cachedListing = unstable_cache(async (
  endpoint: string, origin: string, page: number, sort: ProductSort, query: string,
  categoryId?: string, minPrice?: Decimal, maxPrice?: Decimal, attributesKey?: string,
) => {
  const attributes: AttributeFilter[] | undefined = attributesKey ? JSON.parse(attributesKey) : undefined;
  const hasFilters = categoryId !== undefined || minPrice !== undefined || maxPrice !== undefined ||
    (attributes !== undefined && attributes.length > 0);
  const { result } = await requestHive(catalogListing, { input: {
    page, size: PAGE_SIZE, sort,
    ...(query ? { query } : {}),
    ...(hasFilters ? { filters: {
      ...(categoryId === undefined ? {} : { categoryId }),
      ...(minPrice === undefined ? {} : { minPrice }),
      ...(maxPrice === undefined ? {} : { maxPrice }),
      ...(attributes === undefined ? {} : { attributes }),
    } } : {}),
  } }, { endpoint, origin }, { kind: "public" });
  const data = success(result);
  try {
    if (data.searchProducts.pageInfo.page !== page) throw new TypeError("Unexpected page");
    data.searchProducts.items.forEach(productPrice);
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
    throw new CatalogFailure(failure("protocol", true));
  }
  return data;
}, ["catalog-listing-v2"], { revalidate: 60 });

async function read<Data>(request: (endpoint: string, origin: string) => Promise<Data>): Promise<Result<Data>> {
  try {
    const config = configuration();
    return { status: "success", data: await request(config.endpoint, config.origin) };
  } catch (error) {
    if (!(error instanceof CatalogFailure)) throw error;
    await connection();
    console.error("Catalog read failed:", error.safe.kind);
    return { status: "failure", error: error.safe };
  }
}

export interface ListingQuery {
  readonly page: number;
  readonly sort: ProductSort;
  readonly query?: string;
  readonly categoryId?: string;
  readonly minPrice?: Decimal;
  readonly maxPrice?: Decimal;
  readonly attributes?: readonly AttributeFilter[];
}

export const getCategories = cache((): Promise<Result<CatalogCategoriesQuery>> => read(cachedCategories));
export const getListing = cache((input: ListingQuery): Promise<Result<CatalogListingQuery>> =>
  read((endpoint, origin) => cachedListing(
    endpoint, origin, input.page, input.sort, input.query ?? "", input.categoryId, input.minPrice, input.maxPrice,
    input.attributes && input.attributes.length ? JSON.stringify(input.attributes) : undefined,
  )));
