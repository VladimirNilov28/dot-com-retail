import "server-only";
import { cache } from "react";
import { unstable_cache } from "next/cache";
import { connection } from "next/server";
import { productDetail } from "@/lib/graphql/operations";
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
}, ["product-detail-v1"], { revalidate: 60 });

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
