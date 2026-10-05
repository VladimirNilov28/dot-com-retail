# Order cancellation and simulated refunds

Checklist #5 builds on Guest Cart #54 and Checkout API #55. The API is served
through Hive; there are no frontend pages, emails, returns or real gateways.
Checkout snapshots, purchased quantities and original allocations stay immutable.

## Eligibility and authorization

Cancellation is allowed in **PENDING and PAID**. **SHIPPING means fulfillment
processing has begun**, not merely that a parcel has left a warehouse. Operators
must enter SHIPPING before processing begins. SHIPPING and COMPLETED cannot
be cancelled. No new fulfillment state/system is introduced.

`cancellationEligibility { allowed reason }` is available on `Order`,
`CheckoutOrder` and the cancellation response. Reasons are `PROCESSING_BEGUN`,
`COMPLETED` and `ALREADY_CANCELLED`; an eligible order has a null reason.
Eligibility is informational: cancellation rechecks under the order lock.
Authorization failure is an error, not disclosure of another order's eligibility.

| Mutation | Required authorization |
| --- | --- |
| `cancelOrder(input: CancelOrderInput!)` | `order:write`, active JWT principal, original owner |
| `cancelGuestOrder(input: CancelOrderInput!)` | Valid independent guest-order cookie/grant and protected guest transport |
| `cancelOrderAsStaff(input: CancelOrderInput!)` | Active actor, ORDER_MANAGER or ADMIN **and** `order:manage-status` |

ADMIN does not bypass missing scopes; SUPPORT gains no cancellation privilege.
Deleted/deleting authenticated accounts are rejected on cancellation and replay.
Order reads retain `order:read` and their owner/staff policy.

Guest cancellation uses the existing `retail_guest_orders` HttpOnly, host-only,
SameSite=Lax cookie (Secure outside local dev, Path=/graphql). It requires JSON
POST, exact allowed Origin, `X-Guest-Cart-Request: 1` and `credentials: "include"`.
The source cart cookie, JWT, order ID/public UUID, checkout UUID and contact email
do not replace the unexpired order grant. Guest orders are not linked by email.
Expired/lost grants have no email recovery in this milestone.

**Intentional staff API restriction:** `updateOrderStatus` accepts only SHIPPING
and COMPLETED, with a confirmed successful payment. PAID/PENDING cannot be
assigned, and CANCELLED callers must use `cancelOrderAsStaff`. Payment/refund
status is never client-assignable. Existing roles/scopes still apply.

## Separate fulfillment and money

| Event | Order | Payment | Refund |
| --- | --- | --- | --- |
| Checkout accepted | PENDING | PENDING | NONE |
| Cancellation before/during charge | CANCELLED | PENDING/UNRESOLVED | NONE, awaiting payment outcome |
| Successful payment before cancellation | PAID | SUCCEEDED | NONE |
| Cancel a confirmed paid order | CANCELLED | SUCCEEDED | PENDING full refund |
| Successful charge after cancellation | Remains CANCELLED | SUCCEEDED | One PENDING full refund |
| Confirmed decline | CANCELLED | FAILED | NONE |
| Decline after customer cancellation | Remains CANCELLED | FAILED | NONE |
| Charge timeout/error | No automatic cancellation | UNRESOLVED | NONE until charge confirmed |
| Confirmed refund | Remains CANCELLED | SUCCEEDED | SUCCEEDED |
| Refund timeout/error | Remains CANCELLED | SUCCEEDED | UNRESOLVED, same-key recovery |
| Confirmed refund rejection | Remains CANCELLED | SUCCEEDED | FAILED |

PENDING means no terminal financial outcome is known. UNRESOLVED explicitly
reports an uncertain provider attempt; timeouts are not declines. UNKNOWN
represents legacy history without enough evidence. `refund.status: NONE` with
pending/unknown payment does **not** prove that no refund will be needed.
`cancellation.awaitingPaymentOutcome` makes this visible.

The Payment Service does not coordinate charge suppression with cancellation.
A queued/in-flight charge can still occur after cancellation. Cancellation stops
fulfillment, not a proven charge. A legitimate correlated late success is recorded
and requests a refund; it never resurrects the order. A conflicting success after
a confirmed decline is rejected, not treated as a legitimate late charge.

Confirmed payment outcomes and refund outcomes cannot be overwritten by
duplicates, contradictions or delayed uncertainty notifications. A successful
refund preserves the original SUCCEEDED charge history.

