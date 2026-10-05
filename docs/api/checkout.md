# Checkout API

Checkout is served by the existing retail GraphQL subgraph through Hive at
`http://localhost:4002/graphql`. No frontend, carrier, production gateway or
email recovery is implemented. Guest carts are described in [Guest Cart](guest-cart.md).

## Operations and compatibility

| Operation | Access |
| --- | --- |
| `checkoutShippingOptions(countryCode: String)` | Development catalog; anonymous through protected guest transport |
| `checkoutPreview(input: CheckoutInput!)` | Active principal, `cart:read` and `order:write` |
| `guestCheckoutPreview(input: CheckoutInput!)` | Live guest cart cookie and protected transport |
| `createOrder(input: PlaceOrderInput!)` | Active principal, `order:write`; owner derived from JWT |
| `createGuestOrder(input: PlaceOrderInput!)` | Live guest cart and independent confirmation cookies |
| `checkoutOrder(requestId: UUID!)` | Active original owner and `order:read` |
| `guestOrder(publicId: UUID, requestId: UUID)` | Exactly one reference plus valid independent confirmation cookie |

**Intentional breaking transition:** public no-argument `createOrder` is replaced
by required checkout input. It cannot bypass address validation, shipping,
quote acceptance or request identity. The internal legacy `createOrder(Long)`
service entry remains for existing internal consumers/tests and shares the
order/inventory/outbox machinery; it is not publicly exposed. No client-supplied
customer ID is accepted. Existing order reads/status/subscriptions retain their
scope/role requirements; `Order.user` is now nullable for genuine guest orders.

Authenticated placement still returns `Order`. Select `Order.checkout` for the
snapshot confirmation (requires `order:read`), or retrieve `checkoutOrder` after
a reload. Guest operations return only `CheckoutOrder`, without User,
OrderItem.order or live catalog traversal. `publicId` is the order reference,
not a credential. Payment interaction is `NONE`, not a payment token.

## Selections, validation and totals

`CheckoutInput` has contactEmail/contactPhone, shippingMethod, saved shipping/
billing address IDs or inline address values, billingSameAsShipping,
paymentSelection and optional paymentMethodId. Delivery requires exactly one
shipping address source. Pickup requires no shipping address; supplying one is
an error. Separate billing and billingSameAsShipping are mutually exclusive;
the latter requires a delivery address. Billing may be omitted, including for
pickup; the simulator does not require a billing address.

Authenticated omitted contactEmail uses the canonical account email. Guests
must provide a structurally valid email. Saved address/payment selections
require active principal ownership and are unavailable to guests. Saved
country values must be supported alpha-2 codes; free-text country names are
not guessed. Inline and saved addresses are both validated at checkout.

Address fields: firstName/lastName (required, 50 characters), city (required,
100), countryCode (required supported ISO alpha-2), postalCode (required, 10),
addressLine1 (required, 255), addressLine2 (optional, 255), phone (optional, 20).
Contact email is limited to 255 characters and contact phone to 20. Values
are trimmed; control characters, blank required fields, unsupported country
codes and malformed combinations are rejected. This is structural validation,
**not postal deliverability or phone-number verification**.

Only `SIMULATED` payment is supported. A saved method may be selected only if
owned by the active customer, provider is `simulated` (case-insensitive), and
type is not CASH_ON_DELIVERY. Type is historical descriptive metadata; CARD,
BANK_TRANSFER and DIGITAL_WALLET do not connect to real instruments. Other
providers/COD are rejected. No card/PAN/CVV or payment credentials are accepted,
stored or returned. The simulator asynchronously approves all amounts except
those whose final EUR cents component equals 13, which it declines.

Every preview line contains productVariantId, productName, SKU, attributes,
quantity, unitPrice and subtotal. Totals contain merchandiseSubtotal,
shippingCharge, total and currency. All amounts are server-calculated
BigDecimal with two-decimal precision in EUR. Final total is merchandise plus
shipping; no new tax or discount behavior is included. Amounts must fit the
existing DECIMAL(10,2) storage precision.

Preview reserves/decrements no stock, creates no order/payment request/receipt,
and consumes nothing. Guest preview may issue a cookie, not a persisted
Checkout entity. Empty carts, inactive variants and insufficient stock appear
in `issues`; `placeable` is false. Structurally invalid selections and foreign
saved resources produce GraphQL errors. Products have no independent active
flag; variant availability uses the existing `isActive` flag.

## Shipping catalog and configuration

| Method | Development charge | Development estimate |
| --- | --- | --- |
| STANDARD | EUR 4.99 | 3-5 business days |
| EXPRESS | EUR 9.99 | 1-2 business days |
| PICKUP | EUR 0.00 | Simulated readiness in 1 business day |

