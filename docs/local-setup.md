# Local Setup

This guide walks through setting up the ByteCore e-commerce application for local development.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Only for host Gradle build/tests; Spring startup builds/runs in Docker |
| Node.js | 22+ | Required for Next.js 16 / TypeScript 5 |
| bun | 1.4.2 | Frontend package manager (see `frontend/package.json#packageManager`) |
| Docker + Compose | Current | Spring and supporting services; Testcontainers for host integration tests |
| Git | Latest | Version control |

## Project Structure

```
dot-com-retail/
├── backend/          # Spring Boot 4.0 (Spring MVC, Spring Data JPA, GraphQL via DGS, Kafka)
├── frontend/         # Next.js 16 (React 19, Tailwind CSS 4)
├── docs/             # Project documentation
├── scripts/          # Utility scripts
├── infrastructure/   # Docker, Kubernetes, Jenkins, Nginx configs
└── .github/          # GitHub Actions / CI
```

## Backend

The backend is a Spring Boot 4.0 application written in Java 21, using
Spring MVC (blocking) and Gradle as the build system.

### Key dependencies

- **Spring Boot 4.0.7** (Spring MVC, Security, OAuth2 Resource Server, Actuator)
- **Spring Data JPA** over blocking JDBC (schema owned by Flyway, `ddl-auto=validate`)
- **Flyway** for database migrations
- **GraphQL** via Netflix DGS Framework 11.1.0
- **Apache Kafka** for messaging (plain `spring-kafka` `@KafkaListener`s)
- **SpringDoc OpenAPI** for REST API documentation

### Container startup

```bash
cp .env.example .env # first run only; do not overwrite an existing .env
make dev
```

Spring runs as the `backend` service on <http://localhost:8080>. The image uses
Java 21 and builds `bytecore-backend.jar` with the repository Gradle wrapper.
Flyway runs before application readiness; OAuth bootstrap waits for Spring.
Use `make debug` and attach an IDE to `127.0.0.1:5005` for container debugging.
Stop any manually started Spring instance first.

### Host build and tests

```bash
cd backend
./gradlew build
```

This runs the full build pipeline: formatting (Spotless), compilation, tests (JUnit 5 + Testcontainers), coverage (JaCoCo), and static analysis (SonarQube).

### Database

The shared `infrastructure/compose.yml` defines PostgreSQL and the auth stack:

| Setting | Value |
|---|---|
| Database | `retail` |
| Container credentials | `DB_USERNAME` / `DB_PASSWORD` from the root `.env` |
| Spring container connection | `postgres:5432`, credentials passed from root `.env` by Compose |

Spring's Docker Compose auto-management is disabled. Compose owns startup,
including Spring, and sets its internal database/auth/Kafka addresses explicitly:

```bash
docker compose -f infrastructure/compose.yml --env-file .env up -d --build --wait
```

### Configuration

Application properties live in `backend/src/main/resources/application.yaml`. As the project grows, you'll add configuration for the database connection, Kafka, OAuth2, and other services. Create environment-specific profiles under `application-{profile}.yaml`.

### Common commands

| Command | What it does |
|---|---|
| `make dev` (repository root) | Build/start the containerized stack and wait for readiness |
| `make debug` (repository root) | Start the stack with loopback-only Spring remote debugging |
| `./gradlew build` | Full build (format, compile, test, coverage) |
| `./gradlew test` | Run unit and integration tests |
| `./gradlew jacocoTestReport` | Generate coverage report |
| `./gradlew spotlessApply` | Auto-format Java source |
| `./gradlew dependencyCheckAnalyze` | Check dependencies for known CVEs |

### Testing

