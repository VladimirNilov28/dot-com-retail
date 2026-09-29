# GraphQL Manual Test Cases - Order Status, Inventory Decrement & Cancellation Restock

Target endpoint: Hive Router GraphQL IDE at `http://localhost:4002` (proxies to
Spring/DGS on `:8080`). Authenticate with a valid JWT (Ory Hydra/Kratos) for
all operations below.

Schema reference: `backend/src/main/resources/schema/payment/*.graphqls`,
`backend/src/main/resources/schema/cart/*.graphqls`.

## Setup

Add cart items first, noting `productVariantId`s and their known warehouse
stock levels (via seeded test data / `make seed-test-data`):

```graphql
mutation {
  addCartItem(input: { productVariantId: "1", quantity: 2 }) {
    id
    quantity
    productVariant { id name }
  }
}
```

---

## Queries

### 1. My cart

```graphql
query {
  myCart {
    id
    items {
      id
      quantity
      productVariant { id name }
    }
  }
}
```

### 2. My orders

```graphql
query {
  myOrders {
    id
    publicId
    status
    totalAmount
    items {
      productVariant { id }
      quantity
      priceAtPurchase
    }
  }
}
```

### 3. Order by id

```graphql
query {
  order(id: "1") {
    id
    status
    totalAmount
  }
}
```

### 4. Order by publicId

```graphql
query {
  order(publicId: "<uuid>") {
    id
    status
  }
}
```

### 5. Non-existent order

Query a random/unused id - expect `null` result (or a well-formed GraphQL
error), never an HTTP 500 / stack trace leak.

---

## Mutations - `createOrder` (inventory tests)

```graphql
mutation {
  createOrder {
    id
    status
    totalAmount
    items {
      quantity
      priceAtPurchase
      productVariant { id }
    }
  }
}
```

| # | Setup | Expected result |
|---|-------|------------------|
| 1 | Cart qty 2, stock 5 | Order created; stock becomes 3; cart cleared |
| 2 | Cart qty = stock exactly (e.g. qty 5, stock 5) | Order created; stock becomes 0 |
| 3 | Cart qty 6, stock 5 | `InsufficientStock` error (GraphQL `CONFLICT`); no order created; stock unchanged; cart unchanged |
| 4 | Empty cart | Error; no order created |
| 5 | Two variants in cart, one has insufficient stock | Whole mutation fails; **neither** inventory row is decremented; no order created; cart unchanged |
| 6 | Variant split across warehouses A (stock 2) / B (stock 3), request qty 5 | `InsufficientStock` - split fulfillment is out of scope, so this must fail even though combined stock is sufficient |
| 7 | Variant in warehouse A (stock 2) / B (stock 10), request qty 5 | Order succeeds, fulfilled entirely from warehouse B (deterministic single-row selection) |

---

## Mutations - `updateOrderStatus` (transition tests)

```graphql
mutation($id: ID!, $status: OrderStatus!) {
  updateOrderStatus(orderId: $id, input: { status: $status }) {
    id
    status
  }
}
```

| From -> To | Expected |
|-----------|----------|
| `PENDING` -> `PAID` | [OK] Allowed |
| `PAID` -> `SHIPPING` | [OK] Allowed |
| `SHIPPING` -> `COMPLETED` | [OK] Allowed |
| `PENDING` -> `CANCELLED` | [OK] Allowed; inventory restored to original level |
| `PAID` -> `CANCELLED` | [OK] Allowed; inventory restored to original level |
| `SHIPPING` -> `CANCELLED` | [FAIL] Rejected; status and stock remain unchanged |
| `PENDING` -> `SHIPPING` (skip a step) | [FAIL] Rejected |
| `PAID` -> `PENDING` (backwards) | [FAIL] Rejected |
| `PENDING` -> `PENDING` (same status) | [FAIL] Rejected |
| `COMPLETED` -> anything | [FAIL] Rejected (terminal state) |
| `CANCELLED` -> `CANCELLED` (cancel twice) | [FAIL] Rejected - must not restock twice |
| Called by a user without `ORDER_MANAGER`/`ADMIN` role | [FAIL] `FORBIDDEN` |
| Called by `ADMIN`/`ORDER_MANAGER` but missing the `order:manage-status` scope | [FAIL] `FORBIDDEN` (role alone must not bypass the scope check) |

**Verifying restock:** after a successful cancellation, confirm the
inventory quantity returned to its pre-order level - e.g. by re-running
`createOrder` for the same quantity again and confirming it succeeds, or by
inspecting inventory directly if/when a query is exposed for it.

---

## Subscription

```graphql
subscription($id: ID!) {
  orderStatusChanged(orderId: $id) {
    id
    status
    updatedAt
  }
}
```

Test using two GraphQL clients/tabs:

1. Open the subscription above for a known `orderId` in client A.
2. In client B, run a **valid** transition (e.g. `PENDING` -> `PAID`) via
   `updateOrderStatus`. Expect client A to receive **exactly one** event with
   the new status.
3. In client B, attempt an **invalid** transition (e.g. `SHIPPING` ->
   `CANCELLED`). Expect client A to receive **no** event, and a follow-up
   `order(id: ...)` query to still show the prior (unchanged) status.
4. Subscribe to an order id that belongs to a different user. Confirm the
   current authorization/ownership behavior (should be denied or return no
   data - verify against actual implementation, since this was not
   explicitly changed as part of this feature).
