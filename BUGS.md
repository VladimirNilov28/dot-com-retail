# Known Issues

_Last updated: 2026-09-29_

This file tracks bugs discovered during ad-hoc GraphQL testing that are **not yet fixed**,
along with their investigation notes so they can be picked up as a follow-up task.

## 1. `orderStatusChanged` subscription connects but never delivers events

**Status:** Open — root cause partially understood, not fixed.

**Symptom:**
The `orderStatusChanged(orderId: ID!)` subscription can now be reached over WebSocket
(`ws://localhost:8080/graphql`, `graphql-transport-ws` sub-protocol) — the handshake
succeeds and returns `connection_ack`, and the `subscribe` message is accepted and
authorized (`@PreAuthorize` passes, `Execution result ready.` is logged). However, when
an `updateOrderStatus` mutation is fired for the same `orderId` while a client is
subscribed, no `next` message with data is ever sent to the client. The subscription
instead sends a `complete` message with an empty payload (`{"id":"1","payload":{},"type":"complete"}`)
and no data is ever delivered — even when the subscribed `orderId` matches the order
being updated in a valid status transition.

**What was already fixed this session (do not re-do):**
- Missing `spring.graphql.websocket.path: /graphql` config, which previously made the
  WS transport completely unreachable (`405`/timeout). This part is fixed and verified
  — see `backend/src/main/resources/application.yaml`.

**What's still broken:**
- The `@DgsSubscription orderStatusChanged` resolver (`OrderMutation.java`) returns a
  `Publisher<Order>` sourced from `OrderStatusPublisher.subscribeTo(orderId)`, which
  wraps `Sinks.Many<Order>` (`Sinks.many().multicast().onBackpressureBuffer()`).
  `OrderService.updateStatus()` calls `orderStatusPublisher.publish(saved)` →
  `sink.tryEmitNext(order)` after saving the order status change.
- Despite the subscriber being registered (confirmed via Spring GraphQL debug/trace
  logs — `o.s.g.s.webmvc.GraphQlWebSocketHandler : Execution result ready.`) before the
  mutation runs, no `next` message ever reaches the client; only a premature `complete`.
- `OrderStatusPublisher.publish()` uses `sink.tryEmitNext(...)` and ignores the returned
  `Sinks.EmitResult` — if the WS transport hasn't established reactive-streams demand
  with the sink by the time `publish()` is called, the emission could silently fail
  (e.g. `FAIL_ZERO_SUBSCRIBER`) without raising an error, which would be consistent with
  the observed "no next, only complete" behavior, but does not fully explain why the
  Flux would also *complete*.
- This is likely a Spring GraphQL **Servlet/WebMvc** stack limitation or misconfiguration
  around reactive subscription execution (`org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler`),
  as opposed to the fully-reactive WebFlux stack, which has more mature support for
  long-lived `Flux` subscriptions. No existing test in the repo exercises this path
  (`orderStatusChanged` has zero test coverage).

**Suggested next steps for whoever picks this up:**
1. Check the return value of `sink.tryEmitNext(...)` in `OrderStatusPublisher.publish()`
   and log/handle non-`OK` results — this alone may explain (or rule out) the silent-drop
   theory.
2. Write a focused test (e.g. `OrderStatusPublisherTest`) subscribing directly to
   `subscribeTo(orderId)` in-process (no WebSocket) and asserting `publish()` results in
   an emitted item — this isolates whether the bug is in the publisher/sink itself or in
   the WebMvc WebSocket transport layer.
3. If the sink itself works correctly in isolation, investigate whether
   `spring-boot-starter-graphql`'s WebMvc WebSocket handler needs a Reactor
   `Scheduler`/executor configured for it to properly subscribe to and forward emissions
   from a `Flux` sourced from an application-level `Sinks.Many`, or whether switching to
   the WebFlux-based transport is required for reliable subscription push delivery in
   this Spring Boot/DGS version combination (`dgs-starter` 12.0.1 on Spring Boot 4.0.7).

**Files involved:**
- `backend/src/main/java/ee/bytecore/backend/services/OrderStatusPublisher.java`
- `backend/src/main/java/ee/bytecore/backend/services/OrderService.java` (`updateStatus`, `publish` call site)
- `backend/src/main/java/ee/bytecore/backend/graphql/datafetchers/order/OrderMutation.java` (`orderStatusChanged` resolver)
- `backend/src/main/resources/application.yaml` (`spring.graphql.websocket.path`)
