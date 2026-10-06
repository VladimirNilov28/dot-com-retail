"use client";

import { execute, type TransportOptions } from "./transport";
import type { Operation } from "./operations";

export function requestGuest<Data, Variables>(
  operation: Operation<Data, Variables>,
  variables: Variables,
  options: Pick<TransportOptions, "signal" | "timeoutMs"> = {},
) {
  if (operation.access !== "guest") throw new TypeError("Guest transport requires a guest operation");
  return execute(operation, variables, {
    ...options, endpoint: "/graphql", credentials: "same-origin",
    headers: { "X-Guest-Cart-Request": "1" },
  });
}
