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

If ambiguous, pick the **least invasive** interpretation: inspect, propose, write tests if asked — but do not touch `src/main`.

Ambiguity is never a license to do more work; it's a reason to do less and ask or stop sooner.

# TEST_DESIGN

Allowed:

- inspect GraphQL schema/contracts;
- inspect existing tests;
- propose test scenarios;
- identify edge cases/boundaries;
- discuss test structure.

Not allowed:

- writing production code;
- writing test code unless the user explicitly asks to move into RED.

TEST_DESIGN is for discussion and proposals only.

# RED

1. Identify the externally observable behavior to test:
    - a GraphQL field/operation;
    - a method contract;
    - a repository query;
    - another public behavior;
    - not an implementation detail.

2. Inspect only the relevant contract:
    - `.graphqls` schema;
    - existing entity/repository when required;
    - existing tests for the same domain when they establish a needed convention.

3. Write the smallest test that expresses the behavior.

4. Mock only the true dependencies of the unit/slice under test. Follow Mockito conventions in `CLAUDE.md`.

5. Decide whether test execution is necessary:
    - if the user explicitly requested execution or verification, run the narrowest relevant test;
    - if observing the failure is necessary to proceed, run the narrowest relevant test;
    - otherwise, do not run it and provide the verification command to the user.

6. If the test is executed, confirm it fails for the expected reason rather than an unrelated compile/configuration failure.

7. If the test is not executed, do not invent or speculate about the exact runtime failure. State only the expected RED condition when it follows directly from the current code.

8. STOP — do not implement.

**Hard rule: never modify `src/main` during RED.**

If the test cannot compile because a required type/method does not exist yet, report that blocking gap explicitly rather than silently adding a stub — unless the user's request explicitly asked for stub-first style.

# GREEN

Enter GREEN only when the user explicitly asks to implement the behavior or make the failing test pass.

1. Read the failing test to understand exactly what is required.

2. Identify the minimum production change that satisfies it.

3. Implement only that.

4. Prefer existing abstractions and patterns:
    - if the existing code calls a repository directly from a data fetcher/controller, keep that pattern;
    - do not introduce a service layer merely to make the architecture "cleaner";
    - do not introduce DTOs, mappers, factories, abstractions, or helpers unless required by the requested behavior.

5. Decide whether test execution is necessary:
    - run the narrowest relevant test when explicitly requested;
    - run it when runtime/compilation behavior is genuinely uncertain and the result is needed to validate the implementation;
    - otherwise provide the narrowest verification command and leave execution to the user.

6. If execution is necessary, prefer:
    - one test method;
    - then one test class;
    - then closely related tests;
    - never the entire suite unless genuinely required.

7. STOP once the requested behavior is implemented.

Do not:

- optimize prematurely;
- refactor unrelated code;
- add abstractions "while you're in there";
- implement more than the test requires;
- broaden scope;
- automatically continue into REFACTOR.

# REFACTOR

Enter only when the user explicitly asks for a refactor.

1. Determine whether current test status is already known.

2. If verification is necessary before refactoring, run the narrowest relevant test. Do not automatically run tests merely as ceremony when the current green state is already known and the requested refactor is mechanically safe.

3. Preserve all public/observable behavior.

4. Make incremental changes.

5. Run tests after a meaningful step only when:
    - the change could realistically alter behavior;
    - execution is required to resolve uncertainty;
    - the user requested verification.

6. Service/DTO/mapper extraction happens here if the user asked for it — not automatically during RED or GREEN.

7. Do not rewrite passing tests merely because internal structure changed. Only modify a test if the actual public contract changed.

8. Stop once the requested refactor scope is complete.

Do not continue into unrelated cleanup.

# Execution and token efficiency

Tool usage, test execution, reasoning, and repository inspection must be proportional to the uncertainty and complexity of the requested change.

Correctness takes priority over token efficiency, but every additional tool call must have a concrete purpose.

## User-driven verification by default

The user is responsible for running tests locally unless execution is genuinely necessary to complete the requested task.

Do not run tests merely because:

- a test file was modified;
- the task is conceptually in RED;
- the task is conceptually in GREEN;
- TDD normally includes executing tests;
- verification would be "nice to have."

Run tests only when at least one of the following is true:

