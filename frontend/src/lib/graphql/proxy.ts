import { failure, type SafeFailure } from "./errors";
import { guestOperations, publicOperations, type Operation } from "./operations";
import { object, parseJson, serializeJson } from "./scalars";
import { requestHive, type HiveConfig } from "./server-transport";
import { boundedText, type Result } from "./transport";

function jsonResponse<Data>(result: Result<Data>, cookies: string[] = [], status = 200) {
  const data = result.status === "failure" ? null : result.data;
  const error = result.status === "success" ? undefined : result.error;
  const body = serializeJson({
    data,
    ...(error ? { errors: [{
      message: error.message,
      extensions: {
        failureKind: error.kind,
        ...(error.httpStatus ? { http: { status: error.httpStatus } } : {}),
      },
    }] } : {}),
  });
  if (!body) throw new TypeError("Invalid GraphQL response");
  const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "private, no-store" });
  cookies.forEach((cookie) => headers.append("Set-Cookie", cookie));
  return new Response(body, { status, headers });
}

export function proxyFailure(error: SafeFailure, status: number) {
  return jsonResponse({ status: "failure", error }, [], status);
}

export async function handleGuestRequest(request: Request, config: HiveConfig, fetcher: typeof fetch = fetch) {
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
    if (operation.access === "guest" && (request.headers.get("Origin") !== config.origin ||
        request.headers.get("X-Guest-Cart-Request") !== "1")) {
      return proxyFailure(failure("forbidden", false), 403);
    }
    if (envelope.query !== operation.source) return proxyFailure(failure("validation", false), 400);
    let variables: Variables;
    try {
      variables = operation.variables(envelope.variables ?? {});
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
      return proxyFailure(failure("validation", false), 400);
    }
    const { result, setCookies } = await requestHive(operation, variables, config, operation.access === "public" ?
      { kind: "public" } : {
      kind: "guest", cookieHeader: request.headers.get("Cookie") ?? undefined,
    }, { fetch: fetcher, signal, timeoutMs: Math.max(1, 10_000 - (Date.now() - started)) });
    return jsonResponse(result, setCookies);
  }
  const [shipping, cart, order] = guestOperations;
  if (envelope.operationName === shipping.name) return dispatch(shipping);
  if (envelope.operationName === cart.name) return dispatch(cart);
  if (envelope.operationName === order.name) return dispatch(order);
  const [categories, listing] = publicOperations;
  if (envelope.operationName === categories.name) return dispatch(categories);
  if (envelope.operationName === listing.name) return dispatch(listing);
  return proxyFailure(failure("validation", false), 400);
}
