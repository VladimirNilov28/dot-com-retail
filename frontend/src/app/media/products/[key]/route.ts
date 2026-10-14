import "server-only";
import { allowedImageTypes, mediaBackendUrl, mediaKey, mediaSizes } from "@/lib/media/config";

export async function GET(request: Request, context: { params: Promise<{ key: string }> }) {
  const { key } = await context.params;
  const size = new URL(request.url).searchParams.get("size");
  if (size !== null && !mediaSizes.has(size)) return new Response(null, { status: 400, headers: { "Cache-Control": "no-store" } });
  if (!mediaKey.test(key)) return new Response(null, { status: 404 });
  let upstream: Response;
  try {
    upstream = await fetch(`${mediaBackendUrl()}/media/products/${key}${size === null ? "" : `?size=${size}`}`, {
      signal: AbortSignal.timeout(5000), cache: "no-store", redirect: "error",
    });
  } catch {
    return new Response(null, { status: 502 });
  }
  const type = upstream.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase() ?? "";
  if (upstream.status === 404) return new Response(null, { status: 404 });
  if (!upstream.ok || !allowedImageTypes.has(type)) return new Response(null, { status: 502 });
  return new Response(upstream.body, {
    headers: {
      "Content-Type": type,
      "Cache-Control": "public, max-age=31536000, immutable",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'none'",
    },
  });
}
