# Product reviews: purchase eligibility and helpful sorting

This page covers the review rules added on top of the review model described in
[graphql-schema.md](graphql-schema.md#p1-written-reviews-and-moderation-78-79-85)
and [FLYWAY_DB.md](../database/FLYWAY_DB.md#reviews-and-helpful-votes-v22).
Ownership, one review per user and product, moderation (hide/restore, audited),
rate limits, vote uniqueness, the self-vote restriction and the computed
aggregates are unchanged.

## Who can review: verified purchase policy

A customer can **create** a rating or written review (`rateProduct`,
`upsertMyReview`) only if they have a *qualifying purchase* of the product.
`ProductRatingService` enforces this on the server, so calling the API directly
gets the same rejection as the storefront.

A qualifying purchase is an order that:

- belongs to the caller (`orders.user_id`). Guest orders never qualify.
- contains an `order_items` row for any variant of the product.
- has `payment_status = SUCCEEDED` and `status` in
  `PAID`, `PROCESSING`, `SHIPPING` or `COMPLETED`.
- has no **succeeded** refund (`order_refunds.status = SUCCEEDED`).

| Order situation | Qualifies? |
|---|---|
| Paid, and in processing, shipping or completed | Yes |
| Unpaid, payment pending or unresolved (`PENDING` order) | No |
| Payment declined or failed | No |
| Cancelled before payment | No |
| Cancelled after payment and refunded | No (there is no completed purchase) |
| Refund succeeded on an order that wasn't cancelled | No |
| Refund requested but rejected, or refund still pending or failed | Yes |

There is no "delivered only" requirement. A paid order qualifies as soon as the
payment is confirmed.

A rejected attempt returns a GraphQL error with
`extensions.errorType = REVIEW_PURCHASE_REQUIRED` and writes nothing. The
storefront shows "Only customers who bought this product can review it."

### Existing reviews

Reviews written before this policy are **not deleted**. They stay listed and
counted, and their authors can still edit or delete them. Every review exposes
`ProductReview.verifiedPurchase`. This flag is computed on each read, is true
while the author currently has a qualifying purchase, and the storefront shows it
as a "Verified purchase" label.

A legacy star-only rating (no title or body) can still change its stars or be
deleted. It **cannot** gain review text without a qualifying purchase. Otherwise
"rate first, write later" would bypass the policy.

### Eligibility query for the UI

```graphql
query MyReview($productId: ID!) {
  myReview(productId: $productId) { id stars title body status createdAt updatedAt }
  myReviewEligibility(productId: $productId) { canReview hasReview reason }
}
```

`myReviewEligibility` requires `rating:write`, like `myReview`, and is part of the
existing `MyReview` operation, so the BFF allow-list didn't change.

- `canReview` is true when the caller already has a written review, which they
  can always edit, or has a qualifying purchase.
- `reason` is `PURCHASE_REQUIRED` when `canReview` is false.

The product page shows the form only when `canReview` is true and otherwise
explains the policy. If the eligibility read fails, the page still shows the
form, because the server enforces the policy anyway.

## Helpful sort

`productReviews(sort: HELPFUL)` orders visible written reviews by:

1. helpful vote count, descending.
2. `created_at`, descending.
3. review `id`, descending, so reviews created at the same time still have a
   fixed order.

Pagination is zero-based and `size` must be 1-20 (`BAD_USER_INPUT` otherwise).
The storefront requests 5 reviews per page. You can choose "Most helpful" from
the labelled "Sort reviews" control. `NEWEST`, `RATING_HIGH` and `RATING_LOW`
are still available, and `NEWEST` is the default.

## Indexes (V29)

`V29__review_purchase_eligibility.sql` adds `order_items(order_id,
product_variant_id)` and `product_variants(product_id)`. These support the
purchase lookup `orders(user_id) → order_items → product_variants`, and
`orders(user_id)` was already indexed. The primary key of `review_votes`
already covers the helpful counts.

## Tests

- `ProductReviewIntegrationTest` (Testcontainers):
  - eligible and ineligible order states, including refunds and rejected refund
    requests.
  - purchases of another product or by another customer don't count.
  - legacy reviews.
  - helpful ordering with the tie-break, and the page-size bound.
- `ProductReviewDataFetcherTest`: the error code, eligibility mapping and
  `verifiedPurchase` exposure.
- `GuestCartHiveTransportTest`: rejection and eligibility through Hive Router.
