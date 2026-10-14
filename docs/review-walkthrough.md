# Review walkthrough — key concepts and where to see them

A short guide for the review conversation. Each section names the concept, how
this project applies it, and the file or command that shows it. The startup
steps are in [`review-setup.md`](review-setup.md); the requirement-by-requirement
status is in [`submission-checklist.md`](submission-checklist.md).

## 1. Architecture

```
browser ──HTTPS──▶ Caddy edge ──▶ Next.js storefront + BFF ──▶ Hive Router ──▶ Spring Boot (DGS "retail" subgraph) ──▶ PostgreSQL
                        │                 │                                         │  ▲
                        │                 └─▶ Kratos (identity) / Hydra (OAuth2/OIDC) │  │ Kafka (payment requests/results)
                        └─▶ /webhooks/stripe ──▶ Go payment-service ◀─────────────────┘  │
                                                    └──▶ Stripe (test mode) ─────────────┘
```

* **Next.js BFF** renders the storefront and is the only component the browser
  talks to for data. It holds OAuth tokens server-side and forwards only
  allow-listed GraphQL operations (`frontend/src/lib/graphql/proxy.ts`).
* **Hive Router** is the federated GraphQL gateway; **Spring** is the single
  resource server and authorization authority (`SecurityConfig`).
* **Kratos** handles registration, login, recovery, TOTP and Google; **Hydra**
  issues OAuth2 tokens; the Python `oauth-service` bridges Kratos login to Hydra.
* **Go payment-service** owns Stripe: it creates PaymentIntents, verifies
  webhooks and publishes payment results to Kafka; Spring's Order Service
  consumes them (`PaymentResultListener`).
* Order e-mails go through a transactional outbox (`order_notifications`) to
  SMTP (Mailpit in local/review).

## 2. JWT — header, payload, signature

Hydra issues access tokens as signed JWTs (`strategies.access_token: jwt` in
`infrastructure/oauth/hydra/hydra.yml`).

