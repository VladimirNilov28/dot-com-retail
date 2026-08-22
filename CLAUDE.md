# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

ByteCore is an e-commerce monorepo with two independently-built apps:

- `backend/` — Spring Boot 4 (Java 21), REST + GraphQL, PostgreSQL, Flyway, Kafka.
- `frontend/` — Angular 22 with SSR (Express), Tailwind CSS 4, Vitest.
- `infrastructure/` — Docker Compose, Jenkins, Kubernetes (`kuber/`), Nginx configs.
- `docs/` — API design notes and database schema documentation; check `docs/database/FLYWAY_DB.md` before touching migrations or entities, and `docs/api/api-design.md` for the intended REST surface.

Note: `docs/local-setup.md` describes the backend as reactive (WebFlux/R2DBC); the actual stack (see `backend/build.gradle.kts` header comment) is blocking Web MVC + Spring Data JPA + plain `@KafkaListener`s. Trust the code/build file over that doc.

## Common commands

### Backend (run from `backend/`)

```bash
./gradlew bootRun                    # run the application
./gradlew build                      # full pipeline: spotlessApply, compile, test, jacoco
./gradlew test                       # run all tests
./gradlew test --tests "ee.bytecore.backend.YourTestClass"   # single test class
./gradlew test -Pgroup=unit          # run tests tagged "unit" (also: graphql, integration, e2e; comma-separated)
./gradlew test -Pfull                # full stdout/stack traces in test output
./gradlew jacocoTestReport           # coverage report
./gradlew spotlessApply              # auto-format (Google Java Format; required before commit)
./gradlew spotlessCheck              # format check (runs as part of `check`/CI)
./gradlew dependencyCheckAnalyze     # OWASP dependency CVE scan
```

Formatting via Spotless is ratcheted from `origin/main` — only files changed relative to main get checked/formatted, not the whole codebase.

Postgres for local dev is started automatically by `spring-boot-docker-compose` (or manually: `docker compose -f backend/compose.yaml up -d`). Integration tests use Testcontainers (Postgres + Kafka) via `TestcontainersConfiguration`, imported per-test with `@Import(TestcontainersConfiguration.class)` — no shared state or manual cleanup needed.

### Frontend (run from `frontend/`)

```bash
pnpm install
pnpm start                # dev server at http://localhost:4200
pnpm build                # production build
pnpm test                 # Vitest unit tests
pnpm watch                # watch build, development config
pnpm serve:ssr:frontend   # serve SSR build on http://localhost:4000
```

Use pnpm `11.11.0` exactly (`corepack prepare pnpm@11.11.0 --activate`).

## Architecture

### Backend package layout (`ee.bytecore.backend`)

- `entities/<domain>/` — JPA entities grouped by domain (`cart`, `category`, `inventory`, `payment`, `product`, `user`, `wishlist`).
- `repositories/<domain>/` — Spring Data JPA repositories, mirroring the entity package structure.
- `enums/` — `UserRole`, `PaymentStatus`, `OrderStatus`.
- `graphql/datafetchers/` — Netflix DGS `@DgsComponent` data fetchers, one per domain (e.g. `UserDataFetcher`).
- `graphql/scalars/` — custom scalar wiring (`GraphQLConfig` registers `Url`, `UUID`, `GraphQLBigDecimal`, `Json` via `graphql-java-extended-scalars`; `LocalDateScalar`/`InstantScalar` are hand-rolled).
- `src/main/resources/schema/` — GraphQL SDL: `types/<domain>.graphqls` for object types, `ops/{queries,mutations,subscriptions}.graphqls` for the root operations, `scalars.graphqls` for scalar declarations.

### Database / schema ownership

Flyway is the single source of truth for schema (`src/main/resources/db/migration/V{n}__*.sql`); JPA runs with `ddl-auto: validate` and never generates/alters schema. **Currently in dev mode**: existing migration files may still be edited in place (DB is recreated from scratch each time). Once staging/production exists, migrations become append-only — new schema changes require a new `V{n}__...sql` file, not edits to existing ones.

Migration dependency order (see `docs/database/FLYWAY_DB.md` for full details and the ER diagram):

```
V0 init_functions (set_updated_at trigger)
V1 users, user_address, user_payment_methods         (depends on V0)
V2 products, product_variants                        (depends on V0)
V3 carts, cart_items                                 (depends on V1, V2)
V4 orders, order_items, payment_details              (depends on V1, V2, V3)
V5 categories, product_categories                    (depends on V2)
V6 warehouses, inventory                             (depends on V2)
V7 wishlists, wishlist_items                         (depends on V1, V2)
```