- the user explicitly asks to run or verify them;
- test output is required to diagnose an existing failure;
- runtime behavior cannot be determined reliably from available context;
- compilation behavior is genuinely uncertain and blocks the requested change;
- the result is necessary to choose the next implementation step.

When test execution is necessary:

- run the narrowest possible scope;
- prefer one test method over one class when practical;
- prefer one test class over a package;
- prefer one package over the full suite;
- never run the full suite unless the requested task genuinely requires it;
- do not rerun an unchanged test unless its previous result provides information required for the next change.

When execution is unnecessary, provide the exact command the user can run instead.

## Mechanical changes

Do not perform a full RED-GREEN-REFACTOR ceremony for mechanical or locally verifiable edits.

Examples include:

- completing existing `GraphQlTester` assertions;
- adding assertions for fields already present in a GraphQL selection set;
- correcting GraphQL response paths such as `addresses` to `addresses[0]`;
- completing obvious Mockito `when(...).thenReturn(...)` stubbing;
- extending an already established assertion chain;
- adding straightforward mappings;
- fixing selectors;
- fixing imports;
- trivial generic/type corrections;
- formatting;
- renaming;
- trivial syntax corrections;
- repetitive changes that follow an already established pattern.

For these tasks:

1. Read only the minimum context required.
2. Make the smallest correct edit.
3. Do not inspect unrelated production code.
4. Do not run tests unless explicitly requested or necessary to resolve real uncertainty.
5. Do not load additional documentation or inspect additional repository files unless required.
6. Do not perform architecture analysis.
7. Do not search for alternative implementations when the existing pattern is already clear.
8. Stop immediately after the requested edit is complete.

A mechanical test edit may still conceptually belong to RED, but RED does not require executing the test when the requested change can be verified statically.

## Repository inspection budget

Do not explore the repository speculatively.

Before reading another file, determine whether its contents could materially change the requested edit.

If not, do not read it.

Prefer inspection in this order:

1. the file explicitly named by the user;
2. directly referenced types/contracts only when required;
3. nearby tests only when they establish an unknown convention;
4. broader repository search only when required information cannot otherwise be obtained.

Do not:

- reread files whose relevant contents are already available;
- inspect production implementations merely to confirm obvious Java/Spring behavior;
- search for additional abstractions when the existing code establishes the pattern;
- inspect unrelated packages;
- investigate architecture during a local test edit;
- turn a single-file change into repository-wide analysis.

## Reasoning budget

Prefer direct implementation over exhaustive analysis for small and obvious tasks.

If the requested change follows an established pattern, apply that pattern directly.

Do not spend significant reasoning or tool calls proving facts that are already evident from:

- Java types;
- method signatures;
- generic types;
- GraphQL schema types;
- existing mocks;
- existing assertions;
- immediately surrounding code;
- an already established repository convention.

Do not investigate hypothetical edge cases that are outside the requested behavior.

Do not propose architecture changes unless the task requires architecture decisions.

## Compilation

Compilation is not mandatory after every edit.

Compilation may be used when:

- a method signature or generic type is uncertain;
- generated GraphQL/DGS types may affect compilation;
- an API usage cannot be verified reliably from existing code;
- compilation failure is the problem being investigated;
- the user explicitly requests verification.

Do not run Gradle merely to validate:

- repetitive assertions;
- obvious method chaining;
- formatting;
- imports that are clearly available;
- straightforward Mockito stubbing;
- trivial syntax that can be checked statically.

# GraphQL-specific TDD

- The GraphQL schema (`backend/src/main/resources/schema/**/*.graphqls`) is the API contract.
- Write tests around operations and fields declared in the GraphQL contract, not around the database shape.
- Distinguish root operation fields (`Query.x`, `Mutation.x`) from nested field resolvers (`@DgsData(parentType = ..., field = ...)`).
- Do not assume every entity needs:
    - a root query;
    - a data fetcher;
    - a DTO;
    - a mapper.