* **Header:** `alg` (RS256), `kid` (key in Hydra's JWKS), `typ`.
* **Payload:** `iss` (Hydra public URL), `sub` (Spring user id), `aud`,
  `exp`/`iat`/`nbf`, `jti`, `scp` (granted OAuth scopes, mapped to `SCOPE_*` authorities), plus the allow-listed top-level
  claims `role` and `aal` (`allowed_top_level_claims`).
* **Signature:** RS256 over `base64url(header).base64url(payload)`; Spring
  verifies it against Hydra's JWKS and checks issuer, audience and expiry
  (`JwtDecoderConfigurationTest`). Roles map to authorities; staff roles also
  require `aal=aal2` (second factor).

Demonstrate: decode any access token at jwt.io-style tooling **in a private
session** (never paste real tokens into third-party sites); the BFF never sends
tokens to the browser, so use the disposable flow in
[`auth/browser-contract.md`](auth/browser-contract.md).

## 3. Token lifetime, rotation and revocation

* Access token 30 min, refresh token 168 h (7 days).
* Refresh tokens are single-use: every refresh returns a new refresh token and
  the previous one is rejected (`invalid_grant`); reuse of an old token revokes
  the chain.
* Logout revokes the refresh token at Hydra, which also invalidates the access
  tokens of that grant, and deletes the server-side BFF session. Spring checks
  revocation on every authenticated request (see
  [`auth/browser-contract.md`](auth/browser-contract.md#token-revocation) for the
  mechanism, cache bound and fail-closed behaviour).
* Role changes and account deletion revoke the user's grants, so tokens carrying
  an old role stop working.
* **Storage:** tokens are sealed (JWE) in the BFF's server-side session store;
  the browser only holds an opaque HttpOnly, Secure, SameSite cookie. Nothing
  is in `localStorage`/`sessionStorage` or readable by page JavaScript. The
  specification's "access tokens in memory" is satisfied in the sense of "not in
  persistent browser storage"; the reviewer may judge this interpretation.

## 4. ACID and the ERD

* ERD: [`database/FLYWAY_DB.md`](database/FLYWAY_DB.md#entity-relationship-diagram)
  (Mermaid). Schema is owned by Flyway; JPA runs with `ddl-auto=validate`.
* **Atomicity:** order placement writes the order, items, address snapshots,
  inventory decrement and payment outbox row in one transaction
  (`OrderService`, `docs/api/checkout.md`); bulk import is all-or-nothing
  (`docs/api/bulk-import.md`).
* **Consistency:** foreign keys, unique constraints (one review per user and
  product, one vote per user and review, idempotency keys), check constraints
  on quantities and statuses.
* **Isolation:** inventory and orders are locked with `SELECT … FOR UPDATE`
  (`PESSIMISTIC_WRITE` in `InventoryRepository`, `OrderRepository`), so
  concurrent buyers of the last unit cannot both succeed; outbox workers use
  `FOR UPDATE SKIP LOCKED`.
* **Durability:** PostgreSQL WAL; the Kafka consumer commits its offset only
  after the listener (and its order transaction) completes; failing messages are
  retried and then routed to a dead-letter topic (`PaymentKafkaConsumerConfig`).

## 5. Database scalability

* Indexes for every hot path: trigram GIN indexes for search (`V8`), foreign-key
  and status/date indexes for order history, batch loaders (`findAllByXIdIn`)
  to avoid N+1 in GraphQL lists.
* Every collection query is bounded (page size caps) — no unbounded scans.
* Stateless Spring and BFF instances; shared state (rate-limit buckets, sessions,
  outboxes) is in PostgreSQL, so more instances can be added behind the edge.
* Next steps documented but not applied: read replicas for catalog reads,
  partitioning `orders`/`admin_audit_log` by time, a cache for catalog pages.

## 6. Search

Catalog search uses PostgreSQL `pg_trgm` trigram similarity with GIN indexes
(`V8__add_catalog_search_indexes.sql`, `CatalogSearchService`): typo-tolerant
matching on product names, brands and categories, combined with filters (category,
price range, brand, attributes, in-stock), sorting (relevance, price ascending/descending,
rating) and pagination. Suggestions power the header's quick search.

## 7. PCI DSS

* Card data is entered only in Stripe-hosted Payment Element iframes; PAN and CVC
  never reach our servers, logs or database (SAQ A-style scope).
* Servers handle only PaymentIntent ids, amounts, status and safe decline codes.
* Webhooks are verified against the raw body with the Stripe signing secret.
* Secrets live in ignored env files; TLS on every review origin.

## 8. CIA triad

* **Confidentiality:** TLS everywhere in the review stack; AES-256-GCM
  application-level encryption for PII and order data; sealed BFF tokens;
  storage-layer encryption for the review database
  ([`security/encryption-at-rest.md`](security/encryption-at-rest.md)); least
  privilege roles + mandatory staff TOTP (AAL2) enforced by Spring.
* **Integrity:** signed JWTs, signed webhooks, append-only admin audit log,
  server-side recalculation of prices and totals, database constraints.
* **Availability:** token-bucket rate limiting (`security/rate-limiting.md`),
  health checks and restart policies, outbox retries and dead-letter handling,
  bounded queries.

## 9. Testing

* **Automated:** JUnit 5/Mockito unit tests, DGS GraphQL tests, Spring Security
  authorization tests with real JWTs, Testcontainers integration tests
  (PostgreSQL, Kafka) — `cd backend && ./gradlew test` (tags:
  `-Pgroup=unit|graphql|integration`); Go tests `cd payment-service && go test ./...`;
  frontend `bun test` (`*.test.mjs`), `bun run typecheck`, `bun run lint`.
* **Manual:** the scripted demo in [`review-setup.md`](review-setup.md) —
  register, verify by Mailpit, browse/search, cart, Stripe test cards (success,
  declines), order history/cancel/refund request, staff admin with TOTP.

## 10. Performance and bottlenecks

[`performance/report.md`](performance/report.md): all targets met (p90 < 2 s at
50 users, ≥ 10 TPS, 0% failures). Capacity: p90 stays under 2 s up to ~250
concurrent users and first exceeds 5 s at ~450. The bottleneck is the single
Next.js SSR process (CPU-bound), not Spring or PostgreSQL; remedy: more Next.js
replicas and page caching. Measured on the dev topology on one host (load
generator on the same machine) — absolute numbers are conservative.