Tests use JUnit 5 with Testcontainers. The `TestcontainersConfiguration` class spins up disposable PostgreSQL and Kafka containers during integration tests — no shared state, no cleanup needed. Test classes should use the `@Import(TestcontainersConfiguration.class)` annotation.

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "ee.bytecore.backend.YourTestClass"
```

## Frontend

The frontend is a minimal Next.js App Router foundation with HeroUI v3,
a fixed dark theme, and a working component demonstration. It does not yet
implement storefront or authentication flows. See
[`frontend/README.md`](../frontend/README.md) for setup, design conventions,
and browser smoke checks, and `frontend/AGENTS.md` before changing Next.js APIs.

### Key dependencies

- **Next.js 16.3.6** (App Router) with **React 19.2.8** and React Compiler
- **HeroUI 3.2.6**, fixed dark server/client rendering
- **Tailwind CSS 4.3.3** via PostCSS, **TypeScript 5.9.3**, **ESLint 9.39.5**
- **Bun 1.4.2** and a committed `bun.lock`

### Setup

```bash
cd frontend
bun install --frozen-lockfile
```

The #60 demonstration needs no environment variables or backend services.
These host-development commands are not the Docker-only reviewer workflow;
that separate work remains tracked in #59/#94.

### Common commands

| Command | What it does |
|---|---|
| `bun dev` | Dev server on `http://localhost:3000/` |
| `bun run build` | Production build |
| `bun start` | Serve the production build |
| `bun run lint` | Run ESLint |
| `bun run typecheck` | Generate route types and run TypeScript without emitting files |

## Running the Full Stack

1. **Start Spring and the shared infrastructure** (PostgreSQL, Kratos,
   Hydra, oauth-service, Hive, Kafka, Go Payment Service and Mailpit):
   ```bash
   make dev
   ```

2. **Start the frontend** (in another terminal):
   ```bash
   make front-dev
   ```

3. Open `http://localhost:3000/` in your browser.

`make dev` returns after readiness. Use `make logs` for container output.
Do not launch a second Spring process on port 8080. Normal restart/down retains
volumes; the explicitly named clean targets are destructive.

For a production-style run, build then start instead:
```bash
cd frontend
bun run build
bun start
# Open http://localhost:3000/
```

## User Registration

Normal end users self-register directly against the Spring backend (not
through Hive Router / GraphQL) — this is the one intentionally
unauthenticated endpoint in the API:

```bash
curl -s -X POST http://localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
        "username": "jane-doe",
        "email": "jane@example.com",
        "password": "a-strong-test-password",
        "dateOfBirth": "1995-06-15"
      }'
```

This creates the canonical Spring `User` (role always `USER` — the request
has no `role` field, so it cannot be supplied by the client), creates a
matching Kratos identity (credentials only — Spring never stores the
password), and links them via `metadata_admin.spring_user_id`, mirroring the
existing dev/admin bootstrap convention. Duplicate username/email returns
`409 Conflict`; invalid input returns `400 Bad Request`.

Once registered, the user logs in through the existing Kratos/Hydra flow
(see "Get a dev JWT" below for the headless dev-token shortcut, or the
browser login flow via Hive Router/`bytecore-web`) — no separate
registration-specific login path exists.

## Guest Cart

Anonymous shopping uses the [Guest Cart GraphQL contract](api/guest-cart.md)
through Hive at `http://localhost:4002/graphql`, not the registration REST
endpoint. Browser requests from `http://localhost:3000` use POST JSON,
`credentials: "include"` and `X-Guest-Cart-Request: 1`; the server manages an
HttpOnly guest cookie. Use consistent hostnames and never log the cookie.

After registration, complete the existing Hydra/Kratos login, then invoke
`mergeGuestCart(requestId: UUID!)` with the JWT and guest cookie. Keep that
UUID across retries. Merge conflicts preserve both carts; stock remains
checkout-only. No Next.js page/callback integration is implemented here.

## Optional TOTP two-factor authentication

Users can enroll any standard RFC 6238 authenticator through Kratos's native
authenticated settings flow. Kratos manages secrets, QR/setup data, verification
and single-use backup codes. Spring still owns users/roles and Hydra still issues
JWTs; no frontend authentication UI or CAPTCHA is added.

