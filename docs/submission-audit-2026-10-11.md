# Three-project submission audit — 11 October 2026

**Decision:** none of the three projects is submission-ready. Foundation is
closest; Commerce has substantial backend support but no usable purchase
journey; Experience lacks most administration and several required customer,
security and performance features. The remaining mandatory scope is too large
for a credible one-person promise of all three submissions by 14 October.

## Basis and limits

- Audited local `dev` at `460d92b`, with a clean worktree before this document.
  The live GitHub `dev` SHA and local `origin/dev` both matched
  `97f4fa7d96959bee3461a14e77f1458ccc4b4bae`. Local `dev` was **19 commits
  ahead, zero behind**. This document adds one more local commit. No fetch,
  push, branch creation, board change or issue update was performed.
- **Original specifications unavailable:** no original Foundation, Commerce or
  Experience text/file was located in repository documentation, available
  local conversation history or the relevant issues. #94 references the
  owner's 5 October originals but is a derived roadmap, not their text.
  The request for original files/URLs was unanswered because the owner was
  unavailable; this was **not a tool-policy denial**.
- Consequently this is a **provisional checklist-based audit**, not a claim to
  have checked every original mandatory clause. The owner's checklist in the
  11 October audit request supplies the requirement rows below. Issue bodies
  supply ticket scope, not proof of the originals. Obtain the three originals
  before certifying completeness, provider count, measurement units, exact
  test obligations, performance thresholds or optional/mandatory distinctions.
- Compared actual routes, SDL, domain/services, migrations, auth/infra
  configuration, operation registration and relevant test source with recent
  verification reports. Reports were retrieved once, using the latest two
  comments per selected implementation issue. Older reports claiming
  “unpushed” or “missing” are interpreted against current Git/source, not
  repeated as current fact.
- Reused the repaired auth evidence and the owner's confirmation that
  same-browser switching works. No auth reinvestigation, data/secret changes,
  load tests, full test suites or screenshots were undertaken for this audit.

**Status meaning:** Complete = implementation plus relevant verification;
Partial = only part of the requirement is delivered; Missing = absent;
Unverified = appears implemented but adequate acceptance evidence is missing;
Blocked = a named dependency prevents determination/delivery. “Complete” in a
backend-only row does **not** complete the corresponding customer/admin workflow.

**Path shorthand:** `B/` = `backend/src/main/`; `BT/` =
`backend/src/test/java/ee/bytecore/backend/`; `F/` = `frontend/src/`;
`O/` = `infrastructure/oauth/`; `G/` = `payment-service/internal/`.
Issue numbers refer to `VladimirNilov28/dot-com-retail`.

## Requirement matrix

