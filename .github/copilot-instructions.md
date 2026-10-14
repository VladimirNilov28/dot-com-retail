# Copilot instructions for dot-com-retail

Monorepo: `backend/` (Spring Boot 4 GraphQL API, the only implemented part today) and
`frontend/` (Next.js scaffold, not yet built out — see `frontend/AGENTS.md`).
`infrastructure/` holds Docker Compose, Hive Router (GraphQL gateway), and Ory
Hydra/Kratos (OAuth2). Root `README.md`/`CLAUDE.md` are the same file and describe
the full local dev stack in detail — read them for environment/infra questions.

## Development philosophy — read before touching any code

This project is developed with **strict, explicit Red → Green → Refactor TDD**,
enforced by convention (no tooling blocks it). The full state machine lives in
`.claude/skills/tdd/SKILL.md` and is mirrored in `CLAUDE.md`. The critical rule:

- **"Write tests" is never permission to implement the behavior under test.**
  Only touch `src/test` during RED; only touch `src/main` once the user explicitly
  asks to implement (GREEN); only restructure without changing behavior when
  explicitly asked to refactor.
- Never chain phases automatically (write a test → immediately implement it).
- When in doubt about scope, do less, not more — ask rather than assume.
- Don't run the test suite or compile as a reflex after every edit; only when the
  user asks or when it's the only way to resolve genuine uncertainty. Give the
  narrowest verification command instead (see below).
- Don't add services, DTOs, mappers, or other layers preemptively "for cleanliness."
  See "Architecture" below for what's already been extracted vs. what's
  intentionally still inline.

## Build, test, lint

All commands run from `backend/` (or via `make` targets from the repo root).

```bash
./gradlew bootRun                                   # run the app (also: make dev, from repo root)
./gradlew test                                      # full backend test suite
./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest"   # one class
./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest.shouldNotExposeCreateUserMutationTest"  # one method
./gradlew test -Pgroup=unit                         # by JUnit tag: unit | graphql | integration | e2e (comma-separated)
./gradlew jacocoTestReport                           # coverage
./gradlew spotlessApply / spotlessCheck              # format / format-check
./gradlew build                                      # full build (format, compile, test, coverage, static analysis)
```

`make` wraps these plus the Docker infra (`make dev`, `make doctor`, `make down`, `make seed-test-data`, etc.) —
run `make help` for the full list. `make dev` starts Docker infra and expects Spring to be run separately (from your IDE, or a second terminal via `./gradlew bootRun`); Spring is never containerized in dev.

Frontend (`frontend/`, minimal scaffold today): `npm run dev|build|start|lint`.

## Architecture (backend)

- **GraphQL via Netflix DGS**, package `ee.bytecore.backend.graphql`. This is the
  Federation-compatible `retail` subgraph — the *only* subgraph. `infrastructure/hive`
  (Hive Router, `:4002`) is the sole external GraphQL entry point in dev; it proxies
  to Spring on `:8080` but takes no part in auth — Spring Resource Server is the sole
  JWT/authorization authority. Spring/DGS's own GraphiQL is disabled; use the Hive
  Router IDE at `localhost:4002` instead.
- **Layering, per domain** (User, Category, Product/ProductVariant, Cart, Wishlist,
  Warehouse/Inventory, Order): `DataFetcher` → `XService` (`@Service`) → JPA
  `Repository`, plus a static `XMapper` (entity → generated GraphQL type) called
  from the DataFetcher. This extraction is *already done* for every domain above —
  reuse it, don't re-derive it. **`Payment` is the one domain without this
  structure** (`PaymentQuery`/`PaymentMutation` have no mutations implemented yet) —
  deliberately deferred; don't add a `PaymentService` speculatively.
- For any *other* still-unbuilt domain, DataFetcher → Repository directly is
  expected and fine; don't add a service/DTO/mapper layer "to be consistent"
  until asked.
