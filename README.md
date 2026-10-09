# Backend development

For the standalone HeroUI v3 dark frontend foundation, see
[`frontend/README.md`](frontend/README.md). Its component demonstration needs
no backend services. Full-stack Docker reviewer setup remains separate work.

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
- The future Payment Service is **not** a Hive subgraph and Hive Router is
  not involved in reaching it. It integrates asynchronously via Kafka
  (`PaymentEventPublisher` seam, not yet wired) — Hive Router is
  client-facing GraphQL infrastructure only.

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
Payment Service before enabling guest checkout; no real gateway is integrated.

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
