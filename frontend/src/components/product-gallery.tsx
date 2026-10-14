"use client";

import { useState } from "react";
import { ImageUnavailable } from "./image-unavailable";
import type { ProductImageData } from "./product-image";
import { variantSrcSet, variantUrl } from "@/lib/media/variants";

/** Main image plus an accessible thumbnail selector; the first image is the primary one. */
export function ProductGallery({ images }: { images: readonly ProductImageData[] }) {
  const [selected, setSelected] = useState(0);
  const [failed, setFailed] = useState<ReadonlySet<string>>(new Set());
  if (images.length === 0) return <ImageUnavailable />;
  const index = Math.min(selected, images.length - 1);
  const current = images[index];
  const broken = (url: string) => setFailed((previous) => new Set(previous).add(url));
  return (
    <div className="product-gallery">
      {failed.has(current.url) ? <ImageUnavailable /> : (
        <div className="product-gallery-main">
          {/* eslint-disable-next-line @next/next/no-img-element -- served by the same-origin media proxy */}
          <img ref={(node) => { if (node?.complete && node.naturalWidth === 0) queueMicrotask(() => broken(current.url)); }} src={variantUrl(current.url, "medium")} srcSet={variantSrcSet(current, ["thumb", "medium", "full"])}
            sizes="(min-width: 80rem) 36rem, (min-width: 48rem) 50vw, 100vw" alt={current.altText} width={current.width} height={current.height}
            decoding="async" onError={() => broken(current.url)} />
        </div>
      )}
      {images.length > 1 && (
        <ul className="product-gallery-thumbs" aria-label="Product images">
          {images.map((image, position) => (
            <li key={image.url}>
              <button type="button" onClick={() => setSelected(position)} aria-pressed={position === index}
                aria-label={`Show image ${position + 1} of ${images.length}: ${image.altText}`}
                className="product-gallery-thumb">
                {failed.has(image.url) ? <span className="text-xs text-muted">Unavailable</span> : (
                  // eslint-disable-next-line @next/next/no-img-element -- served by the same-origin media proxy
                  <img ref={(node) => { if (node?.complete && node.naturalWidth === 0) queueMicrotask(() => broken(image.url)); }} src={variantUrl(image.url, "thumb")} alt="" width={image.width} height={image.height} loading="lazy"
                    decoding="async" onError={() => broken(image.url)} />
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