- **DataFetchers resolve GraphQL fields, not entities.** Don't add a top-level
  query/mutation just because a repository exists (e.g. no `Mutation.createUser` —
  intentionally removed; user creation happens via internal provisioning, not
  public GraphQL). Add a nested `@DgsData(parentType = ..., field = ...)` fetcher
  only when it does real work (custom loading, computed values, cross-aggregate
  lookups) — plain-getter fields need nothing.
- **DataLoaders** (`graphql/dataloaders`, `MappedBatchLoader` + a `findAllByXIdIn`
  repo query) exist only for genuine many-parents access patterns:
  `variantsByProductId`, `categoriesByProductId`, `addressesByUserId`,
  `paymentMethodsByUserId`. Item-level lookups inside a single cart/wishlist/order
  (`CartItem`/`WishlistItem`/`OrderItem.productVariant`, `Warehouse`/
  `ProductVariant.inventory`) are deliberately *not* batched — don't add a
  DataLoader without a demonstrated N+1.
- **Schema-owned-by-Flyway**: JPA uses `ddl-auto=validate`; migrations live in
  `backend/src/main/resources/db/migration` (see `docs/database/FLYWAY_DB.md`).
  GraphQL schema files live under `backend/src/main/resources/schema/<domain>/`
  (see `docs/api/graphql-schema.md`).
- **Security**: role-based `@PreAuthorize` on the DataFetcher's
  `@DgsMutation`/`@DgsQuery` method (e.g. `hasRole('ADMIN')`,
  `hasAnyRole('CATALOG_MANAGER','ADMIN')`) — not on the service. Current-user id
  comes from `CurrentUserProvider#getCurrentUserId()` (resolves JWT `sub`, returns
  `null` rather than throwing on a non-numeric subject); DataFetchers call it and
  pass the id into the service. Ownership checks (`!Objects.equals(currentUserId,
  ownerId)` → `AccessDeniedException`) live in services, using `Objects.equals`
  (not `.equals()`) since either side may be `null` in tests.
- **Messaging**: plain `@KafkaListener` (`spring-kafka`, not reactor-kafka). The
  future Payment Service integrates asynchronously via Kafka
  (`PaymentEventPublisher` seam, not yet wired) and is not a Hive subgraph.

## Test conventions

- Pure unit tests: JUnit 5 + Mockito (`@Mock`/`@InjectMocks`), no Spring context.
- DGS data fetcher tests: `@SpringBootTest(classes = {...})` + `@EnableDgsTest` +
  `DgsQueryExecutor`, `@MockitoBean` for in-context dependencies.
- GraphQL HTTP tests: `@EnableDgsMockMvcTest` + `@AutoConfigureHttpGraphQlTester` +
  `HttpGraphQlTester`.
- Testing `@PreAuthorize` requires importing the real `SecurityConfig` (so
  `@EnableMethodSecurity` is active) and a real JWT via `Jwt.withTokenValue(...)` +
  `SecurityMockMvcRequestPostProcessors.authentication(...)` — see
  `UserMutationAuthorizationTest`, `CatalogMutationAuthorizationTest`,
  `InventoryMutationAuthorizationTest`, `OrderMutationAuthorizationTest`. Bare
  `@WithMockUser` does not survive the real filter chain once `SecurityConfig` is
  imported.
- Full integration tests (`@SpringBootTest` + Testcontainers) only for real
  infra behavior: migrations, real repository queries, Postgres constraints/
  cascades/triggers, Kafka.
- Assert the GraphQL response shape actually selected in the query (e.g.
  `user.addresses[0].firstName`), not the underlying JPA entity.
- JUnit tags (`unit`/`graphql`/`integration`/`e2e`) drive `-Pgroup=...` filtering;
  don't mass-add tags to existing tests outside the change you're making.

## Minimal-diff discipline

Touch only the files required for the requested change. Don't reformat unrelated
files, rename unrelated classes, reorganize packages, or fix unrelated warnings.
Don't run `./gradlew test`/`build` automatically after every edit — provide the
narrowest verification command and let the user run it unless execution is
explicitly requested or required to resolve real uncertainty.