Delivery initially supports **EE only**. Options expose supportedCountries,
estimate, charge/currency and the pickup location. An unsupported but valid
country filters out delivery options; pickup remains available at its
configured location. A selected delivery/billing address must use a supported
country. This is not a carrier integration or an operational dispatch promise.

The default pickup is labeled **Development pickup fixture - not a staffed
location**, with an example Tallinn address. Replace it with an actual location
before a production release; no physical store readiness is asserted.

Standard Spring property/environment overrides apply:

| Property | Default |
| --- | --- |
| `checkout.standard-charge` / `express-charge` / `pickup-charge` | 4.99 / 9.99 / 0.00 |
| `checkout.standard-estimate` / `express-estimate` / `pickup-estimate` | Descriptions above, explicitly development/simulated |
| `checkout.supported-countries` | `[EE]` |
| `checkout.pickup-name` | Development fixture label |
| `checkout.pickup-address` | Address fields listed above, example Tallinn fixture |
| `checkout.guest-order-ttl` | PT720H (30 days) |

Country codes, positive lifetime, monetary scale/range, required pickup address
and nonblank descriptions are validated at startup. Receipt cookie security
and exact-origin policy reuse `cart.guest.secure`/`allowed-origins`; Secure is
true except explicitly local HTTP dev. Config changes require a new quote;
existing order snapshots are unaffected.

## Quote acceptance and durable retries

Preview returns a deterministic `quoteVersion`: SHA-256 over versioned canonical
server quote data (source cart, sorted lines/prices/snapshot details, normalized
contact/addresses, shipping/location, simulated selection, totals and currency).
It is a comparison version, not an authentication credential or signed
reservation. Client-submitted amounts never determine pricing.

Keep a stable client-generated request UUID and the exact accepted selections
and quote version for one placement attempt. Recalculate under locks at
placement. `CHECKOUT_QUOTE_CHANGED` means no order was placed: fetch another
preview, display it and require explicit acceptance before resubmission. Do not
automatically charge a changed amount. `CHECKOUT_INVALID` means cart/stock
validation failed. Check GraphQL errors even with HTTP 200.

The database binds each successful request UUID globally to its owner/guest
identity, source cart, canonical submitted payload hash and original order.
Identical authorized retries return that order, even after cart consumption or
saved address/payment/catalog/config changes. Changed payload/source produces
`CHECKOUT_REQUEST_CONFLICT`; another owner is denied. Receipt lookup precedes
current cart/quote resolution on replay. A UUID alone grants no order access.

Idempotency identity is retained with the order, including after guest access
expiry; expiry cannot allow the old source to be purchased again. A different
UUID on an already-consumed cart fails instead of creating another purchase.
After a validation/transaction failure, the same request can be retried with
corrected/renewed selections because no successful identity was persisted.

## Transaction, inventory and payment

OrderService remains the only order-processing pipeline. One placement
transaction writes order/items/snapshots, inventory decrement, payment outbox,
request identity/guest grant and cart consumption. Failure rolls them all back.
Purchased items are removed transactionally and become cleared only on commit,
not after eventual payment success. Authenticated carts are reusable; consumed
guest carts are deleted. Cookie changes occur only after successful service
commit.

Lock order: request-UUID transaction advisory lock -> active user (authenticated
only) -> cart(s) -> product rows in ascending ID -> variant rows in ascending ID
-> saved address/payment rows -> inventory by ascending variant ID and row ID
-> new order/receipt/outbox writes. Merge retains user -> destination -> guest
cart -> variant locks. Guest placement never acquires a user lock after a guest
cart lock. Account deletion locks the user first. Identical request UUIDs
serialize independently of source-cart existence; unique database identity is
the final constraint. Advisory key collisions only add serialization.

After locking, catalog and inventory entities are refreshed so managed values
loaded during preview/lock waits cannot overwrite a competing committed stock
change. Cart mutations/merge/placement serialize on existing cart locks.

Warehouse policy is unchanged: each line uses the lowest-ID row able to fulfil
its full quantity; different lines may use different warehouses. No guest-only
single-warehouse rule exists. Splitting one line over multiple warehouses is
still unsupported even if combined stock would suffice. Preview uses the same
single-row feasibility rule; placement locks and rechecks.

Orders start **PENDING**. The final total and EUR are stored in the same
transaction's PaymentRequestedEvent/outbox. Kafka publication and provider
charging occur later, outside this database transaction. Guest userId is
genuinely null across Spring/Go persistence, never zero or a synthetic user.
Existing request/result correlation, terminal protection and persisted Payment
UUID provider idempotency are preserved. Failure cancels/restores the exact
allocated inventory according to existing rules; it never repopulates a cart.

## Historical snapshots and migrations