## Durable retries and stock release

Use a stable client-generated **cancellation request UUID**, distinct from the
checkout placement UUID. Input accepts only that UUID and the public order
reference; there is no client payment identity or refund amount.

The cancellation receipt binds request UUID, target order and authorized
actor/grant. Identical authorized retries return the committed cancellation and
current financial projection. Another target/actor produces
`CANCELLATION_REQUEST_CONFLICT`. Different request UUIDs on an already cancelled
authorized order are also safe. Authorization/grant expiry/active-account checks
still run on replay. A receipt is not an access credential.

Order locking serializes cancellation, fulfillment, payment/refund results and
competing requests. Result handlers lock order before request/receipt state.
Authenticated writes lock the active actor before the order; guest writes
recheck the original grant under the order lock. Request advisory locks serialize
UUID reuse. Stock locks follow ascending variant and original inventory-row IDs.
Managed inventory is refreshed after lock waits.

Cancellation/decline release the original `OrderItem.quantity` to the original
`inventory_id`, once. The release marker, inventory increments, cancellation
state/receipt and any refund/outbox commit together. Rollback leaves none of
these partially committed. A durable marker prevents another credit after
duplicate delivery or a cancellation/decline race. Carts are never repopulated.

## Simulated full-refund workflow

Spring persists one refund per original successful Payment UUID and one
`refund.requested` outbox event. Amount/currency come from the immutable original
payment request, including **the entire shipping charge**. Partial refunds,
multiple allocations and arbitrary client payment/amount selection are absent.

Go validates the original SUCCEEDED Payment, request/order identity, full amount,
currency and provider reference before invoking the provider. The persisted
refund UUID is the provider idempotency key, not an attempt/event UUID.
The request event, original payment request, Payment UUID, refund UUID and result
event are separately correlated.

The runtime simulated provider has a durable PostgreSQL charge/refund ledger,
committed independently of payment/refund result transactions. Concurrent
identical attempts return the retained outcome; changed parameters for a key are
rejected. Lost response, result/outbox rollback, restart, redelivery and lost
Kafka acknowledgement retry the original UUID rather than create another effect.

Request queuing is **PENDING**, never successful. Transient/unknown attempts
remain UNRESOLVED and recover with the same identity. Confirmed rejection is
FAILED and is not overridden automatically; this scope provides no privileged
replacement-refund operation. Investigate/reconcile failed or persistently
unresolved requests rather than rotate keys.

These are simulation guarantees, not production financial certification.
A future gateway adapter needs durable atomic key deduplication, adequate
retention and original-transaction reconciliation. Expired keys must fail closed
or reconcile; never issue a new charge/refund blindly.

## GraphQL examples

Authenticated cancellation (staff substitutes `cancelOrderAsStaff`; guest
substitutes `cancelGuestOrder` and supplies the protected cookie transport):

```graphql
mutation Cancel($input: CancelOrderInput!) {
  cancelOrder(input: $input) {
    requestId
    order {
      publicId
      status
      cancellationEligibility { allowed reason }
      cancellation { source cancelledAt awaitingPaymentOutcome }
      payment { status }
      refund { status amount currency failureReason }
    }
  }
}
```

```json
{
  "input": {
    "requestId": "fef50f9d-6fda-4d43-822a-f245c11077d7",
    "publicId": "071eb954-21cd-49cb-b5df-81f433c9b18d"
  }
}
```

Reload/poll guest confirmation using its **checkout** request UUID, not the
cancellation UUID:

```graphql
query Confirmation($requestId: UUID!) {
  guestOrder(requestId: $requestId) {
    publicId
    status
    totals { merchandiseSubtotal shippingCharge total currency }
    cancellationEligibility { allowed reason }
    cancellation { source cancelledAt awaitingPaymentOutcome }
    payment { status }
    refund { status amount currency failureReason }
  }
}
```

Authenticated clients use `checkoutOrder(requestId: ...)`, `order` or `myOrders`
with `order:read`. Guest and cancellation projections contain no User or live
catalog back-reference. Financial changes can be polled; the existing order-status
subscription is not a new durable payment/refund notification channel.

All direct Spring GraphQL responses, including authorization denials, are
`private, no-store`. Hive forwards successful response privacy headers; the
pinned router rewrites errored responses to `no-store, no-cache, must-revalidate`
without retaining the redundant `private` directive. Both prohibit caching.
Hive can wrap subgraph 401/403 as GraphQL errors at HTTP 200; always inspect errors.
Malformed JSON batches are rejected by Hive itself with HTTP 400 before Spring
execution. Those ingress errors contain no order data and the pinned router does
not add Cache-Control; do not cache GraphQL responses at the client/edge.