An enrolled user's password-only dev-token request returns
`403 second_factor_required`. Complete Kratos's native AAL2 login and then send
only `{"kratos_session_token": ...}` to `POST http://127.0.0.1:4447/internal/token`.
Both bridges enforce the highest available assurance, including remembered Hydra
logins. Disabling all 2FA requires privileged removal of both TOTP and lookup codes.
Old JWTs/refresh grants are not retroactively invalidated by enrollment.

The [TOTP runbook](../infrastructure/oauth/service/README.md#totp-runbook) contains
actual enrollment/challenge/disable/recovery commands, JWT checks, the manual
checklist and the runnable real-infrastructure smoke sequence.

## GraphQL API access

Hive Router (`infrastructure/hive/`, Compose service `hive-router`) is the
single external GraphQL entry point — it fronts the Spring/DGS `retail`
subgraph and is started as part of Docker infrastructure (`make dev` /
`docker compose -f infrastructure/compose.yml --env-file .env up -d`).

1. **GraphQL endpoint**: `http://localhost:4002/graphql`
2. **GraphQL IDE**: open `http://localhost:4002` in a browser — Hive
   Router's built-in IDE (Docs/schema explorer, autocomplete, queries,
   mutations, subscriptions). This replaces Spring GraphiQL, which is
   disabled.
3. **Get a dev JWT**:
   ```bash
   curl -s -X POST http://127.0.0.1:4447/internal/token \
     -H 'Content-Type: application/json' \
     -d '{"email":"admin@bytecore.ee","password":"admin-dev-password"}'
   ```
4. In the IDE, open the headers panel and add:
   ```json
   { "Authorization": "Bearer <access_token>" }
   ```
5. Run queries/mutations/subscriptions as usual. The router forwards only
   the `Authorization` header to Spring; Spring remains the sole JWT
   validation and authorization authority — the router does not.
6. **Health/readiness**: `curl http://localhost:4002/health` (liveness),
   `curl http://localhost:4002/readiness` (supergraph loaded — will be
   unhealthy if the local supergraph was never generated).

### Regenerating the local supergraph

The supergraph is a reproducible, locally-composed artifact — no Hive
Cloud account or schema registry is used or required:

```bash
cd infrastructure/hive
./scripts/compose-supergraph.sh        # requires Spring running on :8080
                                        # and oauth-service running (:4447)
docker compose -f ../compose.yml --env-file ../../.env restart hive-router
```