- Model GraphQL around actual requested behavior, not 1:1 with JPA entities.
- Test only the selection set the query actually requests.
- Do not assert fields the GraphQL test did not request.
- For a DGS data fetcher test, the data fetcher is the unit under test.
- Mock its actual repository/service dependencies.
- Do not invent a service dependency merely so there is something to mock.
- Use `@MockitoBean` when the mocked dependency must replace a real Spring bean, for example with `@EnableDgsTest` + `DgsQueryExecutor`.
- Use plain Mockito (`@Mock`, `@InjectMocks`) when no Spring context is required.
- Be aware of N+1 patterns on nested relations.
- Introduce `DataLoader` only when an actual access pattern demonstrates the need — not preemptively for every relation.

# Spring MVC-specific TDD

For REST controllers (none currently exist in `src/main`, per `docs/api/api-design.md`):

- prefer `@WebMvcTest` + `MockMvc`;
- mock dependencies with `@MockitoBean`;
- test route;
- test HTTP method;
- test validation;
- test status code;
- test response contract.

Do not boot the full application context without a concrete reason.

# Integration testing

Use Testcontainers for behavior that genuinely requires real infrastructure.

Examples:

- JPA mappings;
- schema validation;
- Flyway migrations;
- real repository queries;
- database constraints;
- cascade behavior;
- triggers;
- PostgreSQL-specific behavior;
- Kafka integration.

`TestcontainersConfiguration` is already available and can be `@Import`ed where appropriate.

Do not use Testcontainers for ordinary:

- data fetcher unit tests;
- controller unit/slice tests;
- service unit tests;
- Mockito-based behavior tests.

Mock the dependency instead.

Do not start containers merely to prove behavior already represented by a mock.

# Repository testing

Distinguish between mocking a repository and testing a repository.

For example:

```java
List<UserAddress> findAllByUserId(UUID userId);
```

In a unit/data-fetcher test:

```java
when(userAddressRepository.findAllByUserId(userId))
    .thenReturn(List.of(userAddress));
```

This tests how the unit under test behaves when the repository returns that value.

It does **not** test whether Spring Data JPA correctly executes `findAllByUserId`.

Testing the actual repository query belongs in an integration test with the real JPA/database infrastructure.

Do not introduce database infrastructure into a unit test merely to verify a mocked repository.

# Security testing

`@WithMockUser` assumes authentication has already happened.

Use it for authorization testing only.

It does not test:

- JWT parsing;
- login;
- OAuth flow;
- authentication filters;
- token validation;
- token refresh.

Testing the actual authentication mechanism requires an integration test against that mechanism.

Do not mistake authorization tests using `@WithMockUser` for authentication tests.

# Reporting

Keep reporting proportional to the size and complexity of the requested change.

For ordinary TDD work, report:

- current TDD phase;
- files changed;
- whether tests were executed;
- test result if executed;
- whether production code was modified;
- what remains intentionally unimplemented when applicable.

If tests were intentionally not executed, say so briefly.

Provide the narrowest verification command when useful.

Do not produce a verbose TDD status report for a trivial or mechanical edit.

## Example: test executed

```text
Current phase: RED

Changed:
- backend/src/test/java/ee/bytecore/backend/graphql/datafetchers/UserDataFetcherTest.java

Executed:
- ./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest"

Result:
- FAILS as expected: Query.user currently returns null.

Production code modified: No.

Stopping here until GREEN is explicitly requested.
```

## Example: mechanical edit without execution

```text
Current phase: RED

Changed:
- UserDataFetcherTest.java — completed GraphQlTester assertions for addresses[0].

Tests not executed; the requested change was mechanical and locally verifiable.

To verify:
./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest"

Production code modified: No.
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
17. Do not run tests automatically for mechanical or statically verifiable changes.
18. Test execution must resolve uncertainty or satisfy an explicit user request; it is not a ritual.
19. Read only the files required to perform the requested change.
20. Do not perform speculative repository exploration.
21. Do not reread unchanged context unnecessarily.
22. For repetitive edits with an established pattern, apply the pattern directly and stop.
23. Keep tool calls and reasoning proportional to task complexity.
24. Prefer user-driven test execution when the result is not required to make the requested edit.
25. Never run the full test suite when a narrower command can answer the same question.
26. Do not compile automatically after trivial changes.
27. Do not load unrelated documentation or inspect unrelated packages.
28. Do not investigate hypothetical improvements outside the requested behavior.
29. Do not use tool calls merely to increase confidence in facts already evident from the available code.
30. Correctness takes priority over token efficiency, but every additional tool call must have a concrete purpose.