Key domain design decisions worth knowing before modifying entities/migrations:

- **Stock lives only in `inventory`**, keyed `(product_variant_id, warehouse_id)` — never re-add a `stock_quantity` column to `product_variants`; total stock is the sum across warehouses.
- **`carts.user_id` and `orders.cart_id` are intentionally non-unique** — a user can have multiple carts over their lifetime. A prior version made these unique/1:1, which incorrectly limited a user to one order ever; don't reintroduce that constraint.
- **`order_items` snapshots price/quantity at checkout** (`price_at_purchase`); later `product_variants` price changes must never retroactively affect past orders. `orders.total_amount` is stored directly, not derived via a join/sum.
- **Native Postgres enums** (`user_role`, `payment_status`, `order_status`) don't map cleanly to Hibernate's default `@Enumerated(STRING)` (which expects `varchar`) — this will fail `ddl-auto: validate` unless mapped with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, or the column is changed to `varchar` + `CHECK` constraint. Check current entity mapping before assuming which approach is in use.
- `product_variants.attributes` is unvalidated `jsonb` for free-form, category-specific specs (color, size, etc).
- `categories` is self-referencing (`parent_id`) for arbitrary-depth sub-categories.

### GraphQL vs REST

The backend exposes both. `docs/api/api-design.md` sketches an intended REST surface (`/api/carts`, `/api/products`, `/api/orders`, etc., all read-only `GET` endpoints as currently documented) — no REST controllers exist yet in `src/main`. GraphQL is implemented via Netflix DGS: schema-first (`.graphqls` files), with data fetchers annotated `@DgsComponent`. When adding a new domain's GraphQL support, follow the existing pattern: SDL type in `schema/types/`, operations in `schema/ops/`, fetcher class in `graphql/datafetchers/`.

### Testing conventions

- Unit/GraphQL tests: `src/test/java/.../graphql/datafetchers/*Test.java`.
- Schema/migration behavior tests: `src/test/java/.../migration/*Test.java` (e.g. `CascadeDeleteTest`, `ForeignKeyViolationTest`, `TriggerBehaviorTest`) — these exercise real Postgres behavior via Testcontainers, not mocks.
- `TestBackendApplication` is the test-only Spring Boot entry point that wires in `TestcontainersConfiguration`.

`backend/src/main/java/ee/bytecore/backend/services/` exists but is currently empty — do not populate it speculatively (see "No speculative architecture" below).

---

## Development philosophy (read before any code change)

This project is built with incremental, strict Red → Green → Refactor TDD. **A request to write tests is not permission to implement the behavior under test.** Each phase below is a separate, explicit step the user asks for — never chain them automatically.

- **"Write tests"** = modify test code only, unless the user explicitly also asks for implementation.
- **"Implement"** = modify production code only as necessary to satisfy an existing, already-written test/requirement. Do not also touch unrelated tests.
- **"Refactor"** = behavior-preserving structural change; tests must already be green before starting, and must stay green throughout.
- Never automatically advance from one phase to the next. Stop and report when the requested phase is done, even if the "obvious next step" seems clear.

### RED

Allowed: inspect production code/schema/contracts, write or modify tests, add test fixtures under `src/test`, run the relevant tests, report why they fail.

Forbidden: touching `src/main`, creating the production implementation, changing production code just to make compilation pass, preemptively adding services/abstractions.

If production code doesn't compile because an API doesn't exist yet, report the blocking gap instead of silently stubbing it — unless the user explicitly asked for a stub-first style.

Stop when: the test fails for the expected/understood reason. Report and wait.

### GREEN

Only after the user explicitly asks to implement/make it pass. Implement the minimum production change needed, prefer existing abstractions over new ones, run the narrowest relevant test, stop once it passes. Do not broaden scope or refactor unrelated code in the same step.

### REFACTOR

Only after explicit request. Tests must be green first and stay green. Improve structure without adding features or new capabilities; service extraction belongs here, not earlier. Don't rewrite passing tests just because the refactor makes them feel outdated.

## No speculative architecture

Business logic is intentionally allowed to start inside a controller or a DGS data fetcher and get extracted into a service later, at the user's explicit request. Until then:

- Controller/DataFetcher → Repository directly is fine and expected during early development.
- Do not create `UserService`, `ProductService`, etc. "because it's cleaner" — only when explicitly requested or during an explicit refactor step.
- Do not create DTOs, mapper layers, factories, or interfaces solely to make mocking easier.
- Do not add patterns, layers, or abstractions for hypothetical future requirements.

Existing project structure and explicit user intent outweigh generic "best practice" advice.

