import "server-only";

export const mediaKey = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png)$/;
/** Must match the backend's `ImageSize` enum and `@/lib/media/variants`. */
export const mediaSizes: ReadonlySet<string> = new Set(["thumb", "medium", "full"]);
export const allowedImageTypes = new Set(["image/jpeg", "image/png"]);

/** Origin of the Spring backend that owns product media (never exposed to the browser). */
export function mediaBackendUrl(environment: Record<string, string | undefined> = process.env): string {
  const origin = environment.MEDIA_BACKEND_URL || "http://127.0.0.1:8080";
  const url = new URL(origin);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash ||
      url.pathname !== "/") throw new TypeError("Invalid media backend configuration");
  return url.origin;
}
