/**
 * Product image delivery variants served by `/media/products/{key}?size=…` (see docs/api/product-media.md).
 * Each bounds the longest edge and never upscales, so the real pixel width can be derived for `srcSet`.
 */
export type ImageSize = "thumb" | "medium" | "full";

export const imageSizeEdges: Readonly<Record<ImageSize, number>> = { thumb: 320, medium: 800, full: 2000 };

type Dimensions = { url: string; width: number; height: number };

export function variantUrl(url: string, size: ImageSize): string {
  return `${url}?size=${size}`;
}

/** Pixel width of the stored variant for an original of the given dimensions. */
export function variantWidth(image: Pick<Dimensions, "width" | "height">, size: ImageSize): number {
  const longest = Math.max(image.width, image.height);
  const edge = imageSizeEdges[size];
  return longest <= edge ? image.width : Math.max(1, Math.round((image.width * edge) / longest));
}

/** `srcSet` with real width descriptors; sizes that would produce the same pixels are listed once. */
export function variantSrcSet(image: Dimensions, sizes: readonly ImageSize[]): string {
  const seen = new Set<number>();
  return sizes.flatMap((size) => {
    const width = variantWidth(image, size);
    if (seen.has(width)) return [];
    seen.add(width);
    return [`${variantUrl(image.url, size)} ${width}w`];
  }).join(", ");
}
