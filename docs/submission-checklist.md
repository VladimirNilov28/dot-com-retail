# Submission checklist — i-love-shopping (updated 15 Oct 2026)

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
  `stripe listen`), or — where marked "review stack" — the 15 Oct run on a fresh
  disposable reviewer project (`docs/review-setup.md`, "Verified behaviour").
- "Owner-reported" means the owner exercised the workflow; it was not independently
  observed for this checklist.
- The reviewer Functional checklists were not available as a file in the repository or
  session; rows below follow the original specification plus every Functional item
  named explicitly in the 15 Oct task (purchase-gated reviews, helpful sorting, image
  sizes, About contents, payment decline scenarios, token rotation/revocation,
  encryption at rest, rate limiting). Functional rows come first; the Quality rows
  (accessibility, SEO, performance) are listed separately at the end.
- In the tables, "Live:" introduces live evidence and "Tests:" introduces automated
  tests. A test name followed by "(`Foo`)" names the test class whose method it is.

## Foundation

| Requirement | State | Evidence |
|---|---|---|
| Email/password registration and login | Verified | **Tests:** `NativeRegistrationIntegrationTest`, `lib/auth/flows.test.mjs`. **Live:** headless registration → Mailpit code → sign-in (`docs/review-setup.md` "Verified behaviour"). |
| CAPTCHA on registration | Verified | **Tests:** `RegistrationControllerTest`, `lib/auth/verification.test.mjs`. |
| External OAuth (Google) login and linking | Verified (owner-reported) | Kratos OIDC method; `lib/auth/social.test.mjs`. The owner reports Google login/linking working on the dev stack; a fresh reviewer stack needs its own Google client (`docs/review-setup.md`). |
| Validation and actionable errors | Verified | **Tests:** `CheckoutValidationTest`, `lib/auth/password.test.mjs`, `lib/admin/forms.test.mjs`. |
| Password recovery and reset | Verified | **Tests:** `lib/auth/flows.test.mjs` (recovery). Uses the Kratos recovery code delivered by Mailpit. |
| Optional customer 2FA (TOTP) | Verified | **Tests:** `lib/auth/account.test.mjs`. **Owner-reported:** TOTP sign-in. **Disposable (encrypted review DB):** registration, login and TOTP enrolment succeeded, and login still worked after restart and container re-creation (`docs/security/encryption-at-rest.md`). |
| Access token 15–60 min; refresh token 3–7 days; rotation | Verified | Hydra TTLs: 30 min access, 168 h refresh, single-use refresh rotation with reuse detection. **Live (owner dev stack and review stack, 15 Oct):** `scripts/verify-token-revocation.mjs` 19/19 PASS — refresh issues a new refresh token, the old one is rejected (`invalid_grant`), reuse revokes the newer generation. |
| Revocation of already-issued access tokens | Verified | Spring validates signature/issuer/audience/expiry/roles/AAL, then introspects at Hydra (`HydraTokenIntrospector`: `active` + matching `sub`, 2 s timeout, fails closed, 10 s bounded cache). Logout, subject revocation and role changes revoke grants. **Tests:** `HydraTokenIntrospectorTest`, `JwtDecoderConfigurationTest`. **Live:** after logout/subject revocation the previously accepted access token gets 401 directly from Spring. Delay ≤ cache TTL (10 s). Other-device BFF sessions revoked via Kratos are cut on their next request; grant revocation retries run in `auth-maintenance`. |
| Token storage ("access tokens in memory") | Verified (interpretation) | Tokens never reach browser JavaScript, localStorage or sessionStorage: the Next BFF keeps them server-side in an encrypted session store and the browser holds only an HttpOnly cookie (`docs/auth/browser-contract.md`, "Token storage"). This deliberately differs from the literal "in memory in the SPA" wording; the reviewer decides whether it satisfies the intent. |
| Catalog, search and product data | Verified | **Tests:** `CatalogSearchServiceIntegrationTest`, `ProductServiceIntegrationTest`, `lib/catalog/model.test.mjs`. |
| JWT, injection and security tests | Verified | **Tests:** `JwtDecoderConfigurationTest`, `ForeignKeyViolationTest`, `*AuthorizationTest`, `GraphQlHttpTransportTest`. |
| Docker-only, one command | Verified | `make review` = `bash scripts/review-up.sh` (no host Java/Node/Bun; needs Docker Compose ≥2.24 and `/dev/fuse`). **Review stack (15 Oct):** fresh disposable project healthy, data persisted across `review-down`/`review-up` (42 products/15 users/8 messages before and after). Found and fixed a first-start `EPERM` in encrypted Postgres (`ce536c1`). |
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
| Pending, success and failure states; queue; consumer; email; inventory | Verified | **Tests:** `PaymentResultListenerIntegrationTest`, `PaymentOutboxIntegrationTest`, `StripeCheckoutIntegrationTest` (declines). **Live:** the PAID path for order 72. Cancelling PIs for orders 68–71 led to CANCELLED, restock (66 → 70) and the email. |
| Definite decline vs unknown outcome; reviewer card scenarios | Verified (tests + Stripe test mode) | `payment.declined` with allow-listed reason (insufficient funds, expired card, incorrect CVC/number, …) → `OrderPaymentStatus.DECLINED` + `declineReason`, order stays PENDING with the reservation kept, one PAYMENT_FAILED email per attempt, same-order retry → PAID once. Timeouts/processing errors → UNRESOLVED ("being confirmed"), reconciled later. **Tests:** Go `decline_test.go`, `settle_integration_test.go`; Spring `StripeCheckoutIntegrationTest`; `lib/orders/model.test.mjs`. **Live:** Stripe test-mode decline mapping and same-PaymentIntent retry on disposable intents (`decline_live_test.go`). Scenario table: `docs/api/checkout.md`. Invalid card numbers are rejected by Stripe Elements before submission. |
| Idempotency, deduplication, dead-letter queue, no overselling | Verified | **Tests:** `AggregateConcurrencyIntegrationTest`, `PaymentResultCorrelationIntegrationTest`, `PaymentOutboxPublisherIntegrationTest`. The Go payment-service has an outbox and a DLQ. Notifications are deduplicated with a unique key on (order, kind). |
| Browser return URL never marks an order paid | Verified | The confirmation page only reads order state. The state moves to PAID only through the webhook → Kafka path. |
| Order history with date/status filters and bounded paging | Verified | **Tests:** `OrderLifecycleIntegrationTest`, `lib/orders/model.test.mjs`. UI at `/account/orders`. **Live:** order 73 appears in history and in the detail view (items, totals, addresses, delivery estimate, payment state, status history). |
| Order detail with status timeline and tracking | Verified | **Tests:** `OrderLifecycleIntegrationTest` (status events, tracking). |
| Cancellation before processing, enforced by the server | Verified | **Tests:** `OrderCancellationGraphQlIntegrationTest`, `OrderStatusTransitionsTest`. |
| Refund: requested → staff approval → Stripe refund, no restock | Verified (tests) / Unverified (live) | **Tests:** `OrderLifecycleIntegrationTest`. **Live:** customer refund request on order 73 → REQUESTED state and a REFUND REQUESTED email. Staff approval (live) needs the owner's staff TOTP session; order 73 is left pending for it. |
| Ownership on customer orders; guest proof for guest orders | Verified | **Tests:** `OrderMutationAuthorizationTest`, `OrderDataFetcherTest`. Guest access uses the checkout identity grant, not the UUID alone. |
| TLS and encryption at rest | Verified (review stack) / partial (owner dev stack) | **TLS:** Caddy with local CA (`docs/review-setup.md`). **Application-level (AES-GCM key ring):** addresses, order contact/shipping snapshots, notifications, contact messages, user email and date of birth, registration reservations; email lookups via keyed `email_index` blind index. **Tests:** `EncryptionAtRestIntegrationTest`, `UserPiiEncryptionIntegrationTest`, `FieldCipherTest`. **Live (owner dev stack, 15 Oct, after backup):** V27 backfill sealed 130 accounts; 0 of 110 users have a plaintext email or DOB. **Storage layer (review stack):** whole Postgres data dir + WAL on gocryptfs — covers Kratos traits/TOTP credentials/sessions, Hydra and BFF session data; raw volume had 0 files containing a seeded plaintext marker; missing/empty/wrong key fails closed. Kratos `secrets.cipher` only covers OIDC tokens (corrected). **Not applied to the owner dev stack:** its Postgres volume is still plaintext; cutover procedure in `docs/security/encryption-at-rest.md`. Payment-service stores only PaymentIntent ids, amounts, status and allow-listed decline codes (no card data). |