Spring `V13__checkout_orders.sql` adds nullable guest ownership, order and item
JSONB snapshots, checkout_requests and constraints/immutable-snapshot triggers.
Go `0002_guest_ownership.up.sql` makes payment owner nullable; deploy that
service version **before enabling guest placement**. Earlier migrations are
unchanged.

New orders retain product name, SKU, deep-copied attributes, unit price/quantity/
line subtotal; contact email/phone; shipping/billing address or pickup location;
shipping method/estimate/charge; merchandise subtotal/final total/currency and
non-secret simulated payment description. Later edits cannot alter these facts.
Database triggers reject snapshot/financial/line identity changes; state and
cancellation/timestamps remain mutable.

Legacy orders keep original priceAtPurchase/quantity/totalAmount. `Order.checkout`
and `OrderItem.snapshot` are null if unavailable: no historical addresses,
shipping charges, product names or pickup data are fabricated. Existing live
productVariant fields remain compatibility links, not historical facts.
Account deletion retains existing historical order ownership under its
anonymized user; deleted/deleting users cannot retrieve checkout data.
Guest history is never linked to a new account by matching emails.

## Guest confirmation credential

`guestCheckoutPreview` issues/reuses a **separate** 256-bit opaque
`retail_guest_orders` cookie. Complete preview and retain its response cookie
before placement. Placement without this cookie is rejected. Only its SHA-256
hash is persisted as a grant to the resulting order. Source cart credentials
cannot read arbitrary confirmations, and order IDs/references/emails cannot
authorize access.

Cookie: HttpOnly, host-only, SameSite=Lax, Path=/graphql, Secure outside local
HTTP dev. Each order's grant has an absolute expiry 30 days after placement.
Preview/placement/replay may refresh cookie Max-Age but never extend existing
grants. One retained cookie supports multiple explicitly granted guest orders.
Expired grants fail before any cleanup; minimal receipt/grant hash records
remain with the order for durable retry identity.

Successful placement clears only `retail_guest_cart` and preserves/refreshes
the confirmation cookie after commit. If the entire placement response is
lost, the confirmation cookie already established by preview still authorizes
request lookup/replay even though the source cart was consumed. A source cookie
still supplied on replay must match the original source. Replay with only the
confirmation cookie works. The original preview cookie may expire earlier
than a refreshed placement cookie if that response was lost.

`GUEST_ORDER_UNAVAILABLE` covers missing/malformed/foreign/expired confirmation
credentials or unavailable orders without revealing their existence. If the
browser loses the confirmation cookie or the grant expires, this milestone
provides no email recovery. It does not automatically create/link an account.

All guest roots (including the catalog) require JSON POST, exact allowlisted
Origin and `X-Guest-Cart-Request: 1`, with `credentials: "include"`. Existing
operationName/aliases/fragments/mixed-root restrictions and invalid-JWT rejection
remain. An authenticated JWT does not replace a guest-order grant.

Hive forwards cookies/Origin/header only to retail and appends independent
Set-Cookie headers. Checkout/confirmation responses are private/no-store.
Subgraph HTTP 401/403 are wrapped by Hive as GraphQL errors at HTTP 200; Spring
direct tests assert their actual HTTP statuses. Always inspect GraphQL errors.
Never log Cookie/Authorization/Set-Cookie or put bearer values in URLs,
GraphQL response fields/variables, analytics or browser storage.

Future Next.js should use same-site HTTPS or a same-origin `/graphql` proxy,
forward only intended cookies during server fetches, and propagate each
Set-Cookie separately back to the browser. Server-side fetch does not maintain
the browser cookie jar automatically. JavaScript never reads either HttpOnly
credential. This contract does not include browser/Next.js implementation.

## Working request examples

Use a private cookie jar for non-browser guest requests and supply
`Content-Type: application/json`, `Origin: http://localhost:3000`,
`X-Guest-Cart-Request: 1`. Browser requests set Origin automatically:

```javascript
const response = await fetch("http://localhost:4002/graphql", {
  method: "POST",
  credentials: "include",
  headers: {
    "Content-Type": "application/json",
    "X-Guest-Cart-Request": "1",
  },
  body: JSON.stringify({ query, variables }),
});
const result = await response.json();
if (result.errors) throw new Error(result.errors[0].message);
```

Authenticated requests additionally use an access token from the existing
Hydra/Kratos flow. Do not print it or introduce another login system.

Start/populate a guest cart using the Guest Cart guide, then:

```graphql
query Options {
  checkoutShippingOptions(countryCode: "EE") {
    method charge currency estimate supportedCountries
    pickupLocation { name address { city countryCode addressLine1 } }
  }
}

query Preview($input: CheckoutInput!) {
  guestCheckoutPreview(input: $input) {
    quoteVersion placeable issues { code productVariantId message }
    lines { productVariantId productName sku attributes quantity unitPrice subtotal }
    totals { merchandiseSubtotal shippingCharge total currency }
  }
}
```