This fetches the `retail` subgraph's SDL (via `{ _service { sdl } }`),
composes it with [Rover](https://www.apollographql.com/docs/rover/) into
Federation v2's `supergraph.graphql`, and reloads the router. Regenerate it
whenever the GraphQL schema changes.

### Smoke-testing Hive Router

```bash
infrastructure/hive/scripts/smoke-test.sh
```

Checks: router reachable, introspection works, unauthenticated query is
denied, authenticated query succeeds, a mutation reaches Spring, and a
validation error propagates with its original message (not masked).

### Troubleshooting Hive Router

| Symptom | Likely cause |
|---|---|
| `readiness` returns non-200 | `supergraph.graphql` missing/stale — run `compose-supergraph.sh` |
| Query returns `SUBREQUEST_HTTP_ERROR` / `Connect` | Check readiness/logs of the Compose `backend` service; Hive and OAuth use `backend:8080`, not a host gateway |
| `401`/`Unexpected error` on every operation | No `Authorization` header set in the IDE, or the dev JWT expired — fetch a new one |
| IDE loads but Docs panel is empty | Router's local `supergraph.graphql` is out of date — regenerate it |

## Development Workflow

### Code style

- **Backend**: Google Java Format via Spotless. Run `./gradlew spotlessApply` before committing.
- **Frontend**: Prettier. Format on save is recommended — configure your editor to use the project's `.prettierrc`.

### Before committing

```bash
# Backend
cd backend && ./gradlew build

# Frontend
cd frontend && bun run lint && bun run build
```

### Environment variables

Copy the committed root `.env.example` to `.env` on first setup. Compose passes
database credentials to Spring and sets internal service URLs; it does not
automatically source `.env` into optional host Gradle processes. Do not commit
real secrets. Existing `.env` files need `HYDRA_PUBLIC_URL=http://hydra:4444` if
that variable is missing. `DB_URL` and `KAFKA_BOOTSTRAP_SERVERS` host values do
not override Spring's explicitly configured Compose addresses.

## Troubleshooting

### Docker isn't running

`make dev` uses Docker Compose to build/start Spring and its dependencies.
Start the Docker daemon and try again; Spring's own Compose auto-management
is disabled.

### Port conflicts

| Port | Service |
|---|---|
| 3000 | Next.js dev/prod server |
| 4002 | Hive Router (GraphQL entry point + IDE) |
| 8080 | Spring backend (loopback-only publication) |
| 5005 | Spring remote debugger (`make debug` only; loopback-only) |
| 5432 | PostgreSQL |
| 9092 | Compose Kafka's host listener |

### GraphQL IDE

Spring/DGS GraphiQL is **disabled** (`dgs.graphql.graphiql.enabled: false` in
`application-dev.yaml`). Use Hive Router's built-in GraphQL IDE instead —
see "GraphQL API access" below.

### Testcontainers issues

If integration tests fail with connection errors, ensure Docker is running and your user has permission to access the Docker socket. On Linux:

```bash
sudo usermod -aG docker $USER
# Log out and back in for the group change to take effect
```

### bun version mismatch

If you see warnings about the bun version, install the exact version
declared in `frontend/package.json`'s `packageManager` field:

```bash
curl -fsSL https://bun.sh/install | bash -s "bun-v1.4.2"
```
## Account deletion

`deleteUser(userId: ...)` remains an ADMIN-only GraphQL mutation requiring
`user:write`. There is no customer self-delete or alternate authorization path.
Spring uses the Kratos and Hydra admin APIs (dev loopback ports 4434 and 4445;
production requires `KRATOS_ADMIN_URL` and `HYDRA_ADMIN_URL`).

Deletion first enumerates Kratos identities and proves the unique
`metadata_admin.spring_user_id` link. Email alone is never sufficient. Missing,
ambiguous, or changed links fail closed and require support to repair the link.
The verified identity UUID is committed as a retry coordinate before external
side effects; it is not a credential. Profile/role changes and ordinary canonical
user lookups are blocked once deletion starts.

Spring then deletes that identity's sessions and the identity itself, and revokes
all Hydra consent/token chains and login sessions for the canonical numeric
subject. No client-wide or unrelated-user revocation is used. Only after both
providers succeed does a database transaction remove addresses, saved payment
methods, carts, and wishlists (including their items), replace username/email/DOB
with anonymous values, reset the role to USER, and mark deletion complete.
Historical orders, order items, monetary values, inventory, and payment outbox
events remain untouched. Their required user FK points to an anonymized,
non-authenticatable tombstone rather than cascading history away.

This is not a distributed ACID transaction. Upstream failure never returns
success. A provider may already have revoked the account when another step fails.
Retry the same mutation/id: the committed coordinate permits an already-absent
identity, repeats subject-scoped revocation safely, and retries the final database
transaction. Do not manually remove the pending user/coordinate. Completed
deletion is idempotent; deleting a never-existing id still reports not found.
Pending deletion deliberately keeps the original unique email/username reserved;
after completion both can be registered again as a new canonical user and identity.
Registration provider conflicts return actionable 409 responses; provider outages
return actionable 502 responses, not an empty authentication failure.

Previously issued self-contained access JWTs can remain cryptographically valid
until their normal expiry (currently 30 minutes); there is no new denylist.
Refresh grants and native sessions are revoked immediately. Existing authorization
and applicable upstream MFA requirements are unchanged.
