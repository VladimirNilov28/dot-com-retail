---
name: tdd
description: Perform development tasks using strict Red-Green-Refactor TDD while preserving user control over phase transitions. Use for any backend (Spring Boot / GraphQL DGS) task involving writing tests, implementing behavior, or refactoring — writing a test is never permission to also implement it.
---

# TDD state machine

Every task is in exactly one conceptual state:

- **TEST_DESIGN** — discussing/planning test scenarios, no code written yet.
- **RED** — writing a failing test.
- **GREEN** — making a failing test pass with the minimum production change.
- **REFACTOR** — restructuring while behavior and tests stay green.

Infer the state from the user's phrasing:

| Phrase | State |
|---|---|
| "Write tests for...", "create a failing test..." | RED |
| "Make this test pass...", "implement the behavior described by these tests..." | GREEN |
| "Refactor...", "extract this into a service..." | REFACTOR |
| "What should we test for...", "let's think through the scenarios..." | TEST_DESIGN |

If ambiguous, pick the **least invasive** interpretation: inspect, propose, write tests if asked — but do not touch `src/main`. Ambiguity is never a license to do more work; it's a reason to do less and ask or stop sooner.

## TEST_DESIGN

Allowed: inspect GraphQL schema/contracts, inspect existing tests, propose test scenarios, identify edge cases/boundaries, discuss structure.

Not allowed: writing production code. Do not write test code either unless the user asks you to move into RED — TEST_DESIGN is for discussion/proposals only.

## RED

1. Identify the externally observable behavior to test (a GraphQL field/operation, a method contract, a repository query — not an implementation detail).
2. Inspect the relevant contract: `.graphqls` schema, existing entity/repository, existing tests for the same domain.
3. Write the smallest test that expresses the behavior.
4. Mock only the true dependencies of the unit/slice under test (see Mockito conventions in `CLAUDE.md`).
5. Run the narrowest test command that covers it, e.g. `./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest"`.
6. Confirm the test fails for the expected reason (not a compile error hiding the real gap).
7. Report the failure reason.
8. STOP — do not implement.

**Hard rule: never modify `src/main` during RED.** If the test can't even compile because a type/method doesn't exist yet, report that blocking gap explicitly rather than silently adding a stub — unless the user's request already asked for stub-first style.

## GREEN

Enter only when the user explicitly asks to implement / make it pass.

1. Read the failing test to understand exactly what's required.
2. Identify the minimum production change that satisfies it.
3. Implement only that — prefer existing abstractions (e.g. call the repository directly from the data fetcher/controller if that's the existing pattern; do not introduce a service layer to do it "properly").
4. Run the narrowest relevant test; once green, optionally run closely related tests in the same class/package.
5. STOP.

Do not: optimize prematurely, refactor unrelated code, add abstractions "while you're in there", or implement more than the test requires.

## REFACTOR

Enter only when the user explicitly asks for a refactor.

1. Confirm tests are currently green before changing anything.
2. Preserve all public/observable behavior.
3. Make incremental changes, running tests after each meaningful step.
4. Service/DTO/mapper extraction happens here if the user asked for it — not in RED or GREEN.
5. Do not rewrite passing tests just because the internal structure changed; only touch a test if the refactor changed its actual public contract.
6. Stop once the requested refactor scope is done — do not keep going into further cleanup.

# GraphQL-specific TDD

- The GraphQL schema (`backend/src/main/resources/schema/**/*.graphqls`) is the API contract — write tests around operations/fields as declared there, not around the database shape.
- Distinguish root operation fields (`Query.x`, `Mutation.x`) from nested field resolvers (`@DgsData(parentType = ..., field = ...)`).
- Do not assume every entity needs a root query, a data fetcher, a DTO, or a mapper — model GraphQL around actual requested behavior, not 1:1 with JPA entities.
- Test only the selection set the query actually requests; don't assert fields the test didn't ask for.
- For a DGS data fetcher test, the data fetcher is the unit under test — mock its repository/service dependencies. Don't invent a service dependency just so there's something to mock.
- Use `@MockitoBean` when the mocked dependency must be a real Spring bean (e.g. `@EnableDgsTest` + `DgsQueryExecutor` context); use plain Mockito (`@Mock`/`@InjectMocks`) when no Spring context is needed.
- Be aware of N+1 patterns on nested relations, but only introduce `DataLoader` when an access pattern actually demonstrates the problem — not preemptively for every relation.

# Spring MVC-specific TDD

For REST controllers (none exist yet in `src/main`, per `docs/api/api-design.md`): prefer `@WebMvcTest` + `MockMvc`, mock dependencies with `@MockitoBean`, and test route, method, validation, status code, and response contract. Don't boot the full application context without a reason.

# Integration testing

Use Testcontainers (already wired via `TestcontainersConfiguration`, `@Import`ed per test) for behavior that genuinely needs real infrastructure: JPA mapping/schema validation, Flyway migrations, real repository queries, cascade/trigger behavior, Kafka. Do not reach for Testcontainers in an ordinary data-fetcher/controller/service unit test — mock the dependency instead.

# Security testing

`@WithMockUser` assumes authentication already happened and is for authorization testing only — it does not exercise JWT parsing, login, OAuth flow, or authentication filters. Testing the actual authentication mechanism requires a real integration test against that mechanism.

# Reporting

After every change, report:

- current TDD phase
- files changed (and why)
- test command(s) executed and their result
- whether production code was modified
- what remains intentionally unimplemented, and what phase/request would unlock it

Example:

```
Current phase: RED

Changed:
- backend/src/test/java/ee/bytecore/backend/graphql/datafetchers/UserDataFetcherTest.java

Executed:
- ./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest"

Result:
- FAILS as expected: Query.user currently returns null (no resolver implemented).

Production code modified: No.

Stopping here until GREEN is explicitly requested.
```

# Critical rules (do not violate)

1. Never implement production code unless explicitly requested.
2. A request to write tests is not permission to implement the tested behavior.
3. Never automatically advance from RED to GREEN.
4. During RED, do not modify `src/main`.
5. Prefer the smallest possible diff.
6. Do not introduce architecture preemptively.
7. Do not create a service layer merely because it would make the architecture "cleaner."
8. Business logic may intentionally begin in controllers or DGS data fetchers and be extracted later, on request.
9. GraphQL data fetchers correspond to GraphQL fields, not database entities.
10. Do not create a top-level GraphQL operation for every entity/repository.
11. Do not modify existing tests merely to make an implementation pass.
12. Testcontainers are for integration behavior, not ordinary unit tests.
13. Use `@MockitoBean` when replacing a dependency inside a Spring test context.
14. Use ordinary Mockito for pure unit tests.
15. Stop after completing the TDD phase the user requested.
16. Never broaden scope without explicit instruction.
