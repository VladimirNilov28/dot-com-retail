# Guest Cart

Guest Cart is a server-persisted anonymous cart in the existing retail subgraph.
Hive remains the client-facing endpoint (`http://localhost:4002/graphql` in dev);
Spring remains the JWT, scope, ownership and guest-credential authority.
The [Checkout API](checkout.md) adds guest placement and secure confirmation
using a separate order cookie. No frontend page or new login flow is included.

## Transport and security

The server issues a 256-bit random credential in `retail_guest_cart`:

- HttpOnly, SameSite=Lax, host-only (no Domain), Path=/graphql.
- Secure in production; explicitly non-Secure for local HTTP in the dev profile.
- Max-Age follows the cart's absolute expiration. Visits do not extend it.
- Only the SHA-256 hash is persisted. Cart IDs and item IDs are not credentials.

Do not put this credential in URLs, GraphQL variables, ordinary cart fields,
logs, analytics, or browser storage. JavaScript does not need to read it.
Use one consistent hostname: `localhost:3000` and `localhost:4002` in dev,
not a mixture of localhost and 127.0.0.1.

Every guest operation, including initial creation and merge, requires:

```http
Content-Type: application/json
Origin: http://localhost:3000
X-Guest-Cart-Request: 1
```

Browsers set Origin automatically. Send POST requests with
`credentials: "include"`. Non-browser clients must supply the configured
allowed Origin and custom header, and maintain a private cookie jar.
Do not print response Set-Cookie or request Cookie/Authorization headers.

Hive forwards Cookie, Origin and X-Guest-Cart-Request only to retail, retains
Authorization forwarding, and propagates Set-Cookie as separate headers.
The response propagation rule needs `algorithm: append` on router 0.2.19.
Cookie-bearing guest responses are private/no-store, not shareable cache data.
Other cookies do not authenticate Spring or grant access to a user account.

The router's credentialed CORS policy uses exact origins. Spring independently
requires an allowlisted Origin and custom preflight-triggering header. Missing,
null, foreign or lookalike origins are rejected with HTTP 403, including requests
with a valid JWT attempting a cookie-authenticated merge. Ordinary bearer-only
user operations retain their existing behavior, even with a guest cookie present.

