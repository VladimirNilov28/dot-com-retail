import { integer, object } from "./scalars";

export type FailureKind = "validation" | "unauthenticated" | "forbidden" | "not-found" |
  "conflict" | "unavailable" | "transport" | "timeout" | "cancelled" | "protocol" |
  "graphql" | "configuration" | "stale" | "rate-limited";

const messages: Record<FailureKind, string> = {
  validation: "Check the request details and try again.",
  unauthenticated: "Sign in is required for this request.",
  forbidden: "This request is not allowed.",
  "not-found": "The requested resource is unavailable.",
  conflict: "The data changed. Refresh it before trying again.",
  unavailable: "The service is temporarily unavailable.",
  transport: "The service could not be reached.",
  timeout: "The request took too long.",
  cancelled: "The request was cancelled.",
  protocol: "The service returned an unexpected response.",
  graphql: "The request could not be completed.",
  configuration: "The connection is not configured correctly.",
  stale: "A newer request replaced this result.",
  "rate-limited": "Too many requests in a short time. Please wait a moment and try again.",
};

export interface SafeFailure {
  kind: FailureKind;
  message: string;
  retryable: boolean;
  httpStatus?: number;
  codes?: string[];
  /** Seconds until a rate-limited request may be retried, when the server said so. */
  retryAfterSeconds?: number;
}

const safeCodes = new Set([
  "BAD_REQUEST", "BAD_USER_INPUT", "GRAPHQL_PARSE_FAILED", "GRAPHQL_VALIDATION_FAILED",
  "VALIDATIONERROR", "INVALIDSYNTAX", "UNAUTHENTICATED", "UNAUTHORIZED", "FORBIDDEN",
  "PERMISSION_DENIED", "NOT_FOUND", "CONFLICT", "INTERNAL", "INTERNAL_SERVER_ERROR",
  "UNAVAILABLE", "OVERLOADED", "TIMEOUT", "DOWNSTREAM_SERVICE_ERROR", "SUBREQUEST_HTTP_ERROR",
  "GUEST_CART_UNAVAILABLE", "GUEST_ORDER_UNAVAILABLE", "CHECKOUT_INVALID",
  "CHECKOUT_QUOTE_CHANGED", "CHECKOUT_REQUEST_CONFLICT",
  "PAYMENT_NOT_CONFIGURED", "PAYMENT_UNAVAILABLE", "RATE_LIMITED",
  "REVIEW_PURCHASE_REQUIRED",
]);

export function failure(
  kind: FailureKind, query: boolean, httpStatus?: number, codes?: string[], retryAfterSeconds?: number,
): SafeFailure {
  return {
    kind, message: messages[kind],
    retryable: query && ["timeout", "transport", "unavailable"].includes(kind),
    ...(httpStatus === undefined ? {} : { httpStatus }),
    ...(codes?.length ? { codes } : {}),
    ...(kind === "rate-limited" && retryAfterSeconds !== undefined ? { retryAfterSeconds } : {}),
  };
}

/** Accepts only the delta-seconds form of Retry-After, bounded to a day. */
export function retryAfter(value: unknown): number | undefined {
  let seconds = NaN;
  if (typeof value === "string") seconds = /^\d{1,6}$/.test(value.trim()) ? Number(value.trim()) : NaN;
  else if (value !== undefined && value !== null) {
    try { seconds = integer(value); } catch (error) { if (!(error instanceof TypeError)) throw error; }
  }
  return Number.isSafeInteger(seconds) && seconds >= 0 && seconds <= 86_400 ? seconds : undefined;
}

export function httpKind(status: number): FailureKind {
  if (status === 401) return "unauthenticated";
  if (status === 403) return "forbidden";
  if (status === 404) return "not-found";
  if (status === 409) return "conflict";
  if (status === 400 || status === 422) return "validation";
  if (status === 429) return "rate-limited";
  if (status >= 500) return "unavailable";
  return "transport";
}

export function graphFailure(errors: unknown, query: boolean): SafeFailure {
  if (!Array.isArray(errors) || !errors.length) throw new TypeError("Invalid GraphQL errors");
  const kinds: FailureKind[] = [];
  const codes: string[] = [];
  let status: number | undefined;
  let wait: number | undefined;
  for (const value of errors) {
    const error = object(value);
    if (typeof error.message !== "string") throw new TypeError("Invalid GraphQL error");
    const extension = error.extensions === undefined ? {} : object(error.extensions);
    if (typeof extension.failureKind === "string" && Object.hasOwn(messages, extension.failureKind)) {
      kinds.push(extension.failureKind as FailureKind);
    }
    const rawCode = extension.errorType ?? extension.code ?? extension.classification;
    const code = typeof rawCode === "string" ? rawCode.toUpperCase() : undefined;
    if (code !== undefined && safeCodes.has(code)) {
      codes.push(code);
      if (code === "RATE_LIMITED") {
        kinds.push("rate-limited");
        wait = retryAfter(extension.retryAfterSeconds) ?? wait;
      } else if (/UNAUTHENTICATED|UNAUTHORIZED/.test(code)) kinds.push("unauthenticated");
      else if (/FORBIDDEN|PERMISSION_DENIED|REVIEW_PURCHASE_REQUIRED/.test(code)) kinds.push("forbidden");
      else if (/NOT_FOUND|GUEST_CART_UNAVAILABLE|GUEST_ORDER_UNAVAILABLE/.test(code)) kinds.push("not-found");
      else if (/CONFLICT|QUOTE_CHANGED/.test(code)) kinds.push("conflict");
      else if (/BAD_REQUEST|BAD_USER_INPUT|VALIDATION|CHECKOUT_INVALID|GRAPHQL_PARSE_FAILED|INVALIDSYNTAX/.test(code)) kinds.push("validation");
      else if (code === "PAYMENT_NOT_CONFIGURED") kinds.push("configuration");
      else if (/UNAVAILABLE|INTERNAL|TIMEOUT|OVERLOADED/.test(code)) kinds.push("unavailable");
    }
    if (extension.http !== undefined) {
      const nested = object(extension.http);
      if (nested.status !== undefined) {
        const downstream = integer(nested.status);
        if (downstream < 100 || downstream > 599) throw new TypeError("Invalid HTTP status");
        status = downstream;
        kinds.push(httpKind(downstream));
      }
    }
  }
  const priority: FailureKind[] = ["rate-limited", "unauthenticated", "forbidden", "validation", "conflict", "not-found",
    "configuration", "protocol", "cancelled", "stale", "timeout", "unavailable", "transport"];
  return failure(priority.find((kind) => kinds.includes(kind)) ?? "graphql", query, status, [...new Set(codes)], wait);
}