| Project | Requirement | Status | Evidence | Remaining work | Existing issue |
|---|---|---|---|---|---|
| All | Coverage of the three original mandatory specifications | Blocked | Original texts unavailable; #94 is derived planning | Supply originals; reconcile this matrix, including exact units, provider count, tests and performance criteria | #94, #72, #77, #93 |
| Foundation | Email/password registration and login, verified identity, same-browser switching | Complete | `F/lib/auth/{flows,oauth,session}.ts`, `F/components/auth-form.tsx`; E1–E3; owner confirms switching | Final submission regression only; retain current protections and generic login errors | #66, #67, #40 |
| Foundation | External-provider OAuth login/onboarding | Missing | `O/kratos/kratos.yml` has no OIDC provider; BFF form projection supports password/TOTP/lookup-secret, not social methods | Configure the provider(s) required by originals; native provider callback, safe canonical onboarding/linking, cancellation and real new/returning-user verification | #70, #40 |
| Foundation | Genuine registration CAPTCHA and browser/flow binding | Complete | `O/service/src/services/registration.py`, auth flows; E1, E2; genuine ALTCHA and invalid/foreign/reused-proof checks reported | Preserve retry/binding behavior; no fake challenge or bypass | #27, #67 |
| Foundation | Password guidance and authoritative policy validation | Complete | `F/lib/auth/password.ts`, auth form; Kratos minimum/similarity checks and private pre-persist hook; E2, E3 | Final negative checks only; breach check stays enabled in base config, disabled only by explicit local override | #67, #66 |
| Foundation | Password recovery/reset usable by customers | Partial | Kratos code recovery and password-change session-revocation hook exist; `FormKind` excludes recovery/settings and no recovery route exists | Add request/redeem/reset UI and BFF transport; genuine latest email, expiry/reuse, safe enumeration behavior and post-reset session/grant checks | #68, #40, #19 |
| Foundation | Optional customer 2FA | Partial | Kratos TOTP/backup codes, highest-available assurance, BFF login challenges; E1; `O/service/tests/real/totp_flow_test.py` | Customer enrollment, QR/manual setup, verify/disable and backup-code management UI; complete real customer journey | #69, #26, #40 |
| Foundation | Access/refresh lifetimes and refresh rotation | Complete | `O/hydra/hydra.yml`: 30m access, 168h refresh; `F/lib/auth/store.ts`: 24h absolute/1h idle BFF cap; E1–E3 | Confirm original exact lifetime bounds; final rotation/replay regression, not a fresh implementation | #66, #19 |
| Foundation | Logout and revocation | Complete | Durable revoke/retry path; E1–E3; paired provider session and refresh grant rejected after logout | Preserve documented residual issued-JWT lifetime; do not claim instantaneous global JWT invalidation | #66, #40, #19 |
| Foundation | Relational DB and integrity | Complete | PostgreSQL/Flyway, `B/resources/db/migration/V1`, V6, V11–V16; FK/check/unique constraints; migration/concurrency tests and E4–E6 | Apply new migrations append-only as features land; do not reset owner volumes | #33, #34 |
| Foundation | ERD and database documentation | Partial | Mermaid ERD in `docs/database/FLYWAY_DB.md`; core catalog/cart/order/inventory/rating relations documented | Reconcile final model and service DB diagrams/receipts/reservations; current migration table also omits V8/V9 despite source files existing | #34, #72 |
| Foundation | Product stock usable in storefront | Partial | V6 stock constraints, `InventoryService`; product page literally says “Availability unknown”; anonymous inventory traversal intentionally denied | Add a narrow public stock/availability projection and truthful card/PDP UI; preserve warehouse privacy and server checkout revalidation | #95, #38, #65 |
| Foundation | Brand model and customer presentation | Missing | `Product.java` and product SDL have no brand field/entity/relationship | Brand persistence/management/read contract, validation and PDP/filter integration where required | #97, #38, #65 |
| Foundation | Real product images | Missing | No image model/read API; PDP/cards render `ImageUnavailable` | Ordered images, safe storage/URLs and alt text; gallery/card/cart usage, missing-media state; no placeholder-as-completion | #96, #65, #81 |
| Foundation | Weight/dimensions in required units | Partial | `ProductVariant.weightGrams` and selected-variant specification rendering; no dimension fields/unit conversion contract | Confirm original units; add validated canonical dimensions and required display/conversions, preserving exact values | #97, #65 |
| Foundation | Category browsing, search, facets and suggestions | Partial | `product.search.graphqls`, `CatalogSearchRepository`; category/price/attribute facets and suggestions; E7, E8; new expandable sidebar verified locally | Supported subset works; resolve required brand/rating facets from originals/#38 and remaining facet semantics/nonblank/unbounded-query gaps. Keep current category dropdowns | #63, #64, #38 |
| Foundation | Price/relevance and genuine rating ordering | Complete | `RATING_DESC` uses persisted AVG, unrated-last/ID ties; `catalog-sort.tsx` maps real enum; E6 verifies actual rated ordering through Hive; E7 verifies sorter navigation | Final combined rated storefront check; do not confuse old unrated tie-break evidence with E6's genuine rated API proof | #64, #78 |
| Foundation | Required automated/security tests | Partial | Auth replay/CSRF/nonce/PKCE/ownership and real-provider negatives, authorization/Hive/migration tests; E1–E8 | Original test list missing; retain existing tests and add recovery/social/enrollment/full-model negatives, then map exact mandatory tests at gate | #33, #17, #72 |
| Foundation | Docker-only startup and complete README | Partial | Compose builds Spring/Go/Ory/Hive; no frontend Dockerfile/service; README requires separate `make front-dev` and host Bun | One-command reviewer frontend+stack+safe provisioning, retained-volume restart and useful readiness; correct stale README “payment seam not yet wired” statements | #59, #34, #72 |
| Commerce | Guest and persistent user carts/quantities/totals | Partial | `CartService` CRUD, guest hash/grant persistence, merge receipts, exact BigDecimal totals; E4; `/cart` is `UnavailablePage` | Real add/update/remove/cart page, login merge/conflicts and cross-session persistence; register typed mutations and narrowly extend BFF client scopes | #73, #54, #58, #40 |
| Commerce | Cart thumbnails and recommendations | Missing | Cart UI absent; image model absent; no cart-based recommendation operation/rule | Real images and a bounded documented related-category/cart-item recommendation rule; not fabricated personalization | #73, #96 |
| Commerce | Single-page guest/customer checkout and prefill | Partial | `checkout.graphqls`, `OrderService.previewCheckout/placeCheckout`, saved-selection ownership; E4; `/checkout` 404 | One page collecting guest/customer contact/addresses, authorized prefill and stable request/quote acceptance; no synthetic guest accounts | #74, #71, #55 |
| Commerce | Address validation, shipping options, editable summary | Partial | `CheckoutSupport`, configurable STANDARD/EXPRESS/PICKUP, immutable shipping snapshots; E4 | Inline browser/BFF/API errors, cart summary editing and re-preview; genuine configured options; persistent admin delivery management belongs to #83 | #74, #55, #83 |
| Commerce | Stripe or PayPal sandbox with provider-controlled payment inputs | Missing | `CheckoutPaymentSelection` only SIMULATED; `G/provider/durable.go` is a simulator, not a gateway | Hosted/tokenized provider UI, intent/reference binding, authenticated webhook/results, real sandbox success/decline/cancel/timeout; no PAN/CVV in app storage/logs | #25, #74 |
| Commerce | No server storage of card details | Complete | Current checkout/payment seam carries IDs/amount/currency and safe method descriptions; no PAN/CVV model; E4, E5 | Preserve this boundary when adding actual gateway; provider-controlled collection still missing | #25, #89 |
| Commerce | Pending/success/failure order/payment transitions | Partial | Initial PENDING, correlated PAID/FAILED/UNRESOLVED handling; `OrderService.applyPaymentOutcome`; E4, E5; #42's initial-PAID defect was not reproduced | Expose real states in checkout/confirmation UI and verify with actual sandbox; no manual PAID or timeout-as-decline shortcuts | #25, #75, #42 |
| Commerce | Queue publishing/consumption, retries, DLQ, duplicate protection | Complete | Spring transactional outbox; `G/kafka/{consumer,refund_consumer,producer}.go`; request/result receipts; E5 real Kafka, restart/replay/race coverage | Revalidate gateway-side durable idempotency/webhook duplication after adapter change; queue guarantees alone cannot prevent remote double charges | #24, #25, #56 |
| Commerce | Inventory validation and overselling prevention | Complete | `InventoryService.allocateAndDecrement` locks/reloads stock; `CheckoutIntegrationTest.limitedStockCannotBeOversoldAcrossOwners`; E4, E5 | Retain concurrency/rollback checks in real-payment integration. Current policy requires one warehouse to satisfy a line; confirm originals don't require split fulfillment | #15, #55, #33 |
| Commerce | Failed-payment consistency and cancellation/restock | Complete | Terminal correlation, release-once marker/original allocations, late-success refund path; E5 | Real gateway evidence still needed; preserve unknown outcomes and no double restoration | #56, #25 |
| Commerce | Confirmation page and customer/guest access | Partial | Independent guest-order grant, immutable `CheckoutOrder` projection and lost-response receipt; E4, E5; no confirmation route/UI | Authorized confirmation, bounded polling/reload and exact totals/reference/estimate; safe lost-grant state | #75, #55 |
| Commerce | Order confirmation/payment/cancellation/refund emails | Missing | Mailpit/Kratos deliver auth mail; no business notification producer/sender/outbox in Spring/Go | Durable transaction-bound notifications, retry/deduplication and genuine captured emails. Auth email success is not order email delivery | #57, #75 |
| Commerce | Order filters/details/tracking | Partial | Owned `myOrders` and snapshots exist, but collection is unbounded; no date/status query inputs, persisted tracking fields or UI | Bounded owner/staff queries, date/status filters, detail and real tracking reference/link or required management; no mocked carrier completion | #43, #76, #83 |
| Commerce | Customer cancellation and refund workflow | Partial | Owner/guest/staff cancellation APIs; simulated full-refund ledger/statuses; E5; no customer controls | Confirmation/cancellation UI, real sandbox full refund and state display; confirm original refund/shipped-return scope | #76, #56, #25 |
| Commerce | Required tests, encryption verification and one-step containers | Partial | Strong API, stock, queue, ownership and simulated financial negatives; containers omit frontend; business PII lacks application encryption | Real provider negative/duplicate tests, browser checkout, required manual encryption evidence and full reviewer start; preserve all specified tests | #33, #77, #89, #59 |
| Experience | Review text/list/create/edit/delete | Missing | V15/`ProductRatingService` store only caller-owned integer stars; no full review domain or customer form/list | Bounded review model/API/UI, ownership and safe text rendering, edit/delete policy | #78, #79 |
| Experience | Average ratings | Complete | Product formulas compute AVG/COUNT from persisted ratings, shown on cards/PDP when present; E6 | Integrate moderated review visibility without stale counters when full review model lands | #78, #79 |
| Experience | Helpful votes and moderation | Missing | No vote persistence/deduplication/moderation state or UI in current domain/SDL/routes | Unique/concurrent vote protection, bounded moderation queue/actions, public visibility/aggregate consistency | #78, #79, #85 |
| Experience | Admin product/category/inventory management | Partial | Guarded GraphQL mutations, category cycle/stock constraints and tests; no `/admin` route | Staff CRUD/archive, required brand/media/measurements/stock, safe references/destructive actions and form errors | #80, #81, #38 |
| Experience | Admin order/refund/delivery management | Partial | Staff fulfillment/cancellation APIs; shipping options are config fixtures, no delivery/tracking administration UI/model | Bounded order UI, persistent delivery options/tracking, permitted transitions and real refund/reconciliation visibility | #83, #56, #25 |
| Experience | Admin users/roles/permissions | Partial | Role enum, guarded user mutation and `UserService.updateRole`; no management UI; issued-token role/grant lifetime remains bounded, not instant | Bounded user management, least privilege, role/grant lifecycle, required last-admin/self-lockout protections | #84, #40, #43 |
| Experience | CSV/JSON product import | Missing | No importer/upload endpoint/route found in current components/controllers/services | Bounded authorized parser, shared validation, row errors, explicit atomic/partial policy, duplicate handling and audit | #82 |
| Experience | Privileged audit logging | Missing | Ordinary operational logs exist; no actor/action/target/outcome audit record pipeline | Durable auditable admin changes with safe metadata and no credentials; not request logs alone | #80, #81–#85 |
| Experience | Mandatory admin 2FA | Missing | Kratos highest-available enforces enrolled factors, not enrollment; bootstrap/admin can remain unenrolled; BFF/bridge has no all-admin AAL2 gate | Trusted enrollment/bootstrap plus provider/API enforcement and direct-bypass negatives; no client-only flag or role-only gate | #80, #69, #40 |
| Experience | Home, listing, PDP and search pages | Partial | Real SSR catalog/home/search/variants; E7–E9 and route source | Product images/brand/stock/units and review interaction still absent; homepage selections are category-derived, not editorial “featured” data if originals require that | #86, #63–#65, #96, #97 |
| Experience | Cart, checkout and confirmation pages | Missing | `/cart` placeholder; `/checkout` 404; no confirmation implementation | Complete actual purchase workflow, not backend examples | #73–#75 |
| Experience | Account/profile/addresses/orders | Partial | `/account` shows identity/email/logout only; user/address APIs exist; no customer editing/orders routes | Owned profile/address CRUD, coordinated email changes, customer order history and links to factor management | #71, #76, #40 |
| Experience | Contact and About | Missing | `/contact` and `/about` return 404; no routes/form destination | Owner-supplied company/contact content, working support submission, validation and abuse protection; don't invent team/contact details | #87, #90 |
| Experience | Friendly actual 404 | Partial | Custom missing-category/product pages exist; no root `not-found.tsx` | Friendly global unknown-route page with useful navigation; preserve actual HTTP 404 and noindex | #87 |
| Experience | Header quick-cart preview | Missing | Header links to unavailable `/cart`; no preview data/component | Shared real cart data and accessible preview with quantities/totals/thumbnail and mobile access | #73, #61 |
| Experience | Header quick search/suggestions | Complete | `SearchControl`, shared `useProductSuggestions`, bounded typed queries; E7–E9; recent first-click/one-icon fixes tested | Preserve current routing, keyboard/close/Enter behavior; final cross-browser mobile check remains useful | #61, #64 |
| Experience | TLS for review flow | Missing | Canonical provider/storefront config is HTTP; no frontend/review TLS ingress/certificate setup | Self-signed reviewer TLS, issuer/callback/origin/cookie alignment and required sensitive internal paths | #88, #59, #40 |
| Experience | Required encryption and key lifecycle | Partial | Auth state uses row/purpose-bound A256GCM in `F/lib/auth/store.ts`; key-ring/secret handling and hashing covered in E1 | Business user/address/checkout PII stored as ordinary columns/JSON; implement required at-rest scope and backup/key recovery, transit via TLS; don't equate password hashing with PII encryption | #89, #88 |
| Experience | Token-bucket rate limiting | Missing | No token-bucket implementation found in application/bridge/payment source; CAPTCHA and bounded queries are not token buckets | Trusted principal/IP keys, refill/burst/429 behavior and shared deployment consistency; test aliases/direct ingress/forwarded-header spoofing | #90 |
| Experience | Client/server syntactic, semantic and allowlist validation | Partial | Typed variable/operation allowlists, auth BFF and Kratos policy, quote/address/ownership checks; E1, E4, E7 | New screens/privileged actions; nonblank catalog fields and profile-update validation parity; no browser-only acceptance or relaxed authorization | #38, #40, #58, #90, owning UI tickets |
| Experience | SEO | Partial | SSR public pages, dynamic product/category metadata, private auth noindex; generic root description still says features “coming soon” | Original exact targets; canonical/search/filter/private indexing policy, unique accurate metadata and real image alt text; structured data only where appropriate | #91, #93 |
| Experience | Accessibility/responsive customer/admin workflows | Partial | Existing keyboard/focus/zoom/no-JS/contrast evidence E1, E7–E9; latest touch checks E10 | Missing screens cannot pass; no full screen-reader/Safari/physical-device pass. Owner's other mobile-button failures remain unidentified | #91, #61, owning UI tickets |
| Experience | At least three load scenarios and specified report | Missing | No load suite/performance report located; smoke timing is not a load benchmark | Implement three real scenarios, isolated data, measured targets/bottlenecks and report; confirm original exact performance contract | #92 |
| Experience | Docker setup, review/source/usage documentation | Partial | Monorepo sources, domain/API/auth/DB runbooks and Docker backend exist; no Docker frontend or complete submission bundle | Final overview, complete ERD/setup/usage/performance/evidence and Docker-only review path; remove stale claims without repetitive edits | #59, #34, #93 |

