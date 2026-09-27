# Backend development

## Local development architecture

- **Spring Boot** runs manually on the host (usually from your IDE), on
  `:8080`. It is never containerized in dev.
- **Docker Compose** (`infrastructure/compose.yml`) runs the supporting
  infrastructure: PostgreSQL, Ory Hydra, Ory Kratos, and `oauth-service`
  (a small Python service). Jenkins, if present, is unrelated infra on a
  separate Compose project.
- `oauth-service` exposes a login/consent bridge on `:4446` and a
  loopback-only internal dev-token endpoint on `127.0.0.1:4447`.
- Flow: `oauth-service` (in Docker) → `http://host.docker.internal:8080` →
  Spring (on the host). The Compose network is pinned to `172.28.88.0/24`
  (see `infrastructure/compose.yml`'s `networks.default.ipam` block) so this
  path is deterministic across machines and network recreations.

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

### Common failure modes

| Symptom | Likely cause |
|---|---|
| `oauth-service` times out reaching `host.docker.internal:8080` | Firewall rule missing/stale, or the Compose subnet drifted from `172.28.88.0/24` — run `make doctor` |
| `401` from Spring's `/internal/users` | Usually a stale Hydra client secret from a prior broken bootstrap run; self-heals on the next `oauth-service` restart (`make restart`) via its idempotent reconciliation |
| `docker compose config` fails naming a variable | A required value is missing from `.env` — copy from `.env.example` |
| `oauth-service` crash-looping right after `make dev` | Expected transiently: Spring isn't up yet. Bounded retries + container restart recover automatically once Spring starts |
| `127.0.0.1:4447` refuses connections | `oauth-service` is still bootstrapping or crash-looping — `docker logs oauth-service` |
| Docker subnet/firewall mismatch | `make doctor` checks 5 and 12 |