## GraphQL / Netflix DGS conventions

Data fetchers resolve **GraphQL fields**, not database entities. A root fetcher resolves `Query.user`, `Query.product`, `Mutation.createUser`, etc.; nested fields use `@DgsData(parentType = "User", field = "addresses")`.

- Do not add a top-level query just because a JPA entity or repository exists (e.g. no `userAddress(id: ...)` root query when it belongs under `user(id) { addresses { ... } }`).
- Fields resolvable via plain getters on the parent object need no dedicated data fetcher.
- Only add a nested-field data fetcher when it does real work (custom loading, computed values, cross-aggregate lookups).
- Don't add `DataLoader` preemptively for every relation — only when an actual N+1 pattern is identified.

## Test strategy by layer

- **Pure unit tests**: JUnit 5 + Mockito (`@Mock`, `@InjectMocks`), no Spring context unless needed.
- **DGS data fetcher execution tests**: `@SpringBootTest(classes = {...})` + `@EnableDgsTest` + `DgsQueryExecutor`; use `@MockitoBean` for dependencies that must exist as Spring beans.
- **GraphQL HTTP tests**: `@EnableDgsMockMvcTest` + `@AutoConfigureHttpGraphQlTester` + `HttpGraphQlTester`. Raw `MockMvc` for GraphQL only when testing low-level HTTP behavior itself. `@WebMvcTest` is not the default tool for DGS fetchers.
- **REST controllers** (once they exist): `@WebMvcTest` + `MockMvc` + `@MockitoBean`.
- **Full integration**: `@SpringBootTest` with Testcontainers (Postgres/Kafka) when real infrastructure behavior matters (migrations, real queries, cascade/trigger behavior) — not for ordinary unit tests.

## Mockito conventions

`@Mock` = a plain Mockito mock outside the Spring context. `@MockitoBean` = replaces/creates a bean inside the Spring TestContext. Repositories may be mocked in controller/data-fetcher/service tests when the repository itself isn't under test; repository behavior itself is tested separately against a real database (Testcontainers) when needed. Don't reach for Testcontainers in a simple unit test.

## JPA entity conventions

Inspect the actual entity before assuming its shape. In general: protected no-args constructors exist for Hibernate — don't widen them to `public` just to make a test easier; prefer test fixtures/builders instead. Don't weaken encapsulation for test convenience. Be careful with generated IDs/timestamps in assertions. Full entity equality is not a safe substitute for asserting a partial GraphQL response.

## GraphQL assertions

Assert the GraphQL contract, not the entity. If a query selects `user { username email }`, assert `user.username` / `user.email` via the GraphQL tester response — don't deserialize into and compare full JPA entities. Only introduce response DTOs/records when they genuinely improve readability, not automatically.

## Test tags

`./gradlew test -Pgroup=unit` already supports comma-separated JUnit tag filtering (also: `graphql`, `integration`, `e2e`), per `backend/build.gradle.kts`. No tests currently carry these tags — don't mass-edit existing tests to add them as part of an unrelated task; only tag tests you are already touching for a real reason.

## Minimal diff rule

Before editing, identify exactly which files are necessary and touch only those. Do not: reformat unrelated files, rename unrelated classes, reorganize packages, rewrite imports project-wide, bump dependencies, change build config for convenience, or do opportunistic cleanup outside the requested scope.

## Existing tests are requirements

Don't modify a test merely because production code currently fails it. On a failing test: confirm it represents intended behavior, check the schema/contract it's based on, then fix production code (during an explicit GREEN step). Only change the test itself if the user explicitly asks or the test is demonstrably wrong. Never weaken assertions, delete a failing test, or loosen assertions just to get to green.

## User controls scope — examples

- "Write a test for `Query.user`" → inspect schema/code, write the test, run it, stop after RED. Do not implement `Query.user`, add a service, add DTOs, or touch migrations.
- "Now make it pass" → move to GREEN: implement the minimum, run tests, stop when green.
- "Refactor this into a service" → move logic into a service, preserve behavior, run tests, avoid adding extra layers beyond what was asked.

See `.claude/skills/tdd/SKILL.md` for the detailed phase workflow and reporting format.

**Enforcement note:** there is no hook blocking edits to `src/main` during RED — reliably detecting "we are currently in a RED task" from conversation state would require fragile heuristics. This is enforced by instruction only. If this needs to be hardened later, a plausible starting point is a `PreToolUse` hook on `Edit`/`Write` that rejects paths under `backend/src/main/**` when a project-local marker file (e.g. `.claude/state/red.lock`, written/removed explicitly by the user or by a command) is present — but don't add this speculatively.