import { integer, object } from "./scalars";

export type FailureKind = "validation" | "unauthenticated" | "forbidden" | "not-found" |
  "conflict" | "unavailable" | "transport" | "timeout" | "cancelled" | "protocol" |
  "graphql" | "configuration" | "stale";

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
};

export interface SafeFailure {
  kind: FailureKind;
  message: string;
  retryable: boolean;
  httpStatus?: number;
  codes?: string[];
}

const safeCodes = new Set([
  "BAD_REQUEST", "BAD_USER_INPUT", "GRAPHQL_PARSE_FAILED", "GRAPHQL_VALIDATION_FAILED",
  "VALIDATIONERROR", "INVALIDSYNTAX", "UNAUTHENTICATED", "UNAUTHORIZED", "FORBIDDEN",
  "PERMISSION_DENIED", "NOT_FOUND", "CONFLICT", "INTERNAL", "INTERNAL_SERVER_ERROR",
  "UNAVAILABLE", "OVERLOADED", "TIMEOUT", "DOWNSTREAM_SERVICE_ERROR", "SUBREQUEST_HTTP_ERROR",
  "GUEST_CART_UNAVAILABLE", "GUEST_ORDER_UNAVAILABLE", "CHECKOUT_INVALID",
  "CHECKOUT_QUOTE_CHANGED", "CHECKOUT_REQUEST_CONFLICT",
]);

export function failure(kind: FailureKind, query: boolean, httpStatus?: number, codes?: string[]): SafeFailure {
  return {
    kind, message: messages[kind],
    retryable: query && ["timeout", "transport", "unavailable"].includes(kind),
    ...(httpStatus === undefined ? {} : { httpStatus }),
    ...(codes?.length ? { codes } : {}),
  };
}

export function httpKind(status: number): FailureKind {
  if (status === 401) return "unauthenticated";
  if (status === 403) return "forbidden";
  if (status === 404) return "not-found";
  if (status === 409) return "conflict";
  if (status === 400 || status === 422) return "validation";
  if (status === 429 || status >= 500) return "unavailable";
  return "transport";
}

export function graphFailure(errors: unknown, query: boolean): SafeFailure {
  if (!Array.isArray(errors) || !errors.length) throw new TypeError("Invalid GraphQL errors");
  const kinds: FailureKind[] = [];
  const codes: string[] = [];
  let status: number | undefined;
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
      if (/UNAUTHENTICATED|UNAUTHORIZED/.test(code)) kinds.push("unauthenticated");
      else if (/FORBIDDEN|PERMISSION_DENIED/.test(code)) kinds.push("forbidden");
      else if (/NOT_FOUND|GUEST_CART_UNAVAILABLE|GUEST_ORDER_UNAVAILABLE/.test(code)) kinds.push("not-found");
      else if (/CONFLICT|QUOTE_CHANGED/.test(code)) kinds.push("conflict");
      else if (/BAD_REQUEST|BAD_USER_INPUT|VALIDATION|CHECKOUT_INVALID|GRAPHQL_PARSE_FAILED|INVALIDSYNTAX/.test(code)) kinds.push("validation");
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
  const priority: FailureKind[] = ["unauthenticated", "forbidden", "validation", "conflict", "not-found",
    "configuration", "protocol", "cancelled", "stale", "timeout", "unavailable", "transport"];
  return failure(priority.find((kind) => kinds.includes(kind)) ?? "graphql", query, status, [...new Set(codes)]);
}
