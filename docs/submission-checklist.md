# Submission checklist — i-love-shopping (14 Oct 2026)

## How to read this checklist

**States:**

- **Verified:** implemented and verified by an automated test or a live demonstration.
- **Unverified:** implemented, but not exercised live (usually because it needs the
  owner's staff TOTP session).
- **Missing:** not implemented.
- **External:** blocked by an external credential or owner interaction.

**Paths and naming:**

- Backend tests live in `backend/src/test/java/...`.
- Frontend tests are `*.test.mjs` files under `frontend/src`.
- "Live" means the 14 Oct run on the dev stack (`make dev`, plus Next, plus
  `stripe listen`).
- In the tables, "Live:" introduces live evidence and "Tests:" introduces automated
  tests. A test name followed by "(`Foo`)" names the test class whose method it is.

## Foundation

| Requirement | State | Evidence |
|---|---|---|
| Email/password registration and login | Verified | **Tests:** `NativeRegistrationIntegrationTest`, `lib/auth/flows.test.mjs`. **Live:** headless registration → Mailpit code → sign-in (`docs/review-setup.md` "Verified behaviour"). |
| CAPTCHA on registration | Verified | **Tests:** `RegistrationControllerTest`, `lib/auth/verification.test.mjs`. |
| External OAuth (Google) login and linking | External | Implemented with the Kratos OIDC method and `lib/auth/social.test.mjs`. A live check needs the owner's Google client. |
| Validation and actionable errors | Verified | **Tests:** `CheckoutValidationTest`, `lib/auth/password.test.mjs`, `lib/admin/forms.test.mjs`. |
| Password recovery and reset | Verified | **Tests:** `lib/auth/flows.test.mjs` (recovery). Uses the Kratos recovery code delivered by Mailpit. |
| Optional customer 2FA (TOTP) | Verified | **Tests:** `/account/security` and `lib/auth/account.test.mjs`. **Live:** the owner confirmed TOTP sign-in. |
| Access token 15–60 min; refresh token 3–7 days; rotation; revocation | Verified | Hydra TTLs are 30 min (access) and 168 h (refresh), with single-use refresh rotation. Logout revokes tokens. See `docs/auth/browser-contract.md` and `lib/auth/browser-session.test.mjs`. |
| Tokens not in persistent browser storage | Verified | The BFF keeps tokens server-side in an encrypted session store. The browser holds only an HttpOnly session cookie (`docs/auth/browser-contract.md`). This is stricter than the "in memory" example. |
| Catalog, search and product data | Verified | **Tests:** `CatalogSearchServiceIntegrationTest`, `ProductServiceIntegrationTest`, `lib/catalog/model.test.mjs`. |
| JWT, injection and security tests | Verified | **Tests:** `JwtDecoderConfigurationTest`, `ForeignKeyViolationTest`, `*AuthorizationTest`, `GraphQlHttpTransportTest`. |
| Docker-only, one command | Verified | `make review` → `scripts/review-up.sh`. Re-checked after all merges on a disposable project: healthy services, Flyway at V25, encrypted seed data, TLS pages and the contact email (`docs/review-setup.md`, "Verified behaviour"). |
| README with overview, ERD, setup, usage, extras | Verified | `README.md`, `docs/database/FLYWAY_DB.md`. |

## Commerce

| Requirement | State | Evidence |
|---|---|---|
| Cart: thumbnail, name, price, add, remove, quantity, totals | Verified | **Tests:** `CartServiceTest`, `lib/cart/model.test.mjs`, `GuestCartLifecycleIntegrationTest`. **Live:** k6 cart scenario. |
| Guest cart (temporary) and customer cart (persistent), with merge | Verified | **Tests:** `GuestCartGraphQlIntegrationTest`, `CartServiceIntegrationTest` (merge). |
| Cart recommendations | Verified | **Tests:** `ProductRecommendationIntegrationTest`. Recommendations are deterministic, based on shared categories, in stock, and exclude items already in the cart. |
| Single-page checkout: guest and signed-in, prefill, shipping options, editable summary | Verified | **Tests:** `CheckoutIntegrationTest`, `CheckoutGraphQlIntegrationTest`, `lib/checkout/model.test.mjs`. **Live:** guest order 72; signed-in order 73 (14 Oct) prefilled from a saved address and was paid via signed webhook → Kafka → PAID, with PLACED and PAID emails in Mailpit. |
| Address validation | Verified | **Tests:** `CheckoutValidationTest`, `UserDataFetcherTest`. Normalisation (required fields, lengths, no control characters) and a supported-destination country allowlist (`CheckoutSupport.normalize`, `UserAddressService`); there is no carrier deliverability check. |
| Confirmation page and confirmation email | Verified | **Live:** order 72 confirmation page, and the PLACED and PAID emails in Mailpit. |
| Stripe sandbox with hosted fields; no card data stored | Verified | **Tests:** `StripeCheckoutIntegrationTest`. **Live:** PaymentIntent → signed webhook → Kafka → PAID (order 72). Card data is only in Stripe Elements. |
| Pending, success and failure states; queue; consumer; email; inventory | Verified | **Tests:** `PaymentResultListenerIntegrationTest`, `PaymentOutboxIntegrationTest`. **Live:** the PAID path for order 72. Cancelling PIs for orders 68–71 led to CANCELLED, restock (66 → 70) and the email. |
| Idempotency, deduplication, dead-letter queue, no overselling | Verified | **Tests:** `AggregateConcurrencyIntegrationTest`, `PaymentResultCorrelationIntegrationTest`, `PaymentOutboxPublisherIntegrationTest`. The Go payment-service has an outbox and a DLQ. Notifications are deduplicated with a unique key on (order, kind). |
| Browser return URL never marks an order paid | Verified | The confirmation page only reads order state. The state moves to PAID only through the webhook → Kafka path. |
| Order history with date/status filters and bounded paging | Verified | **Tests:** `OrderLifecycleIntegrationTest`, `lib/orders/model.test.mjs`. UI at `/account/orders`. **Live:** order 73 appears in history and in the detail view (items, totals, addresses, delivery estimate, payment state, status history). |
| Order detail with status timeline and tracking | Verified | **Tests:** `OrderLifecycleIntegrationTest` (status events, tracking). |
| Cancellation before processing, enforced by the server | Verified | **Tests:** `OrderCancellationGraphQlIntegrationTest`, `OrderStatusTransitionsTest`. |
| Refund: requested → staff approval → Stripe refund, no restock | Verified (tests) / Unverified (live) | **Tests:** `OrderLifecycleIntegrationTest`. **Live:** customer refund request on order 73 → REQUESTED state and a REFUND REQUESTED email. Staff approval (live) needs the owner's staff TOTP session; order 73 is left pending for it. |
| Ownership on customer orders; guest proof for guest orders | Verified | **Tests:** `OrderMutationAuthorizationTest`, `OrderDataFetcherTest`. Guest access uses the checkout identity grant, not the UUID alone. |
| TLS and encryption at rest | Verified | **TLS:** `docs/review-setup.md` (Caddy, local CA). **Encryption:** `docs/security/encryption-at-rest.md` and `EncryptionAtRestIntegrationTest`. **Live:** the backfill sealed 34 addresses, 55 snapshots and 104 notifications. |

## Experience

| Requirement | State | Evidence |
|---|---|---|
| Star ratings and written reviews; customer edit and delete | Verified | **Tests:** `ProductReviewIntegrationTest`, `ProductRatingIntegrationTest`, `components/reviews/product-reviews.test.mjs`. |
| Average rating and count on product pages and listings | Verified | **Tests:** `ProductReviewIntegrationTest` (hidden reviews are excluded from the aggregates). |
| Helpful votes: unique, not on your own review, rate limited | Verified | **Tests:** `ProductReviewIntegrationTest`, `RateLimitIntegrationTest`. |
| Review moderation (hide/restore, audited) | Verified (tests) / Unverified (live) | **Tests:** `ProductReviewIntegrationTest`. UI at `/admin/reviews`. |
| Admin: products, variants, media, categories | Verified (tests) / Unverified (live) | **Tests:** `CatalogMutationAuthorizationTest`, `ProductMediaControllerAuthorizationTest`, `CategoryServiceIntegrationTest`. |
| Admin: orders, status and tracking, cancellation, refunds | Verified (tests) / Unverified (live) | **Tests:** `AdminOperationsAuthorizationTest`, `OrderLifecycleIntegrationTest`. |
| Admin: shipping statuses and delivery options | Verified (tests) / Unverified (live) | **Tests:** `AdminOperationsAuthorizationTest`. UI at `/admin/shipping`, persisted in `shipping_options` (V21). |
| Admin: users and roles (last-admin protection) | Verified (tests) / Unverified (live) | **Tests:** `AdminOperationsAuthorizationTest`, `UserMutationAuthorizationTest`. |
| CSV/JSON bulk import (atomic, per-row errors) | Verified (tests) / Unverified (live) | **Tests:** `ProductImportServiceIntegrationTest`. Documented in `docs/api/bulk-import.md`, with samples in `docs/api/samples`. |
| Mandatory admin 2FA; least privilege; server enforcement | Verified | **Tests:** `lib/admin/policy.test.mjs`, `*AuthorizationTest`. The resource server requires AAL2 for staff roles. There is no bypass. |
| Audit log of admin operations | Verified (tests) / Unverified (live) | **Tests:** `AdminOperationsAuthorizationTest` (audit rows). Table `admin_audit_log` (V20). UI at `/admin/audit`. |
| Customer pages: home, listing (grid/list, facets, paging), PDP, cart, checkout, confirmation | Verified | **Live:** pages return 200. **Live:** k6 browse scenario. |
| Confirmation shows estimated delivery | Verified | The shipping option estimate is shown, labelled as configured data and not as a carrier commitment. |
| Account: profile, orders, addresses | Verified | `/account/profile` shows details and supports a verified email change. Orders and addresses are covered by `OrderLifecycleIntegrationTest` and `lib/graphql/address-operations.test.mjs`. **Live (14 Oct):** address save was denied because the storefront client never requested `user:write`. Fixed in `0a31a15`; the address now saves from `/account/addresses` and is stored as `enc:v1:` ciphertext. |
| Search: sorting (including rating), filters, result count, pagination, suggestions | Verified | **Tests:** `CatalogSearchServiceIntegrationTest`, `lib/graphql/catalog.test.mjs`. |
| Header quick-cart preview and search suggestions | Verified | **Live:** storefront header. |
| Contact form (validated, emailed), About page, 404 page | Verified (tests) | **Tests:** `ContactMessageIntegrationTest`. **Live:** `/about`, `/contact` and an unknown URL return 200, 200 and 404. |
| TLS, protection of sensitive data, token-bucket rate limiter | Verified | **Tests:** `RateLimitIntegrationTest`, `TokenBucketTest`, `lib/rate-limit/token-bucket.test.mjs`. **Live:** 429 responses in the single-IP k6 run. |
| Client and server validation with allowlists | Verified | Server-side validation tests in the commerce, admin and contact areas. |
| SEO: titles, descriptions, single H1 | Verified | **Tests:** `lib/seo.test.mjs`. |
| Accessibility basics | Unverified | Native controls, labels, `aria-pressed` and keyboard-reachable navigation are in place. No formal audit (contrast, 200% zoom) has been run. |
| Three load scenarios and capacity | See report | `docs/performance/report.md`. |

## Known gaps and limitations

- **No live admin-flow check.** No admin flow has been exercised live with a real AAL2
  staff session. That needs the owner's authenticator. Use of `ADMIN_ALLOW_AAL1` as
  evidence is deliberately avoided.
- **No Google login check.** The Google client credentials have not been verified on
  this machine.
- **Limited profile fields.** The profile has no name or phone fields because the user
  model has none. Names live on addresses.
- **No brand filter.** Search has no brand facet. Brand is shown as product data.
- **Missing role grants.** CATALOG_MANAGER has no `user:read`. The stock editing UI is
  ADMIN-only.
- **Pre-existing test failures.** `CheckoutHiveTransportTest` and
  `OrderCancellationHiveTransportTest` need the `rover` CLI, which is not installed
  (exit 127).
