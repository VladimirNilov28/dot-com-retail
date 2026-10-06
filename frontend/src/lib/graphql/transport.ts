import { failure, graphFailure, httpKind, type SafeFailure } from "./errors";
import type { Operation } from "./operations";
import { object, parseJson, serializeJson } from "./scalars";

export type Result<Data> =
  | { status: "success"; data: Data }
  | { status: "partial"; data: Data; error: SafeFailure }
  | { status: "failure"; error: SafeFailure };

export interface TransportOptions {
  endpoint: string;
  headers?: HeadersInit;
  credentials?: RequestCredentials;
  signal?: AbortSignal;
  timeoutMs?: number;
  fetch?: typeof fetch;
  onResponse?: (response: Response) => void;
}

class InvalidPayload extends TypeError {}

export async function boundedText(message: Request | Response, limit: number, signal?: AbortSignal): Promise<string> {
  if (signal?.aborted) throw new DOMException("Request aborted", "AbortError");
  if (!message.body) return "";
  const reader = message.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0;
  let result = "";
  let cancel: () => void = () => {};
  const aborted = new Promise<never>((_, reject) => {
    cancel = () => reject(new DOMException("Request aborted", "AbortError"));
    signal?.addEventListener("abort", cancel, { once: true });
  });
  try {
    while (true) {
      if (signal?.aborted) throw new DOMException("Request aborted", "AbortError");
      const { done, value } = await Promise.race([reader.read(), aborted]);
      if (signal?.aborted) throw new DOMException("Request aborted", "AbortError");
      if (done) {
        try { return result + decoder.decode(); }
        catch (error) {
          if (!(error instanceof TypeError)) throw error;
          throw new InvalidPayload("Invalid UTF-8");
        }
      }
      size += value.byteLength;
      if (size > limit) {
        await reader.cancel();
        throw new InvalidPayload("Response size limit exceeded");
      }
      try { result += decoder.decode(value, { stream: true }); }
      catch (error) {
        if (!(error instanceof TypeError)) throw error;
        throw new InvalidPayload("Invalid UTF-8");
      }
    }
  } finally {
    signal?.removeEventListener("abort", cancel);
    try {
      await reader.cancel();
    } finally {
      reader.releaseLock();
    }
  }
}

export async function execute<Data, Variables>(
  operation: Operation<Data, Variables>,
  variables: Variables,
  options: TransportOptions,
): Promise<Result<Data>> {
  const query = operation.kind === "query";
  const timeoutMs = options.timeoutMs ?? 10_000;
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30_000) {
    return { status: "failure", error: failure("configuration", query) };
  }
  if (options.signal?.aborted) return { status: "failure", error: failure("cancelled", query) };
  let body: string;
  try {
    body = serializeJson({
      operationName: operation.name, query: operation.source, variables: operation.variables(variables),
    });
    if (!body || new TextEncoder().encode(body).byteLength > 32_768) throw new TypeError("Request size limit exceeded");
  } catch (error) {
    if (!(error instanceof TypeError || error instanceof SyntaxError)) throw error;
    return { status: "failure", error: failure("validation", query) };
  }

  const controller = new AbortController();
  let timedOut = false;
  let decoding = false;
  const onCancel = () => controller.abort();
  options.signal?.addEventListener("abort", onCancel, { once: true });
  if (options.signal?.aborted) controller.abort();
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  let onAbort: () => void = () => {};
  const aborted = new Promise<never>((_, reject) => {
    onAbort = () => reject(new DOMException("Request aborted", "AbortError"));
    controller.signal.addEventListener("abort", onAbort, { once: true });
    if (controller.signal.aborted) onAbort();
  });
  try {
    const work = async (): Promise<Result<Data>> => {
      const headers = new Headers(options.headers);
      headers.set("Content-Type", "application/json");
      headers.set("Accept", "application/graphql-response+json, application/json");
      const response = await (options.fetch ?? fetch)(options.endpoint, {
        method: "POST", body, headers, credentials: options.credentials ?? "omit",
        cache: "no-store", redirect: "error", signal: controller.signal,
      });
      try { options.onResponse?.(response); }
      catch (error) {
        if (!(error instanceof TypeError)) throw error;
        throw new InvalidPayload("Invalid response headers");
      }
      if (!response.ok) {
        await response.body?.cancel();
        return { status: "failure", error: failure(httpKind(response.status), query, response.status) };
      }
      const contentType = response.headers.get("Content-Type")?.split(";", 1)[0].trim();
      if (contentType !== "application/json" && contentType !== "application/graphql-response+json") {
        await response.body?.cancel();
        throw new InvalidPayload("Expected a GraphQL JSON response");
      }
      const source = await boundedText(response, 1_048_576, controller.signal);
      decoding = true;
      const envelope = object(parseJson(source));
      const apiError = envelope.errors === undefined ? undefined : graphFailure(envelope.errors, query);
      if (envelope.data === undefined || envelope.data === null) {
        return { status: "failure", error: apiError ?? failure("protocol", query) };
      }
      const data = operation.decode(envelope.data);
      return apiError ? { status: "partial", data, error: apiError } : { status: "success", data };
    };
    return await Promise.race([work(), aborted]);
  } catch (error) {
    if (controller.signal.aborted) {
      return { status: "failure", error: failure(timedOut ? "timeout" : "cancelled", query) };
    }
    if (!(error instanceof TypeError || error instanceof SyntaxError || error instanceof DOMException)) throw error;
    return { status: "failure", error: failure(decoding || error instanceof InvalidPayload ? "protocol" : "transport", query) };
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", onCancel);
    controller.signal.removeEventListener("abort", onAbort);
  }
}

export function latestRequest() {
  let generation = 0;
  let previous: AbortController | undefined;
  return {
    async run<Data>(request: (signal: AbortSignal) => Promise<Result<Data>>): Promise<Result<Data>> {
      const ownGeneration = ++generation;
      previous?.abort();
      const controller = new AbortController();
      previous = controller;
      const result = await request(controller.signal);
      return ownGeneration === generation ? result : { status: "failure", error: failure("stale", false) };
    },
    cancel() { generation++; previous?.abort(); },
  };
}