## Experience

| Requirement | State | Evidence |
|---|---|---|
| Star ratings and written reviews; customer edit and delete | Verified | **Tests:** `ProductReviewIntegrationTest`, `ProductRatingIntegrationTest`, `components/reviews/product-reviews.test.mjs`. |
| Reviews only for purchased products (server-enforced) | Verified (tests) | Creating a review or rating requires an owned order containing the product with payment SUCCEEDED, status PAID/PROCESSING/SHIPPING/COMPLETED and no succeeded refund; otherwise `REVIEW_PURCHASE_REQUIRED`, also on direct API calls. Existing reviews stay editable and are not deleted; `verifiedPurchase` flag, `myReviewEligibility` query. **Tests:** `ProductReviewIntegrationTest`, `ProductRatingIntegrationTest`, `ProductReviewDataFetcherTest`, `lib/graphql/review.test.mjs`. Policy: `docs/api/reviews.md`. |
| Sort reviews by helpfulness | Verified (tests) | `HELPFUL` sort: votes desc, then created desc, then id desc; max 20 per page. **Tests:** `ProductReviewIntegrationTest`. |
| Average rating and count on product pages and listings | Verified | **Tests:** `ProductReviewIntegrationTest` (hidden reviews are excluded from the aggregates). |
| Helpful votes: unique, not on your own review, rate limited | Verified | **Tests:** `ProductReviewIntegrationTest`, `RateLimitIntegrationTest`. |
| Review moderation (hide/restore, audited) | Verified (tests) / owner-reported (live) | **Tests:** `ProductReviewIntegrationTest`. UI at `/admin/reviews`. |
| Admin: products, variants, media, categories | Verified (tests) / owner-reported (live) | **Tests:** `CatalogMutationAuthorizationTest`, `ProductMediaControllerAuthorizationTest`, `CategoryServiceIntegrationTest`. The owner reports admin workflows working on the dev stack. |
| Product images in multiple real sizes | Verified | thumb ≤320, medium ≤800, full ≤2000 px, re-encoded (metadata stripped), generated on upload or lazily for older uploads, deleted with the image; cards/cart use thumb, PDP uses `srcset`. **Tests:** `ImageResizerTest`, `ProductImageServiceTest`, `lib/media/variants.test.mjs`, `product-card.test.mjs`. **Live (owner dev stack):** an existing 736×988 upload served `?size=thumb` 238×320, `medium` 596×800, `full` 736×988 (image/jpeg); `?size=bogus` → 400. Reviewer upload steps: `docs/api/product-media.md`. |
| Admin: orders, status and tracking, cancellation, refunds | Verified (tests) / owner-reported (live) | **Tests:** `AdminOperationsAuthorizationTest`, `OrderLifecycleIntegrationTest`. |
| Admin: shipping statuses and delivery options | Verified (tests) / owner-reported (live) | **Tests:** `AdminOperationsAuthorizationTest`. UI at `/admin/shipping`, persisted in `shipping_options` (V21). |
| Admin: users and roles (last-admin protection) | Verified (tests) / owner-reported (live) | **Tests:** `AdminOperationsAuthorizationTest`, `UserMutationAuthorizationTest`. |
| CSV/JSON bulk import (atomic, per-row errors) | Verified (tests) / owner-reported (live) | **Tests:** `ProductImportServiceIntegrationTest`. Documented in `docs/api/bulk-import.md`, with samples in `docs/api/samples`. |
| Mandatory admin 2FA; least privilege; server enforcement | Verified | **Tests:** `lib/admin/policy.test.mjs`, `*AuthorizationTest`. The resource server requires AAL2 for staff roles. There is no bypass. **Live (14 Oct):** a disposable staff identity enrolled TOTP on `/admin/security`, signed in with password + TOTP, and loaded `/admin/products` from the backend. Fix `0b36777` promotes the `aal` token claim. |
| Audit log of admin operations | Verified (tests) / owner-reported (live) | **Tests:** `AdminOperationsAuthorizationTest` (audit rows). Table `admin_audit_log` (V20). UI at `/admin/audit`. |
| Customer pages: home, listing (grid/list, facets, paging), PDP, cart, checkout, confirmation | Verified | **Live:** pages return 200. **Live:** k6 browse scenario. |
| Confirmation shows estimated delivery | Verified | The shipping option estimate is shown, labelled as configured data and not as a carrier commitment. |
| Account: profile, orders, addresses | Verified | `/account/profile` shows details and supports a verified email change. Orders and addresses are covered by `OrderLifecycleIntegrationTest` and `lib/graphql/address-operations.test.mjs`. **Live (14 Oct):** address save was denied because the storefront client never requested `user:write`. Fixed in `0a31a15`; the address now saves from `/account/addresses` and is stored as `enc:v1:` ciphertext. |
| Search: sorting (including rating), filters, result count, pagination, suggestions | Verified | **Tests:** `CatalogSearchServiceIntegrationTest`, `lib/graphql/catalog.test.mjs`. |
| Header quick-cart preview and search suggestions | Verified | **Live:** storefront header. |
| Contact form (validated, emailed), About page, 404 page | Verified | **Tests:** `ContactMessageIntegrationTest`. **Review stack:** contact messages accepted and emailed. About contains project information, mission, the actual team (one person, Vladimir Nilov) and real GitHub links only. Unknown URL → 404. |
| Token-bucket rate limiter (per subject / per client, trusted proxies) | Verified | **Tests:** `RateLimitIntegrationTest`, `RateLimitUntrustedPeerIntegrationTest`, `TokenBucketTest`, `lib/rate-limit/token-bucket.test.mjs`. **Review stack (15 Oct):** only the pinned Hive address is a trusted proxy; Caddy strips client `Forwarded`/`X-Real-IP` and rewrites `X-Forwarded-For`. Two clients had independent buckets; forged headers did not escape; 429 + `Retry-After: 120`; an authenticated subject stayed limited from another address; only Caddy publishes ports. |
| Client and server validation with allowlists | Verified | Server-side validation tests in the commerce, admin and contact areas. |
| SEO: titles, descriptions, single H1 | Verified | **Tests:** `lib/seo.test.mjs`. |

