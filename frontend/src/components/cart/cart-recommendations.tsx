"use client";

import { useEffect, useState } from "react";
import { requestPublic } from "@/lib/graphql/browser";
import { recommendedProducts } from "@/lib/graphql/operations";
import type { CatalogProduct } from "@/lib/catalog/model";
import { RelatedProducts } from "../related-products";

export function CartRecommendations({ productIds }: { productIds: readonly string[] }) {
  const key = [...new Set(productIds)].sort().slice(0, 20).join(",");
  const [state, setState] = useState<{ key: string; products: CatalogProduct[] }>({ key: "", products: [] });

  useEffect(() => {
    if (!key) return;
    const controller = new AbortController();
    async function load() {
      const result = await requestPublic(recommendedProducts, { productIds: key.split(","), limit: 4 }, { signal: controller.signal });
      if (!controller.signal.aborted && result.status === "success") setState({ key, products: result.data.recommendedProducts });
    }
    void load();
    return () => controller.abort();
  }, [key]);

  return <RelatedProducts title="You may also like" products={state.key === key ? state.products : []} />;
}
