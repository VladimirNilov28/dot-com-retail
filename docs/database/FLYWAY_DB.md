# Database Schema Documentation

## Overview

Schema is managed via Flyway migrations. Flyway is the single source of truth
for the database structure — JPA/Hibernate is configured with
`ddl-auto: validate` and never generates or alters schema itself.

Currently in dev mode: existing migrations may still be edited in place
(database is recreated from scratch). Once the project moves to a
staging/production-like environment, migrations become append-only —
schema changes must be new `V{n}__...sql` files instead.

## Migration Files

| File | Tables Created | Depends On |
|---|---|---|
| `V0__init_functions.sql` | — (defines `set_updated_at()` trigger function) | — |
| `V1__create_users.sql` | `users`, `user_address`, `user_payment_methods` | `V0` (trigger function) |
| `V2__create_products.sql` | `products`, `product_variants` | `V0` |
| `V3__create_carts.sql` | `carts`, `cart_items` | `V1` (users), `V2` (product_variants) |
| `V4__create_payments.sql` | `orders`, `order_items`, `payment_details` | `V1` (users), `V2` (product_variants), `V3` (carts) |
| `V5__create_categories.sql` | `categories`, `product_categories` | `V2` (products) |
| `V6__create_inventory.sql` | `warehouses`, `inventory` | `V2` (product_variants) |
| `V7__create_wishlist.sql` | `wishlists`, `wishlist_items` | `V1` (users), `V2` (product_variants) |

Every foreign key either points to a table created in an earlier migration
file, or to a table created earlier within the same file. There are no
forward references.

## Entity Relationship Diagram

```mermaid
erDiagram
    users ||--o{ user_address : has
    users ||--o{ user_payment_methods : has
    users ||--o| carts : owns
    users ||--o{ orders : places
    users ||--o| wishlists : owns

    products ||--o{ product_variants : has
    products ||--o{ product_categories : "tagged via"
    categories ||--o{ product_categories : "tagged via"
    categories ||--o{ categories : "parent of"

    carts ||--o{ cart_items : contains
    product_variants ||--o{ cart_items : "referenced by"

    orders ||--o{ order_items : contains
    orders ||--o{ payment_details : "paid via"
    product_variants ||--o{ order_items : "referenced by"
    carts |o--o| orders : "checked out into"

    warehouses ||--o{ inventory : stocks
    product_variants ||--o{ inventory : "stocked as"

    wishlists ||--o{ wishlist_items : contains
    product_variants ||--o{ wishlist_items : "referenced by"
```

## Tables

### users / user_address / user_payment_methods
Core account data. A user has zero or more addresses and saved payment
methods. `users.role` is a native Postgres enum (`admin`, `user`, `support`).

### products / product_variants
`products` holds the shared identity of an item (name, description).
`product_variants` holds the purchasable SKUs — price, physical attributes
(`weight_grams`, `barcode`), and free-form `attributes` (`jsonb`, GIN-indexed)
for category-specific specs like color or size that don't warrant dedicated
columns. `is_active` allows soft-disabling a variant without deleting it.

Stock is **not** tracked here — see `inventory`.

### categories / product_categories
`categories` is self-referencing (`parent_id`) to support arbitrary-depth
sub-categories rather than a fixed two-level split. `product_categories` is
the many-to-many join between products and categories.

Hierarchy mutations acquire PostgreSQL's transaction-scoped
`SHARE ROW EXCLUSIVE` table lock before reading ancestors. This serializes
create/update/delete decisions (including concurrent opposite reparenting)
while leaving ordinary reads available. Self/descendant parenting and
already-cyclic parent chains are rejected before commit. Category mapping
uses iterative traversal and reports the repeated category id instead of
recursing indefinitely or silently truncating a corrupt hierarchy.

**Existing-cycle policy:** do not automatically change shared catalog data.
An administrator can detect affected ancestor chains with this read-only,
bounded query:

```sql
WITH RECURSIVE ancestors AS (
    SELECT id AS start_id, id, parent_id, ARRAY[id] AS path, false AS cycle
    FROM categories
    UNION ALL
    SELECT a.start_id, c.id, c.parent_id, a.path || c.id, c.id = ANY(a.path)
    FROM ancestors a
    JOIN categories c ON c.id = a.parent_id
    WHERE NOT a.cycle
)
SELECT start_id, path FROM ancestors WHERE cycle;
```

Repair requires an explicit, reviewed choice of the incorrect parent link:
record the affected ids/links, acquire the same hierarchy table lock in a
maintenance transaction, and detach that approved link (`parent_id = NULL`)
or replace it with a verified acyclic parent. Rerun detection before commit.
Preserve category ids and product membership; do not delete/reseed categories
or rewrite applied Flyway migrations to hide corrupt data.

### carts / cart_items
A user's in-progress selection before checkout. `carts.user_id` is unique;
checkout clears and reuses that cart rather than creating a new cart per
order. `cart_items` has unique `(cart_id, product_variant_id)` membership.
Lazy cart creation uses a separate `REQUIRES_NEW` transaction so a losing
creation race does not poison the caller's transaction.

Add/update/remove/clear and checkout acquire the owning cart's
`PESSIMISTIC_WRITE` lock before reading its items and retain it until the
entire operation commits. Thus overlapping additions merge quantities and
checkout cannot erase an addition that serialized after its snapshot.

### orders / order_items / payment_details
`orders.cart_id` is a nullable, non-unique reference to the cart it
originated from — informational only. The actual purchased items are
snapshotted into `order_items` (quantity + `price_at_purchase`) at checkout
time, so later price changes on `product_variants` never affect order
history. `orders.total_amount` is stored directly rather than derived via
`SUM(order_items)`, since order total is an immutable historical fact and
this avoids a join on every order-list query.

`payment_details` records one or more payment attempts against an order
(`payment_status`: `SUCCESS` / `FAILED` / `PENDING`).

Status transitions lock the order row before validating its current state.
Cancellation and exact-warehouse restoration commit or roll back together;
duplicate/late cancellation cannot restore inventory twice. Subscription
notifications are emitted only after the transition transaction commits.

### warehouses / inventory
Stock is tracked per warehouse. `inventory` has a unique
`(product_variant_id, warehouse_id)` pair — total stock for a variant is the
sum of its rows across warehouses. This replaced the earlier
`product_variants.stock_quantity` column to avoid two sources of truth for
stock once multi-warehouse tracking was introduced.

### wishlists / wishlist_items
One wishlist per user (`UNIQUE` on `user_id` is safe here, unlike carts,
because a wishlist isn't "consumed" by a purchase).

## Known Open Points

- **Native Postgres enums vs JPA**: `user_role`, `payment_status`, and
  `order_status` are native Postgres `ENUM` types. Hibernate's default
  `@Enumerated(EnumType.STRING)` maps to `varchar`, not a native enum, which
  will fail `ddl-auto: validate`. Either map explicitly with
  `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` (Hibernate 6+), or switch these
  columns to `varchar` + `CHECK` constraint for simpler JPA compatibility.
- **`product_variants.attributes` (`jsonb`)**: flexible but unvalidated at
  the DB level — worth enforcing structure at the application layer per
  category if this grows.