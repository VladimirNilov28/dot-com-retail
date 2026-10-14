import { isIP } from "node:net";

/**
 * Client address the BFF forwards to Hive/Spring for anonymous rate limiting.
 * Next.js keeps an incoming X-Forwarded-For and only fills it from the socket
 * when it is absent, so the right-most entry is the address added by the
 * nearest hop. BFF_TRUSTED_PROXY_HOPS counts reverse proxies in front of Next
 * that append to the header; entries further left are client-controlled.
 */
export function clientAddress(headers: Headers, environment: Record<string, string | undefined> = process.env): string | undefined {
  const hops = Number(environment.BFF_TRUSTED_PROXY_HOPS ?? "0");
  if (!Number.isSafeInteger(hops) || hops < 0 || hops > 5) return undefined;
  const entries = (headers.get("x-forwarded-for") ?? "").split(",").map((entry) => entry.trim()).filter(Boolean);
  if (entries.length > 20) return undefined;
  const candidate = entries[entries.length - 1 - hops]?.replace(/^::ffff:(?=\d+\.\d+\.\d+\.\d+$)/i, "");
  return candidate && isIP(candidate) ? candidate : undefined;
}
