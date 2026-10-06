import "server-only";
import { cache } from "react";
import { unstable_cache } from "next/cache";
import { connection } from "next/server";
import { catalogCategories, catalogListing } from "@/lib/graphql/operations";
import { hiveConfig, requestHive } from "@/lib/graphql/server";
import { failure, type SafeFailure } from "@/lib/graphql/errors";
import type { Result } from "@/lib/graphql/transport";
import { categoryIndex, productPrice, PAGE_SIZE } from "./model";
import type { CatalogCategoriesQuery, CatalogListingQuery } from "@/lib/graphql/generated";

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

const cachedListing = unstable_cache(async (endpoint: string, origin: string, page: number, categoryId?: string) => {
  const { result } = await requestHive(catalogListing, { input: {
    page, size: PAGE_SIZE, sort: "PRICE_ASC", ...(categoryId ? { filters: { categoryId } } : {}),
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
}, ["catalog-listing-v1"], { revalidate: 60 });

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

export const getCategories = cache((): Promise<Result<CatalogCategoriesQuery>> => read(cachedCategories));
export const getListing = cache((page: number, categoryId?: string): Promise<Result<CatalogListingQuery>> =>
  read((endpoint, origin) => cachedListing(endpoint, origin, page, categoryId)));