## Migration, rollout and legacy recovery

- Spring V14 adds financial/cancellation metadata, the irreversible release
  marker, cancellation receipts and immutable refund identity/finality.
- Go's new refund/provider-ledger migration follows 0002. Rebuild/deploy Go first
  in a coordinated maintenance window, then Spring, then compose/reload Hive.
  Preserve database/ledger volumes and consumer offsets; never reset them to
  roll out this feature.
- Existing CANCELLED stock-release markers are backfilled using the old
  cancellation invariant. Financial outcomes are derived only from correlated
  receipts, not PAID/CANCELLED labels. Proven paid cancellations enqueue one
  refund during migration; ambiguous history stays UNKNOWN.
- Older Spring listeners could discard a success arriving after cancellation.
  For those records, reconcile the original Go Payment and retained result
  outbox. Republish the **original result payload/IDs** through the normal
  result topic after checking order/request identity. Do not submit another
  payment request, charge, refund UUID or inferred financial success.
- No service reads another service's database. Operational inspection/replay
  is performed at the owning service boundary. Original request/results and
  idempotency ledger history must be retained for the recovery lifetime.

Direct-Spring tests and actual-Hive contracts verify transport separately.
Fixture-JWT tests prove authorization, not cryptographic authentication.
The implementation handoff records real Hydra-token verification and service
end-to-end checks separately, with executed commands/results and limitations.
No browser checkout/cancellation integration or production gateway is claimed.

## Implementation verification record

| Verification | Result and boundary |
| --- | --- |
| Combined Spring order/cart/checkout/payment/security/migration selection | 269 passed, 0 failed, 0 skipped |
| Final cancellation HTTP contracts | 44 passed across direct Spring and actual Hive, including selected operation, aliases/fragments, mixed roots, duplicate cookies, GET and batch rejection |
| Go 1.25.11 race-enabled test families | 38 top-level tests passed across events, eventtime, money, provider, store and Kafka; real PostgreSQL/Kafka, rollback, restart, loss/retry and offsets |
| Migration checks | Spring V13-to-V14 and fresh Flyway/JPA validation; Go 0002-to-0003, fresh schema and disposable down/up checks passed |
| Formatting/schema/build | Spotless, gofmt, `go vet ./...`, static Go server build, Spring `bootJar`, 9 Hive Python checks and `git diff --check` passed |
| Real authentication smoke | Hydra-issued JWT with untouched Spring decoder/filter: owner cancellation/replay and altered-signature rejection passed against an isolated database |
| Real financial service smoke | Actual Hive, Spring, Kafka and rebuilt Go: guest cancellation before payment delivery, late charge, shipping-inclusive 44.97 EUR refund, Go restart, duplicate original requests and confirmed decline passed |

The service smoke checked the durable simulator ledger: exactly one approved
CHARGE and one approved REFUND for the original Payment, even after Go restart
and duplicate payment/refund request delivery. Both retained 4497 cents/EUR;
original stock returned once. A separate 0.13 EUR guest charge was definitively
declined and its original inventory restored without a refund.

The actual-Hive authorization regression suites use fixture JWT decoding.
The real JWT smoke is separate direct-Spring evidence, not a claim that those
fixtures validate signatures. A later attempt to combine real-token issuance
with the financial smoke was blocked: the shared provisioning Spring endpoint
on port 8080 was offline and OAuth returned 502. No shared service was restarted
or replaced. The successful live financial smoke therefore covered guests;
authenticated financial behavior is covered by the Spring/Hive regression suites.
Isolated smoke containers/processes were stopped after verification.

To rerun the cancellation HTTP contracts:

```bash
cd backend
./gradlew test \
  --tests "ee.bytecore.backend.graphql.OrderCancellationGraphQlIntegrationTest" \
  --tests "ee.bytecore.backend.graphql.OrderCancellationHiveTransportTest" \
  spotlessCheck
```

The broader regression run also includes order transitions/services, deterministic
aggregate races, cart/account deletion, authenticated/guest checkout and Hive,
guest lifecycle, order subscriptions, payment listeners/outbox/correlation and
Flyway/guest-schema/recovery migrations. No full unrelated backend suite was run.