Preview variables for delivery:

```json
{
  "input": {
    "contactEmail": "guest@example.com",
    "shippingMethod": "STANDARD",
    "shippingAddress": {
      "firstName": "Ada",
      "lastName": "Example",
      "city": "Tallinn",
      "countryCode": "EE",
      "postalCode": "10111",
      "addressLine1": "Example street 1"
    },
    "billingSameAsShipping": true,
    "paymentSelection": "SIMULATED"
  }
}
```

For pickup use `{ "contactEmail": "guest@example.com",
"shippingMethod": "PICKUP", "paymentSelection": "SIMULATED" }` instead, without
a shipping address. Preserve the preview cookie. Reuse those exact selections
as `input.checkout`, copy the returned quoteVersion to acceptedQuoteVersion and
generate a request UUID once:

```graphql
mutation Place($input: PlaceOrderInput!) {
  createGuestOrder(input: $input) {
    publicId requestId status paymentInteraction
    totals { merchandiseSubtotal shippingCharge total currency }
    details {
      contactEmail
      shippingAddress { firstName lastName addressLine1 city countryCode postalCode }
      billingAddress { addressLine1 city countryCode }
      shipping { method charge estimate pickupLocation { name } }
    }
    lines { productName sku attributes quantity unitPrice subtotal }
  }
}

query Recover($requestId: UUID!) {
  guestOrder(requestId: $requestId) {
    publicId requestId status cancellationReason
    totals { merchandiseSubtotal shippingCharge total currency }
    lines { productName sku quantity unitPrice subtotal }
  }
}
```

Placement variable shape (replace the quote with the actual preview result):

```json
{
  "input": {
    "requestId": "bf30dc18-8e22-4dcb-a980-a358e584ee52",
    "acceptedQuoteVersion": "<64-character quoteVersion returned by Preview>",
    "checkout": {
      "contactEmail": "guest@example.com",
      "shippingMethod": "PICKUP",
      "paymentSelection": "SIMULATED"
    }
  }
}
```

An identical Place retry returns the original order. A changed quote/payload
requires explicit handling, not blind resubmission with a new UUID.

Authenticated customers use `checkoutPreview` with an owned saved address ID or
the same inline shape. Example:

```graphql
query PreviewMine($input: CheckoutInput!) {
  checkoutPreview(input: $input) {
    quoteVersion placeable issues { code message }
    totals { merchandiseSubtotal shippingCharge total currency }
  }
}

mutation PlaceMine($input: PlaceOrderInput!) {
  createOrder(input: $input) {
    publicId status totalAmount
    checkout {
      requestId paymentInteraction
      details { contactEmail shipping { method charge } }
      lines { productName sku attributes quantity unitPrice subtotal }
      totals { merchandiseSubtotal shippingCharge total currency }
    }
  }
}

query RecoverMine($requestId: UUID!) {
  checkoutOrder(requestId: $requestId) {
    publicId status totals { total currency }
  }
}
```

## Verification boundaries and checklist mapping

CheckoutIntegrationTest exercises actual PostgreSQL migrations, calculations,
ownership, quote changes, snapshots, rollback, replay, cart/merge/account
concurrency, competing stock purchases and correlated guest payment settlement/
failure/restock. CheckoutGraphQlIntegrationTest exercises actual Spring HTTP
security and operation shapes. CheckoutHiveTransportTest runs those contracts
through an isolated pinned Hive router after authenticated runtime composition.
HTTP JWT decoding is a fixture, not real login/signature validation. Existing
order/cart/payment/security/account suites supply compatibility evidence.
Go guest tests exercise null event ownership, actual Postgres persistence and
actual Kafka simulated processing; existing provider idempotency tests retain
their persisted Payment UUID contract.

No browser/Next.js pages, real authentication journey, carrier service or
production gateway was tested or claimed.

Implementation verification: 354 related backend tests passed with no skips;
subsequent focused snapshot/history and final owned-address/calculation suites
also passed. The full direct-Spring and actual-Hive checkout contracts passed.
Spotless and the eight Hive composition/schema-contract checks passed. The Go
payment suite, new guest event/store/Kafka cases and persisted-provider
idempotency race-detector suite passed. Hive composition used authenticated
runtime SDL with fixture JWT decoding, not a real Hydra login.

Checklist #3 Shipping: catalog, charges, estimates/destinations, pickup
configuration/validation, quote acceptance and historical shipping facts are
covered. Real carriers, labels/tracking, operational dispatch/pickup remain.
Checklist #4 Order Snapshot: product/variant/price/quantity/line facts,
contact/address/pickup, shipping and complete monetary snapshots are covered.
Legacy unknown history cannot be reconstructed; unrelated invoices/taxes/
discounts remain outside scope.