## Reusable verification evidence

These are reported executed checks tied to inspected implementation, not tests
rerun during this audit. Historical “local-only” labels do not override the
current remote SHA. Current originals/source gaps still limit submission claims.

- **E1 — auth package:** `docs/auth/implementation-report.md`,
  `docs/auth/browser-contract.md`; [#40 package report](https://github.com/VladimirNilov28/dot-com-retail/issues/40#issuecomment-6075297651).
  Real Ory/Hive registration, factors, callback/replay/CSRF/nonce/PKCE,
  serialized refresh, expiry/logout/revocation and ownership negatives.
- **E2 — genuine verification and switching:** [#67 verification](https://github.com/VladimirNilov28/dot-com-retail/issues/67#issuecomment-6102401540)
  and [same-browser switching](https://github.com/VladimirNilov28/dot-com-retail/issues/67#issuecomment-6102502755).
  Genuine code/link/resend, originating-browser binding, A/B/admin switching,
  reload and revocation; latest switching commit `2278af0`.
- **E3 — local restoration/password boundaries:** [#40 restoration](https://github.com/VladimirNilov28/dot-com-retail/issues/40#issuecomment-6100656114)
  and [#66 compact-password/lifecycle report](https://github.com/VladimirNilov28/dot-com-retail/issues/66#issuecomment-6102141617).
  Real login/invalid credentials/refresh/logout/returns/HMR; same-password
  distinct-user and development/base breach-policy checks. Environment fixes
  are not feature delivery.
- **E4 — checkout/cart backend:** current #55 body, `docs/api/checkout.md`,
  `docs/api/guest-cart.md`, `BT/services/{CheckoutIntegrationTest,CartServiceIntegrationTest}.java`,
  `BT/graphql/GuestCartLifecycleIntegrationTest.java`.
  Actual database/Hive transactions and guest/owner grants; not browser checkout
  or an actual Stripe/PayPal charge.
- **E5 — cancellation/payment/queue backend:** current #56 body,
  `docs/api/order-cancellation.md`, `payment-service/README.md`,
  `G/kafka/{consumer_retry_integration_test,provider_idempotency_integration_test,refund_integration_test}.go`,
  `BT/integration/payment/PaymentResultCorrelationIntegrationTest.java`.
  Real Kafka/Hive, release-once/late-charge/refund/restart/duplicate evidence
  uses the durable **simulator**, not a financial provider.
- **E6 — genuine ratings:** [#78 real persisted rating report](https://github.com/VladimirNilov28/dot-com-retail/issues/78#issuecomment-6020132199),
  `BT/services/ProductRatingIntegrationTest.java`,
  `infrastructure/hive/tests/real/product_ratings_test.py`.
  Actual issued JWTs/owner updates/concurrency/mean ordering/null/ties/pages;
  no review text/helpful/moderation completion.
- **E7 — catalog/search:** [#64 completed verification](https://github.com/VladimirNilov28/dot-com-retail/issues/64#issuecomment-6063653931),
  `frontend/scripts/{catalog-smoke,storefront-smoke}.mjs`.
  Exact isolated real fixture, supported facets/sorts/suggestions/history and
  error/retry; old unrated sorting checks alone were insufficient.
- **E8 — public-read boundary:** [#95 live Hive report](https://github.com/VladimirNilov28/dot-com-retail/issues/95#issuecomment-6018131059)
  and [negative-test strengthening](https://github.com/VladimirNilov28/dot-com-retail/issues/95#issuecomment-6018399761);
  `BT/graphql/GuestCartHiveTransportTest.java`.
  Anonymous success, protected traversal/mixed-root denial and cache/privacy.
- **E9 — product/home UI:** [#65 product verification](https://github.com/VladimirNilov28/dot-com-retail/issues/65#issuecomment-6067896501)
  and [#86 homepage refinement](https://github.com/VladimirNilov28/dot-com-retail/issues/86#issuecomment-6065158806);
  `frontend/scripts/{product-smoke,home-smoke}.mjs`.
  Real selected variants/SSR/navigation/cache and accessible supported states;
  placeholders and unknown availability expressly remain.
- **E10 — latest local UI repairs:** `b928ff6` (11 storefront scenarios),
  `df2ef8b` (search submit), `8a19b89` (EUR integer spinner),
  `4780da7` (responsive sorter), `b85f2b2` (external search button),
  `5bd3992` (production desktop/mobile expandable categories),
  `460d92b` (32 model tests plus true touch-emulated min/max/blank Apply,
  Search, drawer and view checks). Session verification is recorded in the
  owner's conversation; this audit did not repeat it. Physical-phone/browser
  identity was unavailable for the remaining reported mobile failures.

## Delivery batches

Effort is **active developer-hours**, including targeted checks and ordinary
integration/debugging, not elapsed deadlines. Ranges are planning estimates,
not promises. Shared test/configuration plumbing belongs inside its owning
batch, not in extra process tickets. Exact originals may expand these ranges.

1. **Finish customer authentication entry points — #68, #69, #70.**
   Deliver recovery/reset, optional factor management and real external login.
   Dependencies: existing #66/#67; original provider requirements and a usable
   provider application/callback/credentials. Minimum complete scope: reuse
   Kratos flows and the durable BFF/canonical identity contract; no replacement
   auth system or email-based account adoption. Check genuine recovery/reset
   and invalid/used/expired proofs, enrollment/AAL2/backup-code lifecycle,
   provider new/returning/cancel/error flows and cross-account denial.
   **14–24h**; main uncertainty is provider setup and safe OIDC onboarding.
   **This is the single recommended next three-ticket batch.**

2. **Complete required catalog data/PDP — #96, #97, #65.**
   Deliver real images/gallery, brand and measurements plus truthful storefront
   availability. Dependencies: original units; retain current public-read
   boundary. Resolve the existing #38/#95 public-stock gap inside the
   coordinated API/PDP work, not by exposing warehouse rows or creating a
   duplicate ticket. Minimum: append-only models, bounded safe reads and
   existing cards/PDP updated together. Check units/invalid dimensions,
   image ownership/URL handling, inactive/out-of-stock variants, actual
   inventory change and private traversal denial. **16–28h**; media storage
   choice and precise required conversion/filter contract are uncertainties.

3. **Make the reviewer path safe and containerized — #59, #88, #89.**
   Deliver Docker frontend+stack provisioning, TLS and required sensitive-data
   encryption/key recovery. Dependencies: canonical auth origins; originals'
   transit/at-rest coverage; gateway secrets can join when batch 5 lands.
   Minimum: reuse Compose, explicit reviewer config/bootstrap/migrations and
   retained-volume restart; compatible TLS ingress; real encryption coverage,
   not cookie flags or password hashing as substitutes. Check isolated clean
   start/restart, HTTPS login/guest traffic, key failure/recovery and plaintext
   persistence/log negatives. **18–30h**; existing-volume encryption migration,
   callbacks and offline review setup dominate risk. The Docker-only subset
   is an early Foundation gate; full TLS/encryption must not be falsely
   declared complete before all affected flows exist.

4. **Customer identity and usable carts — #71, #73.**
   Deliver profile/address management, real guest/user cart, safe merge,
   header preview and bounded cart-related recommendations. Dependencies:
   media/availability from batch 2 and narrow BFF scope/operation extensions
   coordinated with #40/#58. Minimum: current APIs and server money/ownership;
   no shadow cart or fabricated recommendations. Check guest reload, login
   merge/retry/conflicts, cross-user/address denial, totals/quantities,
   thumbnail/preview consistency and coordinated email edits. **14–24h**;
   protected guest-cookie rendering and grant/profile synchronization.

5. **Actual end-to-end payment and purchase — #25, #74, #75.**
   Deliver single-page guest/customer checkout through Stripe or PayPal
   sandbox to an authorized confirmation page. Dependencies: carts,
   scopes/operations, reviewer ingress and provider application/keys.
   Minimum: hosted/tokenized collection; intent/reference bound to the accepted
   server quote and stable payment UUID; verified callback/webhook integrated
   into existing outboxes; explicit pending/success/decline/unknown states.
   Extend current SIMULATED-only contracts deliberately—an adapter that accepts
   only amount/order IDs cannot collect a real provider-controlled payment.
   Check genuine sandbox charge/decline/cancel/timeout, duplicate/lost-response
   and webhook replay, oversell/quote races and no PAN/CVV persistence.
   **28–48h**; provider lifecycle and at-most-once financial effects are the
   largest Commerce risk. Confirmation email is gated by batch 6.

6. **Order communication and customer history — #57, #76, #43.**
   Deliver durable business emails, bounded date/status-filtered owner history,
   detail/tracking and customer cancellation/refund presentation.
   Dependencies: batch 5; reuse #55/#56 grants/snapshots/finality. Minimum:
   business notification outbox+SMTP retries, scoped order queries and actual
   tracking storage/reference support. Check genuine Mailpit messages,
   duplicates/SMTP outage, foreign-order denial, shipped cancellation denial,
   full sandbox refund/late success and immutable amounts.
   **16–26h**; tracking/fulfillment policy, refund scope and SMTP ambiguity.

7. **Secure staff foundation and core management — #80, #81, #84.**
   Deliver mandatory admin 2FA, protected staff UI, durable action audit and
   product/category/inventory/user-role management. Dependencies: factor
   enrollment, full catalog and an explicit #40 role/grant lifecycle;
   customer BFF currently requests only `user:read`, so staff capabilities
   require deliberate client/scope boundaries. Minimum: backend-enforced
   AAL2/least privilege and existing service mutations behind actual forms.
   Check unenrolled admin/direct API denial, wrong role/scope, current-token
   role changes, cycle/stock validation and safe deletion/reference behavior.
   **22–36h**; trust/bootstrap and retained JWT/grant behavior.

8. **Remaining administration — #82, #83.**
   Deliver bounded CSV/JSON import and delivery/tracking/order/refund management.
   Dependencies: batches 5–7. Minimum: shared validation, explicit duplicate
   and import transaction policy; persistent shipping management with immutable
   order history; guarded staff transitions/refund visibility. Check oversized/
   malformed/duplicate imports, rollback/partial results, MFA/audit,
   concurrent shipping/cancellation and no new refund identity after terminal
   failure. **12–22h**; import semantics and original operational/refund scope.

9. **Complete reviews — #78, #79, #85.**
   Deliver full review CRUD/listing, helpful voting and moderated public
   aggregates plus customer/admin interfaces. Dependencies: staff/MFA/audit,
   owned customer mutations and existing rating source. Minimum: extend the
   persisted star model, not an unrelated review system; real unique votes
   and bounded safe-text lists. Check ownership, invalid/XSS text handling,
   concurrent duplicate votes, moderation visibility and aggregate/sort
   movement. **16–28h**; moderation policy and rating migration.

10. **Required public content and final interaction/security coverage —
    #87, #90, #91.**
    Deliver Contact/About/global friendly 404, genuine token buckets and final
    SEO/accessibility coverage. Dependencies: owner-provided content, real
    submission destination and all required pages. Minimum: working contact
    submission, trusted limiter keys/refill/burst, accurate metadata/indexing,
    keyboard/focus/zoom/alt text; reuse current harness.
    Check server/client validation, 429/refill/key isolation/direct bypass,
    real unknown-route 404, manual keyboard/assistive-tech and actual mobile
    target browser. **16–26h**; original exact SEO/accessibility criteria,
    distributed limiting and unidentified physical-device failures.

11. **Measured release gates — #92, #77, #93**, with #72/#33/#34 reused
    as the Foundation test/document gates rather than parallel paperwork.
    Deliver three real load scenarios, the required measured report and one
    broader final verification/submission pass. Dependencies: completed
    required workflows and the originals. #92 records provisional targets
    of p90 ≤2s, ≥50 concurrent users, ≥10 transactions/s, ≥98% success,
    <5% errors, saturation above 5s and CPU/memory >90% analysis.
    **Those numbers are issue-derived and must be confirmed against the
    originals; none has been measured here.**
    Check normal/peak/saturation metrics, report reproducibility, exact
    mandatory tests, Docker-only review/restart, final demonstrations and
    explicit per-project gaps. **12–20h before measured bottleneck fixes**;
    performance tuning cannot be priced reliably before measurement.

**Estimated listed scope: 184–312 active hours**, excluding unexpected
performance fixes, provider approval delays and additional original clauses.
Do not add these estimates to historical completed work. A single developer
with roughly 30–40 focused hours across 11–14 October cannot complete this
backlog; reserve a substantial part of that time for verification/submission.
Extra engineering capacity, an extension or explicitly approved assignment
scope changes are necessary for a credible all-three delivery plan. Missing
mandatory features cannot simply be renamed optional.

## Realistic 11–14 October sequence

| Date | Work window and decision |
|---|---|
| 11 Oct | Recover originals and provider/reviewer prerequisites first; start batch 1. Reuse auth fixes, do not re-debug them. Arrange additional capacity or deadline escalation immediately. |
| 12 Oct | Finish the smallest complete batch 1 flows that fit; proceed to batch 2. If provider setup is blocked, do independent batch 2 work rather than fake OAuth evidence. Reassess remaining hours before claiming a Foundation forecast. |
| 13 Oct | Complete selected workflows and the Docker-only portion of batch 3; fix consequential integration negatives. With one developer, Commerce/Experience batches are backlog, not a promised day's completion. With added independent engineers, batches 4–6 and 7–9 can proceed only after their named API/security dependencies. |
| 14 Oct | Feature freeze; reserve at least 6–8h for one broader regression/negative pass, isolated reviewer start/restart, exact specification/evidence reconciliation and submission packaging. Record each project's honest pass/blocker decision. Do not use this day for unbounded new payment/admin work. |

This schedule does **not** promise Foundation, let alone all three, will be
complete. Original-source recovery is essential to deciding any official gate.
Do not submit known placeholders/simulated payments as completed workflows.

## Low-overhead execution and uncertainty

- Use the ordered batches as implementation prompts only when separately
  authorized. Update existing tickets with one short result per coherent
  batch; no new Project or checklist-row issue proliferation.
- No uncovered implementation gap presently needs a duplicate issue:
  public stock/facet/nonblank questions stay in #38/#95/#65; notification,
  tracking, imports, MFA/audit and encryption already have owners.
  **Untracked source gap:** the original three specification texts/provenance.
  Resolve it together through #94/#72/#77/#93. If originals reveal additional
  clauses, group those gaps into the existing owning tickets.
- Subsequent batches: targeted changed-behavior tests plus meaningful auth/
  ownership/CSRF/payment replay/webhook/inventory concurrency/rollback
  negatives. Keep tests expressly required by the originals. Run one broader
  combined verification before submission, not every suite after every edit.
- Optional polish, FAQ/terms extensions, production/live-provider charging,
  production SMTP (#20), speculative rich-domain refactoring (#13) and broad
  legacy identity backfill (#51) come last **unless originals explicitly
  require them**. Real sandbox payment, required units, admin MFA, encryption,
  rate limits and load reporting remain mandatory backlog.
- Actual audit checks: clean Git/history and local/remote SHA comparison;
  source/schema/config/test inspection; single retrieval of selected issue
  bodies/recent reports; one credential-free live Hive query returned HTTP
  200/no GraphQL errors, **22 categories/42 products** and rating fields.
  Read-only HTTP probes: `/catalog/all-products` and placeholder `/cart` 200;
  `/recover`, `/checkout`, `/admin`, `/contact`, `/about` 404.
  These checks prove neither a purchase workflow nor load targets.
- No test suite/build/load/browser matrix was executed during this audit;
  existing evidence is explicitly reused. No private environment inspection,
  account/key/password change, service restart, secret output or live data
  mutation was needed. Remaining uncertainty: missing originals, provider
  setup, actual mobile/browser behavior, full accessibility and performance,
  original refund/units/filter scope and final deployment-specific encryption.
- Local fixes after `97f4fa7`—auth/password/verification/account-switching and
  recent dropdown/search/filter/mobile-Apply corrections—are **not on remote
  `dev`**. The older backend/cart/checkout/rating/frontend auth foundation is
  present in that remote ancestor. Final source submission requires the
  owner's authorized publication of the exact reviewed local revisions;
  this audit itself does not push or upload source.