Anonymous requests may execute only selected guest root operations using the
transport ceremony above. The guard parses `operationName`, aliases and
fragments; anonymous protected/guest mixtures are rejected before any resolver
or mutation side effect. Public catalog and guest-cart roots cannot be combined
in one operation. Public catalog discovery is a separate credential-free path;
see the [GraphQL API](graphql-schema.md#public-catalog-discovery) for its root
allowlist and cache policy.
Guest credentials do not authorize myCart, user operations, federation SDL,
inventory staff fields, internal APIs or subscriptions. An invalid Bearer
header is not downgraded to anonymous access.

Use same-site HTTPS origins in production. Configure both Spring's allowed
origins and Hive's `cors.policies.origins`; setting one does not change the other.
The committed Hive configuration is for local development. Third-party cookies
across unrelated sites are not supported by this SameSite=Lax contract.
Future server-side Next.js requests must deliberately forward the guest cookie
and propagate Set-Cookie; do not assume server fetch handles a browser cookie jar.
A same-origin `/graphql` proxy can provide that boundary when SSR is implemented.

## Cart rules

Guest and user carts share `carts`, `cart_items`, variant prices and CartService.
The database enforces either authenticated ownership or guest hash/expiration,
never both, plus unique variants per cart and one persistent cart per user.
Separate GuestCart/GuestCartItem GraphQL types have no user/cart-owner traversal.

Guest quantities must be positive GraphQL/PostgreSQL integers; combined
quantities may not overflow that range. Existing duplicate variants combine.
There are no new arbitrary item/quantity caps. Guest add/update require an
existing active variant; removal still works after it becomes inactive.
Variant validation uses shared database locks, held to the transaction's end.
Add remains additive, like authenticated cart add; only merge has request-ID
idempotency. Do not blindly retry an add after an uncertain network response.

**Stock is checked only at checkout, as explicitly selected for this feature.**
Guest CRUD and merge do not validate single-warehouse stock, reserve inventory,
decrement it, or create inventory rows. A quantity exceeding current stock can
remain in a cart. Existing authenticated cart validation and checkout rules
are unchanged.

Totals use current persisted prices, BigDecimal and two-decimal monetary
precision. Prices and totals are never accepted from callers. Cart totals are
merchandise subtotals in the existing implicit EUR currency, not shipping/tax/
checkout quotes. Price changes appear on the next read. Cart.totals and
CartItem.subtotal are additive authenticated API fields.

## Expiration and retry

The default guest lifetime is 30 days from creation, not last visit.
Expired carts are denied before cleanup. Hourly bounded cleanup deletes expired
guest carts/items and expired merge receipts using SKIP LOCKED, without reserving
stock or holding user locks.

`guestCart` with no cookie returns null and creates nothing. An invalid, expired,
cleaned-up or consumed credential returns GraphQL
`extensions.errorType: "GUEST_CART_UNAVAILABLE"`.
An explicit startGuestCart reuses a valid cart or creates a new empty cart and
replaces an unusable credential. Its `created` flag distinguishes the cases;
expired contents are never restored.

Merge requires cart:read AND cart:write on the authenticated JWT and an active
canonical user. The destination comes only from the numeric principal, never
from a client-supplied owner/cart ID. Deleted/deleting accounts and non-numeric
subjects are rejected even on receipt replay.

The merge transaction locks user -> destination cart -> guest cart, serializing
with authenticated updates, checkout, deletion and other merge attempts. It
validates all affected guest lines before changing either cart. Existing user
items remain; matching variants combine; guest-only variants are added.
Unrelated existing user lines are retained, even if they became unavailable.

| Status | Meaning |
|---|---|
| MERGED | All guest quantities applied; receipt committed and guest cart deleted atomically; cookie cleared |
| REPLAYED | Same owner/request UUID returns the original per-line summary and current owned cart; no quantities applied again |
| BLOCKED | All conflicting variants reported; both carts, cookie and receipt state remain unchanged; no clamping or partial merge |

Conflicts are VARIANT_UNAVAILABLE or QUANTITY_OVERFLOW. Responses include user
and guest quantities and a safe message. Resolve them explicitly by removing/
editing guest lines and retrying. There are no silent adjustments. A blocked
first merge does not independently create an empty destination.

Keep one non-secret request UUID for a merge attempt across network/login
retries; do not generate a new UUID for every retry. A provided cookie on replay
must match the original source. Reusing the request UUID with a different guest
is rejected. After success, retry with the same JWT owner/request UUID works
without the cookie. A consumed credential with a new UUID is rejected and never
authorizes the destination. Another owner cannot replay the receipt.

Receipts are retained for 30 days by default. After cleanup, an old retry fails
explicitly; its deleted source cannot be applied again. Account deletion purges
merge receipts. A failed database transaction rolls back destination creation,
item changes, receipt and source deletion, and does not clear the cookie.

## Configuration and migration

Migration: `V12__guest_carts.sql`. It adds exclusive guest/user ownership,
guest hash/expiry indexes and `guest_cart_merge_receipts`. Historical V3
migrations and authenticated uniqueness/item constraints are unchanged.

| Spring property | Default |
|---|---|
| cart.guest.ttl | PT720H (30 days) |
| cart.guest.cleanup-interval | PT1H |
| cart.guest.merge-receipt-ttl | PT720H |
| cart.guest.cleanup-batch-size | 100 per table per pass |
| cart.guest.secure | true; dev profile explicitly false |
| cart.guest.allowed-origins | dev localhost:3000 and localhost:4002; production explicitly configured |

Durations accept ISO-8601 values and must be positive. Batch size must be positive.
`GUEST_CART_ALLOWED_ORIGINS` supplies a comma-separated production Spring
allowlist; render the equivalent exact origins into the production Hive config.
Standard Spring property/environment overrides apply to the other settings.

## GraphQL examples

The following operations use the transport above. The existing AddCartItemInput
and UpdateCartItemInput are reused, with no client price/total fields.

### 1. Start and retrieve between visits

```graphql
mutation StartGuestCart {
  startGuestCart {
    created
    cart { id expiresAt totals { subtotal currency } }
  }
}

query GuestCart {
  guestCart {
    id expiresAt
    totals { subtotal currency }
    items {
      id quantity subtotal
      productVariant {
        id sku price attributes isActive
        product { id name slug }
      }
    }
  }
}
```

### 2. Add an item

```graphql
mutation AddGuestItem($variantId: ID!, $quantity: Int!) {
  addGuestCartItem(input: { productVariantId: $variantId, quantity: $quantity }) {
    items { id quantity subtotal productVariant { id sku price } }
    totals { subtotal currency }
  }
}
```

### 3. Update quantity

```graphql
mutation UpdateGuestItem($itemId: ID!, $quantity: Int!) {
  updateGuestCartItem(cartItemId: $itemId, input: { quantity: $quantity }) {
    items { id quantity subtotal }
    totals { subtotal currency }
  }
}
```

### 4. Remove an item

```graphql
mutation RemoveGuestItem($itemId: ID!) {
  removeGuestCartItem(cartItemId: $itemId) {
    items { id quantity }
    totals { subtotal currency }
  }
}
```

### 5. Retrieve totals

```graphql
query GuestTotals {
  guestCart { totals { subtotal currency } }
}
```

### 6. Merge after authentication

Registration is Kratos browser self-service; `POST /auth/register` returns 410.
For this backend API, obtain an appropriately scoped Hydra token first, then send the JWT's Bearer header,
guest cookie and guest-request header together:

```graphql
mutation MergeGuestCart($requestId: UUID!) {
  mergeGuestCart(requestId: $requestId) {
    status
    cart {
      id totals { subtotal currency }
      items { id quantity productVariant { id sku } }
    }
    mergedItems {
      productVariantId userQuantityBefore guestQuantityAdded finalQuantity
    }
    conflicts { productVariantId code userQuantity guestQuantity message }
  }
}
```

### 7. Handle expiration and conflicts

On GUEST_CART_UNAVAILABLE, explicitly call StartGuestCart to begin empty.
On BLOCKED, display every conflict, edit/remove affected guest items and retry.
On MERGED/REPLAYED, render the returned owned cart; its guest credential is gone.
Check GraphQL errors even when HTTP status is 200.

Anonymous browser request shape (documentation only; cart UI is not implemented):

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
```

Authenticated storefront tokens remain in the server-only BFF; never add them
to this browser snippet. The current BFF grant is `user:read`, not cart scopes,
so this authentication package does not implement browser merge/cart mutations.
See [the browser contract](../auth/browser-contract.md) for the implemented
login/registration/callback and retained legacy dev-client boundary.

## Verification boundaries

GuestCartGraphQlIntegrationTest and GuestCartLifecycleIntegrationTest exercise
real Spring HTTP/security and isolated Postgres, including merge/update/checkout
concurrency, transaction rollback, expiry/cleanup and inactive account rejection.
JWT decoding is stubbed; scopes/principals use the real Resource Server/filter
and JWT converter. GuestCartSchemaTest exercises actual database constraints.

GuestCartHiveTransportTest runs an isolated pinned Hive router against real
Spring/Postgres, checking cookie propagation, independent clients, merge and
receipt replay. The separate transport fixture also checked multiple Set-Cookie
headers and credentialed CORS/preflight. These are not browser cookie tests
or Next.js integration tests. Guest merge used fixture JWT decoding; a fresh
real dev token was separately obtained through the existing auth service for
authenticated runtime SDL retrieval, not for an end-to-end browser login/merge.
Existing authenticated cart, HTTP JWT, subscription, account-deletion and
checkout regression suites remain the evidence for preserved behavior.

Final snapshots were regenerated from the updated Spring runtime using the
existing authenticated composition script and pinned Rover/Federation settings.
No shared service was manually restarted (Spring's existing development watcher
picked up compiled changes). For subsequent runtime regeneration, use the existing authenticated
`infrastructure/hive/scripts/compose-supergraph.sh` against the updated app.

Guest checkout consumes a cart atomically with its PENDING order, inventory
decrement, payment outbox and durable checkout receipt. Confirmation/replay use
the independently pre-issued `retail_guest_orders` cookie, not an order ID,
email or unrelated cart credential. See [Checkout API](checkout.md) for quote,
shipping, snapshot, expiration and lost-response behavior.
