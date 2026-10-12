"use client";

import { useState } from "react";
import { ImageUnavailable } from "./image-unavailable";

export type ProductImageData = { url: string; altText: string; width: number; height: number };

/** Renders a stored product photo, falling back to the neutral placeholder when absent or broken. */
export function ProductImage({ image, eager = false }: { image: ProductImageData | null | undefined; eager?: boolean }) {
  const [failed, setFailed] = useState<string | null>(null);
  if (!image || failed === image.url) return <ImageUnavailable />;
  return (
    <div className="catalog-image catalog-photo">
      {/* eslint-disable-next-line @next/next/no-img-element -- served by the same-origin media proxy */}
      <img ref={(node) => { if (node?.complete && node.naturalWidth === 0) queueMicrotask(() => setFailed(image.url)); }} src={image.url} alt={image.altText} width={image.width} height={image.height}
        loading={eager ? "eager" : "lazy"} decoding="async" onError={() => setFailed(image.url)} />
    </div>
  );
}
