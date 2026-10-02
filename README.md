# Backend development

## Local development architecture

- **Spring Boot** runs manually on the host (usually from your IDE), on
  `:8080`. It is never containerized in dev. It is the Federation-compatible
  `retail` subgraph — the only GraphQL subgraph today.
- **Hive Router** (`infrastructure/hive/`, Docker Compose service
  `hive-router`) is the single external GraphQL entry point, on `:4002`. It
  composes a local supergraph from the `retail` subgraph and proxies client
  traffic to Spring; it forwards only the `Authorization` header and takes
  no part in JWT validation or authorization — Spring Resource Server
  remains the sole authority for both. Its built-in GraphQL IDE is the
  canonical local GraphQL editor; **Spring/DGS GraphiQL is disabled**.
- **Docker Compose** (`infrastructure/compose.yml`) runs the supporting
  infrastructure: PostgreSQL, Ory Hydra, Ory Kratos, `oauth-service` (a
  small Python service), and Hive Router. Jenkins, if present, is unrelated
  infra on a separate Compose project.
- `oauth-service` exposes a login/consent bridge on `:4446` and a
  loopback-only internal dev-token endpoint on `127.0.0.1:4447`.
- Flow: `oauth-service` (in Docker) → `http://host.docker.internal:8080` →
  Spring (on the host). Hive Router reaches Spring the same way, via
  `host.docker.internal:8080`. The Compose network is pinned to
  `172.28.88.0/24` (see `infrastructure/compose.yml`'s `networks.default.ipam`
  block) so this path is deterministic across machines and network
  recreations.
- The future Payment Service is **not** a Hive subgraph and Hive Router is
  not involved in reaching it. It integrates asynchronously via Kafka
  (`PaymentEventPublisher` seam, not yet wired) — Hive Router is
  client-facing GraphQL infrastructure only.

### Initial setup

```bash
cp .env.example .env
```

### One-time firewall setup

The host firewall (`ufw`) defaults to dropping all inbound traffic, which
also blocks Docker containers from reaching Spring on the host. Allow only
the pinned dev subnet, on only port 8080:

```bash
sudo ufw allow from 172.28.88.0/24 to any port 8080 proto tcp comment 'bytecore-dev: oauth-service -> Spring'
```

This is scoped to the dev Docker subnet and port 8080 only — it does not
open Spring to the LAN/Internet, and does not touch any other firewall
rule. Verify it any time with `make firewall-check` or `make doctor`.

### Day to day

```bash
make dev             # start Docker infra, then run Spring (blocks in this terminal)
make doctor           # diagnose the whole stack (env, network, firewall, ports, health)
make restart          # restart Docker infra (e.g. to re-run oauth-service's bootstrap)
make down             # stop Docker infra
make config           # print the effective (resolved) docker compose config
```

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
cd infrastructure/hive && ./scripts/compose-supergraph.sh
docker compose -f infrastructure/compose.yml --env-file .env restart hive-router
```

Health/readiness: `curl http://localhost:4002/health` (liveness),
`curl http://localhost:4002/readiness` (supergraph loaded).

### Common failure modes

| Symptom | Likely cause |
|---|---|
| `oauth-service` times out reaching `host.docker.internal:8080` | Firewall rule missing/stale, or the Compose subnet drifted from `172.28.88.0/24` — run `make doctor` |
| `401` from Spring's `/internal/users` | Usually a stale Hydra client secret from a prior broken bootstrap run; self-heals on the next `oauth-service` restart (`make restart`) via its idempotent reconciliation |
| `docker compose config` fails naming a variable | A required value is missing from `.env` — copy from `.env.example` |
| `oauth-service` crash-looping right after `make dev` | Expected transiently: Spring isn't up yet. Bounded retries + container restart recover automatically once Spring starts |
| `127.0.0.1:4447` refuses connections | `oauth-service` is still bootstrapping or crash-looping — `docker logs oauth-service` |
| Docker subnet/firewall mismatch | `make doctor` checks 5 and 12 |
