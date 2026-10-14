"use client";

import { useState } from "react";
import { ImageUnavailable } from "./image-unavailable";
import { variantSrcSet, variantUrl } from "@/lib/media/variants";

export type ProductImageData = { url: string; altText: string; width: number; height: number };

/** Grid cards: 2 columns on phones, 3 from 40rem, capped by the page width on large screens. */
export const cardImageSizes = "(min-width: 80rem) 22rem, (min-width: 40rem) 33vw, 50vw";

/**
 * Renders a stored product photo from the thumbnail/medium variants (the browser picks via `sizes`),
 * falling back to the neutral placeholder when absent or broken.
 */
export function ProductImage({ image, eager = false, sizes = cardImageSizes }: {
  image: ProductImageData | null | undefined; eager?: boolean; sizes?: string;
}) {
  const [failed, setFailed] = useState<string | null>(null);
  if (!image || failed === image.url) return <ImageUnavailable />;
  return (
    <div className="catalog-image catalog-photo">
      {/* eslint-disable-next-line @next/next/no-img-element -- served by the same-origin media proxy */}
      <img ref={(node) => { if (node?.complete && node.naturalWidth === 0) queueMicrotask(() => setFailed(image.url)); }} src={variantUrl(image.url, "thumb")} srcSet={variantSrcSet(image, ["thumb", "medium"])} sizes={sizes}
        alt={image.altText} width={image.width} height={image.height} loading={eager ? "eager" : "lazy"} decoding="async" onError={() => setFailed(image.url)} />
    </div>
  );
}
