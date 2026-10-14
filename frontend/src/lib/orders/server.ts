import "server-only";
import { headers } from "next/headers";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, hasSessionCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";
import { hiveConfig } from "@/lib/graphql/server";

export type CustomerSession =
  | { kind: "ok"; accessToken: string }
  | { kind: "anonymous" }
  | { kind: "problem"; code: string };

/** Signed-in customer's API token; ownership is enforced by the API, never by this lookup. */
export async function customerSession(): Promise<CustomerSession> {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return { kind: "anonymous" };
  try {
    const cookie = authCookie(request.get("cookie"), authConfig().sessionCookie);
    if (!cookie) return { kind: "anonymous" };
    const { state } = await authenticatedState(cookie);
    if (!state.tokens) return { kind: "anonymous" };
    return { kind: "ok", accessToken: state.tokens.accessToken };
  } catch (error) {
    if (error instanceof AuthError && error.status === 401) return { kind: "anonymous" };
    const code = error instanceof AuthError ? error.code : "unavailable";
    console.error("order session check failed", code);
    return { kind: "problem", code };
  }
}

export class OrderApiError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
  }
}

const deniedCodes = new Set(["PERMISSION_DENIED", "FORBIDDEN", "UNAUTHORIZED", "UNAUTHENTICATED"]);

/** Bearer GraphQL call to Hive; errors carry the API's errorType/classification code. */
export async function orderGraphql<T>(accessToken: string, query: string, variables: Record<string, unknown> = {}): Promise<T> {
  const { endpoint, origin } = hiveConfig(process.env);
  const response = await fetch(endpoint, {
    method: "POST",
    cache: "no-store",
    headers: { "Content-Type": "application/json", Accept: "application/json", Authorization: `Bearer ${accessToken}`, Origin: origin },
    body: JSON.stringify({ query, variables }),
    signal: AbortSignal.timeout(10_000),
  });
  if (response.status === 401 || response.status === 403) throw new OrderApiError("PERMISSION_DENIED", "Not allowed");
  if (!response.ok) throw new OrderApiError("UNAVAILABLE", `Order request failed (${response.status})`);
  const body = (await response.json()) as {
    data?: T; errors?: { message?: string; extensions?: { errorType?: unknown; classification?: unknown } }[];
  };
  const first = body.errors?.[0];
  if (first) {
    const raw = first.extensions?.errorType ?? first.extensions?.classification;
    const code = typeof raw === "string" ? raw.toUpperCase() : "GRAPHQL";
    throw new OrderApiError(deniedCodes.has(code) ? "PERMISSION_DENIED" : code, (first.message ?? "Request failed").slice(0, 200));
  }
  if (!body.data) throw new OrderApiError("PROTOCOL", "Empty GraphQL response");
  return body.data;
}

export const customerMessages: Record<string, string> = {
  cancelled: "Your order has been cancelled. Any captured payment is refunded automatically.",
  refund_requested: "Refund request sent. We'll email you once it has been reviewed.",
  reason: "Describe the reason for the refund in 3 to 1000 characters.",
  denied: "This order is not available to your account.",
  not_cancellable: "This order can no longer be cancelled.",
  refund_not_allowed: "A refund cannot be requested for this order.",
  invalid: "Check the details and try again.",
  failed: "The request could not be completed. Refresh the order and try again.",
};

const errorCodes: Record<string, string> = {
  PERMISSION_DENIED: "denied", NOT_FOUND: "denied", ORDER_NOT_CANCELLABLE: "not_cancellable",
  REFUND_NOT_ALLOWED: "refund_not_allowed", BAD_REQUEST: "invalid",
};

/** Maps an API failure to a fixed customer message code; raw API text is reserved for staff pages. */
export function customerErrorCode(error: unknown): string {
  return (error instanceof OrderApiError && errorCodes[error.code]) || "failed";
}
