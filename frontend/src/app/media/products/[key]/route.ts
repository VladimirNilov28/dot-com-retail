import "server-only";
import { allowedImageTypes, mediaBackendUrl, mediaKey } from "@/lib/media/config";

export async function GET(_request: Request, context: { params: Promise<{ key: string }> }) {
  const { key } = await context.params;
  if (!mediaKey.test(key)) return new Response(null, { status: 404 });
  let upstream: Response;
  try {
    upstream = await fetch(`${mediaBackendUrl()}/media/products/${key}`, {
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