### Quality items (not pass-blocking Functional items)

| Requirement | State | Evidence |
|---|---|---|
| Accessibility and responsive layout | Verified (automated + spot check) | Review stack, 15 Oct: Playwright + axe-core 4.x (WCAG 2.0/2.1 A/AA rules including colour contrast) on `/`, catalog, search, PDP, cart, checkout, About, contact, account, admin, 404 at 320, 768, 1024, 1440 px and 200% zoom (1440×900 window at device scale 2 = 720 CSS px). Result: 0 axe violations, no horizontal overflow, the first tab stops all show a visible focus indicator. Signed-in account/admin pages were only checked at their sign-in gate. Not a formal certification. |
| SEO | Verified | See the SEO row above. |
| Three load scenarios and capacity | See report | `docs/performance/report.md` (dev topology, Next process is the bottleneck; p90 > 5 s at about 450 VUs). Not rerun: the 15 Oct changes add one bounded, cached introspection call per authenticated request, which the report's anonymous/browse scenarios do not exercise; treat authenticated-scenario numbers as pre-introspection. |
| Concept walkthrough | Provided | `docs/review-walkthrough.md`. |

## Known gaps and limitations

- **Admin mutations are owner-reported, not independently observed.** A disposable AAL2
  staff session was exercised live (enrolment, password + TOTP, admin product list);
  the owner reports the remaining admin workflows working. Live staff refund approval
  on a disposable paid order is still unobserved (owner order 73 was deliberately left
  untouched). `ADMIN_ALLOW_AAL1` was never used as evidence.
- **Google login** is owner-reported only; a reviewer needs their own Google client.
- **Owner dev stack storage encryption not applied.** The reviewer stack encrypts the
  whole Postgres data directory; the owner's existing `postgres_volume` is still
  plaintext at the storage layer (Spring PII is application-encrypted). Cutover requires a
  coordinated backup → restore into an encrypted volume
  (`docs/security/encryption-at-rest.md`, "Owner dev stack: cutover procedure").
- **Declined orders keep their reservation** until cancelled; there is no automatic
  reservation-expiry job (`docs/api/checkout.md`).
- **Limited profile fields.** The profile has no name or phone fields because the user
  model has none. Names live on addresses.
- **No brand filter.** Search has no brand facet. Brand is shown as product data.
- **Missing role grants.** CATALOG_MANAGER has no `user:read`. The stock editing UI is
  ADMIN-only.
- **Pre-existing test failures.** `CheckoutHiveTransportTest` and
  `OrderCancellationHiveTransportTest` need the `rover` CLI, which is not installed
  (exit 127).
