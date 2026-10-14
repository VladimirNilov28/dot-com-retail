import { failure, type SafeFailure } from "./errors";
import { cartGuestOperations, guestCartMerge, guestOperations, publicOperations, recommendedProducts, type Operation } from "./operations";
import { object, parseJson, serializeJson } from "./scalars";
import { requestHive, type HiveConfig } from "./server-transport";
import { boundedText, type Result } from "./transport";
import { checkoutOperations } from "./checkout-operations";
import { addressOperations } from "./address-operations";
import { submitContactMessage } from "./contact-operations";

function jsonResponse<Data>(result: Result<Data>, cookies: string[] = [], status = 200) {
  const data = result.status === "failure" ? null : result.data;
  const error = result.status === "success" ? undefined : result.error;
  const body = serializeJson({
    data,
    ...(error ? { errors: [{
      message: error.message,
      extensions: {
        failureKind: error.kind,
        ...(error.codes?.length ? { code: error.codes[0] } : {}),
        ...(error.httpStatus ? { http: { status: error.httpStatus } } : {}),
        ...(error.retryAfterSeconds === undefined ? {} : { retryAfterSeconds: error.retryAfterSeconds }),
      },
    }] } : {}),
  });
  if (!body) throw new TypeError("Invalid GraphQL response");
  const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "private, no-store" });
  cookies.forEach((cookie) => headers.append("Set-Cookie", cookie));
  if (error?.kind === "rate-limited") {
    status = 429;
    headers.set("Retry-After", String(error.retryAfterSeconds ?? 1));
  }
  return new Response(body, { status, headers });
}

export function proxyFailure(error: SafeFailure, status: number) {
  return jsonResponse({ status: "failure", error }, [], status);
}

export async function handleGuestRequest(
  request: Request, config: HiveConfig, fetcher: typeof fetch = fetch,
  // Browser-supplied bearer tokens are never accepted.
  resolveToken: () => Promise<string | undefined> = async () => undefined,
  validateCheckout: (scope: string | null, guest: boolean) => Promise<boolean> = async () => true,
  clientAddress?: string,
) {
  if (request.method !== "POST") return proxyFailure(failure("validation", false), 405);
  if ((request.headers.has("Origin") && request.headers.get("Origin") !== config.origin) ||
      request.headers.get("Authorization")) {
    return proxyFailure(failure("forbidden", false), 403);
  }
  if (request.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
    return proxyFailure(failure("validation", false), 415);
  }
  const deadline = AbortSignal.timeout(10_000);
  const signal = AbortSignal.any([request.signal, deadline]);
  const started = Date.now();
  let envelope: Record<string, unknown>;
  try {
    envelope = object(parseJson(await boundedText(request, 32_768, signal)));
    if (Object.keys(envelope).some((key) => !["query", "operationName", "variables"].includes(key))) {
      throw new TypeError("Unexpected request fields");
    }
  } catch (error) {
    if (signal.aborted) return proxyFailure(failure(request.signal.aborted ? "cancelled" : "timeout", false), request.signal.aborted ? 499 : 504);
    if (!(error instanceof TypeError || error instanceof SyntaxError)) throw error;
    return proxyFailure(failure("validation", false), 400);
  }
  async function dispatch<Data, Variables>(operation: Operation<Data, Variables>) {
    if ((operation.access === "guest" || operation.access === "authenticated") && (request.headers.get("Origin") !== config.origin ||
        request.headers.get("X-Guest-Cart-Request") !== "1")) {
      return proxyFailure(failure("forbidden", false), 403);
    }
    if (envelope.query !== operation.source) return proxyFailure(failure("validation", false), 400);
    if ((checkoutOperations.some((candidate) => candidate.name === operation.name) && operation.name !== "CheckoutConfiguration") ||
        operation === (guestOperations[2] as Operation<unknown, unknown>)) {
      if (!await validateCheckout(request.headers.get("X-Checkout-Scope"), operation.access === "guest"))
        return proxyFailure(failure("unauthenticated", false), 401);
    }
    let variables: Variables;
    try {
      variables = operation.variables(envelope.variables ?? {});
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
      return proxyFailure(failure("validation", false), 400);
    }
    let context: Parameters<typeof requestHive>[3] = operation.access === "public" ?
      { kind: "public" } : { kind: "guest", cookieHeader: request.headers.get("Cookie") ?? undefined };
    if (operation.access === "authenticated" || operation === (guestCartMerge as Operation<unknown, unknown>)) {
      const accessToken = await resolveToken();
      if (!accessToken) return proxyFailure(failure("unauthenticated", false), 401);
      context = { kind: "authenticated", accessToken, guestCookieHeader: request.headers.get("Cookie") ?? undefined };
    }
    const { result, setCookies } = await requestHive(operation, variables, config, context, { fetch: fetcher, signal, timeoutMs: Math.max(1, 10_000 - (Date.now() - started)), clientAddress });
    return jsonResponse(result, setCookies);
  }
  const [shipping, cart, order] = guestOperations;
  if (envelope.operationName === shipping.name) return dispatch(shipping);
  if (envelope.operationName === cart.name) return dispatch(cart);
  if (envelope.operationName === order.name) return dispatch(order);
  const cartOperation = cartGuestOperations.find((candidate) => candidate.name === envelope.operationName);
  if (cartOperation) return dispatch(cartOperation as Operation<unknown, unknown>);
  const checkoutOperation = checkoutOperations.find((candidate) => candidate.name === envelope.operationName);
  if (checkoutOperation) return dispatch(checkoutOperation as Operation<unknown, unknown>);
  if (envelope.operationName === submitContactMessage.name) return dispatch(submitContactMessage);
  const addressOperation = addressOperations.find((candidate) => candidate.name === envelope.operationName);
  if (addressOperation) return dispatch(addressOperation as Operation<unknown, unknown>);
  const [categories, listing, suggestions] = publicOperations;
  if (envelope.operationName === recommendedProducts.name) return dispatch(recommendedProducts);
  if (envelope.operationName === categories.name) return dispatch(categories);
  if (envelope.operationName === listing.name) return dispatch(listing);
  if (envelope.operationName === suggestions.name) return dispatch(suggestions);
  return proxyFailure(failure("validation", false), 400);
}
