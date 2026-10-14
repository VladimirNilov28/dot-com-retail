# ByteCore — i-love-shopping

ByteCore OÜ's electronics web shop, built for the Foundation, Commerce and
Experience projects. Submission status and evidence:
[`docs/submission-checklist.md`](docs/submission-checklist.md).

## Overview

- **Storefront:** home page; category browsing; faceted search with suggestions;
  sorting by relevance, price and genuine rating. Product pages show variants, media,
  brand, metric and imperial dimensions, reviews, helpful votes and related products.
- **Shopping:** guest and customer carts with merge, cart recommendations, and a
  single-page checkout for guests and signed-in customers. Payment uses Stripe sandbox
  hosted fields, confirmed only by signed webhooks.
- **Orders:** history with status/date filters and paging, detail with status timeline
  and tracking, cancellation before processing, refund requests, and email
  notifications delivered locally through Mailpit.
- **Accounts:** registration with CAPTCHA and email verification, password recovery,
  Google sign-in and linking, optional customer TOTP, sessions, saved addresses and
  account deletion.
- **Administration** (staff with mandatory TOTP, AAL2 enforced in the backend):
  - products, variants and media;
  - categories, shipping options and users/roles;
  - orders: status, tracking, cancel, refund approval;
  - review moderation;
  - atomic CSV/JSON bulk import;
  - audit log.
- **Security:**
  - Next.js BFF token custody, with no tokens in browser storage;
  - Hydra token rotation;
  - role, scope and ownership checks in Spring;
  - token-bucket rate limiting;
  - AES-GCM encryption at rest for personal and order data;
  - TLS on the reviewer stack.

## Architecture

```
Browser ──HTTPS──> Caddy (review) ──> Next.js storefront + BFF (:3000)
                                        │  session cookie only; tokens stay server-side
                                        ├──> Hive Router (:4002, GraphQL gateway)
                                        │       └──> Spring Boot / Netflix DGS "retail" subgraph (:8080)
                                        │               ├── PostgreSQL (Flyway V0–V25)
                                        │               ├── Kafka <──> Go payment-service (:8081) <── Stripe webhooks
                                        │               ├── SMTP → Mailpit (order/contact email outbox)
                                        │               └── media volume (uploaded product images)
                                        ├──> Ory Kratos (identity, MFA, recovery, Google OIDC)
                                        └──> Ory Hydra (OAuth2/OIDC tokens) + oauth-service (login/consent bridge)
```

- **ERD and migrations:** [`docs/database/FLYWAY_DB.md`](docs/database/FLYWAY_DB.md).
- **GraphQL schema:** [`docs/api/graphql-schema.md`](docs/api/graphql-schema.md).
- **Browser token model:** [`docs/auth/browser-contract.md`](docs/auth/browser-contract.md).
- **Security:**
  - [`docs/security/rate-limiting.md`](docs/security/rate-limiting.md)
  - [`docs/security/encryption-at-rest.md`](docs/security/encryption-at-rest.md)
  - reviewer concept walkthrough: [`docs/review-walkthrough.md`](docs/review-walkthrough.md)

## Quick start for reviewers

The whole application runs in Docker. You need only Docker, `bash` and this checkout.

**1. Check the prerequisites**

- Docker Engine with Compose v2.24 or newer (`docker compose version`).
- `/dev/fuse` on the Docker host. It is standard in Docker Desktop. On Linux, run
  `sudo modprobe fuse` if `/dev/fuse` is missing. The database is stored on
  encrypted storage, which needs FUSE.
- You don't need Java, Node, Bun, a Stripe CLI or `make`.

**2. (Optional) Enable card payments with Stripe test keys**

```bash
cp .env.stripe.example .env.stripe   # Git-ignored; paste sk_test_... and pk_test_...
```

Skip this step and everything except card payment still works.

**3. Start the stack.** The first build takes a few minutes.

