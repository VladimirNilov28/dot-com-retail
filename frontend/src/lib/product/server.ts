import "server-only";
import { cache } from "react";
import { unstable_cache } from "next/cache";
import { connection } from "next/server";
import { productDetail, variantAvailability } from "@/lib/graphql/operations";
import { hiveConfig, requestHive } from "@/lib/graphql/server";
import { failure, type SafeFailure } from "@/lib/graphql/errors";
import type { Result } from "@/lib/graphql/transport";
import type { ProductDetailQuery } from "@/lib/graphql/generated";

class ProductFailure extends Error {
  constructor(readonly safe: SafeFailure) { super(safe.message); }
}

// Null lookups and failures must not become persistent successful cache entries.
class MissingProduct extends Error {}

const cachedProduct = unstable_cache(async (endpoint: string, origin: string, slug: string) => {
  const { result } = await requestHive(productDetail, { slug }, { endpoint, origin }, { kind: "public" });
  if (result.status !== "success") throw new ProductFailure(result.error);
  if (result.data.product === null) throw new MissingProduct();
  if (result.data.product.slug !== slug) throw new ProductFailure(failure("protocol", true));
  return result.data;
}, ["product-detail-v2"], { revalidate: 60 });

export const getProduct = cache(async (slug: string): Promise<Result<ProductDetailQuery>> => {
  try {
    let config;
    try { config = hiveConfig(process.env); }
    catch (error) {
      if (!(error instanceof TypeError)) throw error;
      throw new ProductFailure(failure("configuration", true));
    }
    return { status: "success", data: await cachedProduct(config.endpoint, config.origin, slug) };
  } catch (error) {
    if (error instanceof MissingProduct) return { status: "success", data: { product: null } };
    if (!(error instanceof ProductFailure)) throw error;
    await connection();
    console.error("Product read failed:", error.safe.kind);
    return { status: "failure", error: error.safe };
  }
});

export type VariantStock = { availability: "AVAILABLE" | "OUT_OF_STOCK" | null; purchasableLimit: number | null };

/** Live (uncached) availability per variant id; null means it could not be read, never "out of stock". */
export async function getAvailability(slug: string): Promise<Map<string, VariantStock> | null> {
  try {
    const config = hiveConfig(process.env);
    const { result } = await requestHive(variantAvailability, { slug }, { endpoint: config.endpoint, origin: config.origin }, { kind: "public" });
    if (result.status !== "success" || !result.data.product) return null;
    return new Map(result.data.product.variants.map((v) => [v.id, { availability: v.availability as VariantStock["availability"], purchasableLimit: v.purchasableLimit }]));
  } catch {
    return null;
  }
}