```bash
make review            # or: bash scripts/review-up.sh
```

The script:

- generates private secrets and certificates;
- builds and starts the stack, runs migrations and waits for every service;
- loads a demo catalog;
- prints the URLs.

**4. Open the shop:** <https://bytecore.localhost:8443>

- The certificate comes from a local CA generated on your machine. Import
  `infrastructure/review/certs/ca.crt` as trusted (see
  [`docs/review-setup.md`](docs/review-setup.md#tls-and-trusting-the-local-ca)), or
  accept the browser warning once for each port.
- Emails (verification codes, order emails) are in Mailpit:
  <https://bytecore.localhost:8425>.
- Test card: `4242 4242 4242 4242`, any future expiry, any CVC.
- Decline cards: `4000 0000 0000 9995` (insufficient funds) and
  `4000 0000 0000 0069` (expired).

**5. Sign in as administrator**

1. Sign in with `admin@bytecore.ee`. The password is `ADMIN_PASSWORD` in the generated
   `.env.review`.
2. Open `/admin/security` and scan the QR code with any authenticator app. A second
   factor is mandatory for staff.
3. Use `/admin` for products, image upload, categories, orders, users, reviews,
   import and the audit log.

**6. Stop the stack**

```bash
make review-down       # or: bash scripts/review-down.sh   (keeps data)
bash scripts/review-down.sh --volumes   # removes the review data completely
```

The demo catalog has no pictures. Upload a JPEG or PNG under `/admin/products` to see
thumbnail (≤320 px), medium (≤800 px) and full-size (≤2000 px) versions
([`docs/api/product-media.md`](docs/api/product-media.md)).

The database storage key is `infrastructure/review/secrets/pgdata.key` (Git-ignored).
Without it, the encrypted database cannot be opened.

More detail is in [`docs/review-setup.md`](docs/review-setup.md):

- ports and other overrides;
- trusting the CA on each operating system;
- Google sign-in setup;
- troubleshooting.

The concept walkthrough for the review is in
[`docs/review-walkthrough.md`](docs/review-walkthrough.md).

## Production-mode stack

`make prod` (= `bash scripts/prod.sh up`) builds and starts the production stack. It is
isolated from dev and review, with its own project, volumes, `.env.prod` and secrets:

- production builds, migrations and readiness checks;
- an encrypted Postgres and a TLS edge;
- no dev bypasses, and no demo data unless you run `make seed-prod`;
- Stripe in test mode only.

It needs no keys, domain or real data. The first `make prod` creates `.env.prod` with
local defaults. Trust `infrastructure/prod/secrets/tls/ca.crt` in the browser, then open
`https://bytecore.localhost`. Emails are captured by Mailpit at `http://127.0.0.1:18025`.
Card payments are optional: put Stripe test keys into the Git-ignored `.env.stripe`, and
the webhook listener starts automatically.
For a showcase, run `make seed-prod` once afterwards. It loads the demo catalog, stock,
customers and orders into the fresh stack, and refuses if data already exists.

`make prod-down` stops it and keeps all data; `make prod-status` and `make prod-logs`
inspect it. Configuration, TLS modes, external setup (DNS, Google, Stripe webhook,
SMTP), the admin bootstrap, and backup/restore and upgrade/rollback are covered in
[`docs/production-setup.md`](docs/production-setup.md).

## Demo walkthrough

1. Browse `/catalog`, search with suggestions, filter by facets, and sort by rating or
   price. Open a product, switch variants, read reviews sorted by helpfulness and vote
   one helpful. Writing a review requires a paid, non-refunded purchase of the product
   ([`docs/api/reviews.md`](docs/api/reviews.md)).
2. Add items to the cart as a guest. The cart shows "You may also like". Check out with
   Stripe test card `4242 4242 4242 4242`. The confirmation page updates when the
   signed webhook marks the order PAID, and the email appears in Mailpit. Decline
   cards such as `4000 0000 0000 9995` (insufficient funds) show "Payment declined"
   with the reason and allow a retry on the same order
   ([`docs/api/checkout.md`](docs/api/checkout.md)).
3. Register an account (the verification code is in Mailpit) and sign in. Then use:
   - `/account/addresses` to save addresses;
   - `/account/orders` to filter history, view details and tracking, cancel before
     processing, or request a refund after payment;
   - `/account/security` to enable optional TOTP.
4. Use `/contact` to send a message. Support and acknowledgement emails arrive in
   Mailpit. `/about` and unknown URLs show the about page and 404.
5. Staff:
   1. Sign in as the bootstrap admin and enrol TOTP at `/admin/security`.
   2. Manage products, media, categories, shipping, users and roles, and orders
      (status, tracking, refund approval).
   3. Moderate reviews, run `/admin/import` with
      [`docs/api/samples`](docs/api/samples), and inspect `/admin/audit`.

## Implemented extras

- Kafka-based payment-service with an outbox and dead-letter handling.
- Guest-to-customer cart merge.
- Google account linking.
- Staff audit log and atomic bulk import.
- Encrypted BFF session store.
- Product media pipeline on a durable volume.
- Load-test harness: `perf/run.sh` and [`docs/performance/report.md`](docs/performance/report.md).

---

# Development guide

For the standalone HeroUI v3 dark frontend foundation, see
[`frontend/README.md`](frontend/README.md). Its component demonstration needs
no backend services. For the one-command, HTTPS, Docker-only reviewer stack
(`make review`), see [`docs/review-setup.md`](docs/review-setup.md).

## Local development architecture

- **Spring Boot** runs as the Compose service `backend`, published on
  `127.0.0.1:8080`. Its multi-stage image builds with Java 21 and the Gradle
  wrapper; no host JDK is needed to start it. It is the Federation-compatible
  `retail` subgraph — the only GraphQL subgraph today.
- **Hive Router** (`infrastructure/hive/`, Docker Compose service
  `hive-router`) is the single external GraphQL entry point, on `:4002`. It
  composes a local supergraph from the `retail` subgraph and proxies client
  traffic to Spring; it forwards `Authorization` plus the narrowly configured
  guest-cookie/Origin/request headers and takes no part in JWT validation
  or authorization — Spring Resource Server
  remains the sole authority for both. Its built-in GraphQL IDE is the
  canonical local GraphQL editor; **Spring/DGS GraphiQL is disabled**.
- **Docker Compose** (`infrastructure/compose.yml`) runs the supporting
  infrastructure: PostgreSQL, Ory Hydra, Ory Kratos, `oauth-service` (a
  small Python service), Spring, Hive Router, Kafka and the Go Payment Service.
  Jenkins, if present, is unrelated
  infra on a separate Compose project.
- `oauth-service` exposes a login/consent bridge on `:4446` and a
  loopback-only internal dev-token endpoint on `127.0.0.1:4447`.
- Flow: `oauth-service` → `http://backend:8080` → Spring.
  Hive Router also reaches Spring at `http://backend:8080/graphql`.
  Spring reaches PostgreSQL, Kafka, Hydra and Kratos by their Compose service
  names. Hydra JWTs retain their public issuer (`http://127.0.0.1:4444`);
  Spring fetches their signing keys internally from `hydra:4444`.
  The Compose network remains pinned to
  `172.28.88.0/24` (see `infrastructure/compose.yml`'s `networks.default.ipam`
  block). No Docker-to-host Spring connection or firewall exception is needed.
- The Go Payment Service is **not** a Hive subgraph. Financial requests/results
  use Kafka and transactional outboxes. Stripe sandbox hosted-payment sessions
  are retrieved by Spring over a protected internal HTTP seam after order
  ownership validation; browser success never settles an order. See
  [Stripe setup and webhook forwarding](payment-service/README.md#stripe-sandbox-setup).

### Initial setup

```bash
cp .env.example .env
```

Existing `.env` files do not need their old host-oriented DB, Kafka or Spring
URLs rewritten: Compose sets the internal service addresses explicitly and
passes `DB_USERNAME` / `DB_PASSWORD` to Spring. If `HYDRA_PUBLIC_URL` is missing,
add `HYDRA_PUBLIC_URL=http://hydra:4444`. Stop any manually running Spring
instance before starting the container, so port 8080 is free.

### Day to day

```bash
make dev             # build/start the stack, including Spring; wait for readiness
make front-dev        # run the Next.js frontend on :3000 (separate terminal)
make debug            # same stack, with Spring JDWP on 127.0.0.1:5005
make doctor           # diagnose the stack (env, network, ports, health)
make logs             # follow container logs
make restart          # restart Docker infra (e.g. to re-run oauth-service's bootstrap)
make down             # stop Docker infra
make config           # print the effective (resolved) docker compose config
```

`make dev` returns after readiness; `WAIT_TIMEOUT=300 make dev` increases the
default 180-second wait. Source changes require another `make dev` to rebuild
the backend image. `make debug` starts Spring without suspending startup;
attach your IDE to localhost:5005. Normal `make dev` removes the debug override.
Ordinary startup/restart preserves database and Kafka volumes; only the explicit
`make clean`, `make dev-clean`, and `make debug-clean` targets delete them.
Gradle build/test/format/seed targets remain host-development tools requiring
their existing JDK/psql dependencies.

### Obtaining a dev JWT

```bash
http POST :4447/internal/token email=admin@bytecore.ee password=admin-dev-password
```

Returns a real Hydra-issued bearer JWT for the seeded dev admin user
(`role: ADMIN`, full scope set). Loopback-only by design — never exposed
beyond `127.0.0.1`.

### Optional TOTP two-factor authentication

Kratos `v26.2.0` owns optional TOTP enrollment, AAL2 challenges and single-use
backup codes. Use its native settings/login APIs; no frontend auth UI or
authenticator-vendor integration is required. Both OAuth login bridges enforce
Kratos's `highest_available` assurance, including remembered Hydra logins.
Enrolled users complete Kratos AAL2 and submit the native session token to
`POST :4447/internal/token`; password-only requests cannot issue their JWT.

See the [TOTP runbook](infrastructure/oauth/service/README.md#totp-runbook) for
enrollment, secure disable, recovery codes and the executable local smoke test.
Hydra/Kratos admin APIs and Mailpit ports are published on loopback only.
The web client cannot request machine-only user-provisioning scope.

### GraphQL: querying through Hive Router

Open `http://localhost:4002` for the Hive Router's built-in GraphQL IDE
(Docs explorer, autocomplete, queries/mutations/subscriptions) — the
canonical local GraphQL editor. Add the dev JWT from above as a header:

```json
{ "Authorization": "Bearer <access_token>" }
```

The retail subgraph's supergraph is generated locally and reproducibly —
see `infrastructure/hive/scripts/compose-supergraph.sh` — no Hive Cloud
account or registry is used. Regenerate it after a schema change:

```bash
./infrastructure/hive/scripts/compose-supergraph.sh
docker compose -f infrastructure/compose.yml --env-file .env restart hive-router
```

Both `retail.graphql` and `supergraph.graphql` are versioned snapshots. Include
their regenerated changes with schema changes. Check their application root
fields against the source SDL with
`python3 -m unittest discover -s infrastructure/hive/tests -v`.

For a TOTP-enrolled account, complete the AAL2 flow in the TOTP runbook first,
then supply its access token privately through the environment, not an argument:

```bash
read -rsp "AAL2 access token: " HIVE_BEARER_TOKEN; echo
export HIVE_BEARER_TOKEN
./infrastructure/hive/scripts/compose-supergraph.sh
unset HIVE_BEARER_TOKEN
```

This skips password login, not backend authentication. Without this variable,
the existing `DEV_EMAIL`/`DEV_PASSWORD` password flow remains available for
non-enrolled accounts. Failed authentication leaves the previous SDL intact.

Health/readiness: `curl http://localhost:4002/health` (liveness),
`curl http://localhost:4002/readiness` (supergraph loaded).

### Guest shopping

[Checkout API](docs/api/checkout.md) adds server quotes, authenticated/guest
placement, development shipping and immutable confirmations. Public
`createOrder` now requires accepted checkout input and a stable request UUID.
Guest confirmation uses a separate HttpOnly cookie established before placement
to support lost-response recovery. Deploy nullable guest ownership in the Go
Payment Service before enabling guest checkout. The compact `/checkout` and
protected `/order-confirmation/<requestId>` routes support genuine Stripe test
payments, same-order retry and bounded authoritative status refresh. Order
notifications use the configured SMTP/Mailpit outbox; local Mailpit evidence is
not a claim of external inbox delivery. Shipping remains configured development
data, not a live carrier or delivery promise.

[Guest Cart API and security guide](docs/api/guest-cart.md) documents anonymous
server-persisted carts, cookie transport, origin/preflight requirements, live
server totals, expiration and all-or-nothing login merge with durable retries.
Use `credentials: "include"` and `X-Guest-Cart-Request: 1` from the allowed
Next.js dev origin (`http://localhost:3000`). Stock remains checkout-only;
no inventory is reserved by adding or merging cart items.

Registration now uses Kratos browser self-service with genuine email verification;
Spring `POST /auth/register` returns 410. The Next.js BFF holds OAuth tokens
server-side and its current `user:read` grant does not implement cart merge.
Manual backend merge still uses an appropriately scoped Hydra token. No
storefront cart mutations or second authentication system are included.

### Storefront authentication (#40, #66, #67)

The browser contract, production constraints, dedicated database migration,
secret/key rotation and maintenance runbooks are in
[`docs/auth/browser-contract.md`](docs/auth/browser-contract.md).
Configure root and frontend examples privately before deliberate rollout:
existing PostgreSQL volumes do **not** automatically create the new auth store.
Use canonical `http://127.0.0.1:3000` for local browser authentication and exact
matching provider callback/origin configuration. Login, verification, existing
MFA and logout support native forms; new signup requires the real self-hosted
ALTCHA JavaScript check. Registration does not automatically issue OAuth tokens.
The `/account` page is status/logout only, not an account dashboard.

### Common failure modes

| Symptom | Likely cause |
|---|---|
| `oauth-service` cannot reach `backend:8080` | Check backend readiness and `docker compose -f infrastructure/compose.yml --env-file .env logs backend`; run `make doctor` |
| `401` from Spring's `/internal/users` | Usually a stale Hydra client secret from a prior broken bootstrap run; self-heals on the next `oauth-service` restart (`make restart`) via its idempotent reconciliation |
| `docker compose config` fails naming a variable | A required value is missing from `.env` — copy from `.env.example` |
| `make dev` reports an unhealthy service | Inspect that service's logs; Spring must complete Flyway/schema validation before OAuth bootstrap starts |
| `kratos` restarts with `A private registration hook secret is required.` | `.env` predates the auth package and still has `replace-with…` placeholders for `AUTH_BRIDGE_SECRET`, `BFF_CLIENT_SECRET`, `CAPTCHA_SECRET` and the three auth DB passwords. Set random values, recreate `postgres` (volume kept), run `04-create-storefront-auth.sh` once as described in [`docs/auth/browser-contract.md`](docs/auth/browser-contract.md), then `make dev` |
| `127.0.0.1:4447` refuses connections | `oauth-service` is still bootstrapping or crash-looping — `docker logs oauth-service` |
| Port 8080 is already allocated | Stop the manually started Spring instance before running `make dev` |
