## Development philosophy (read before any code change)

This project is built with incremental, strict Red → Green → Refactor TDD. **A request to write tests is not permission to implement the behavior under test.** Each phase is a separate, explicit step controlled by the user — never chain phases automatically.

- **"Write tests"** = modify test code only, unless the user explicitly also asks for implementation.
- **"Implement"** = modify production code only as necessary to satisfy an existing, already-written test/requirement.
- **"Refactor"** = behavior-preserving structural change; do not add new behavior.
- Never automatically advance from one phase to the next.
- Ambiguity is a reason to do less, not more.
- Prefer the smallest possible change that satisfies the user's request.

### RED

Allowed:

- inspect the relevant production code/schema/contracts;
- write or modify tests;
- add test fixtures under `src/test`;
- mock dependencies;
- run a relevant test when execution is actually necessary;
- report the expected or observed failure.

Forbidden:

- touching `src/main`;
- creating the production implementation;
- changing production code merely to make a test compile;
- preemptively adding services, DTOs, mappers, abstractions, or architecture.

If production code does not compile because an API does not exist yet, report the blocking gap instead of silently stubbing it — unless the user explicitly asked for stub-first style.

Test execution is **not mandatory** merely because the task is in RED.

Run the test only when:

- the user explicitly asks to run or verify it;
- observing the actual failure is necessary to continue;
- an existing failure must be reproduced or diagnosed;
- compilation/runtime behavior cannot be reliably determined from the available code.

Otherwise, write the requested test, provide the narrowest verification command when useful, and stop.

For mechanical test edits that are statically verifiable, do not run tests merely to prove an obvious change.

Stop when the requested RED work is complete. Do not implement.

### GREEN

Enter GREEN only after the user explicitly asks to implement or make existing behavior/tests pass.

Implement the minimum production change required.

Prefer existing abstractions and existing architectural patterns over introducing new ones.

Do not:

- add unrelated behavior;
- refactor unrelated code;
- create abstractions "while you're in there";
- introduce a service merely because it would be cleaner;
- automatically continue into REFACTOR.

Test execution is not mandatory after every GREEN edit.

Run the narrowest relevant test when:

- the user explicitly requests verification;
- compilation/runtime behavior is genuinely uncertain;
- test output is necessary to determine whether the implementation is correct;
- the result is needed to choose the next implementation step.

Otherwise, provide the narrowest verification command and leave execution to the user.

Stop once the requested implementation is complete.

### REFACTOR

Enter only after an explicit refactor request.

Preserve observable behavior.

Tests should already represent the required behavior before refactoring. If their current status is known, do not rerun them merely as ceremony before making a mechanically safe refactor.

Run tests when:

- the refactor could realistically affect behavior;
- execution is needed to resolve uncertainty;
- the user explicitly requests verification.

Service/DTO/mapper extraction belongs here when explicitly requested — not automatically during RED or GREEN.

Do not rewrite passing tests merely because internal structure changed.

Stop once the requested refactor scope is complete.

## No speculative architecture

Business logic is intentionally allowed to start inside a controller or a DGS data fetcher and get extracted into a service later, at the user's explicit request.

Until then:

- Controller/DataFetcher → Repository directly is fine and expected during early development.
- Do not create `UserService`, `ProductService`, etc. merely because it is cleaner.
- Do not create DTOs, mapper layers, factories, interfaces, or helpers solely to make mocking easier.
- Do not add patterns, layers, or abstractions for hypothetical future requirements.
- Do not solve N+1, caching, pagination, validation, authorization, or other future concerns unless they are part of the requested behavior.
- Do not turn a local implementation task into an architecture redesign.

Existing project structure and explicit user intent outweigh generic best-practice advice.

## GraphQL / Netflix DGS conventions

Data fetchers resolve **GraphQL fields**, not database entities.

A root fetcher resolves:

- `Query.user`
- `Query.product`
- `Mutation.createUser`
- etc.

Nested fields that require custom loading use:

```java
@DgsData(parentType = "User", field = "addresses")
```

Rules:

- Do not add a top-level query merely because a JPA entity or repository exists.
- For example, do not create `userAddress(id: ...)` merely because `UserAddressRepository` exists when addresses naturally belong under `user { addresses { ... } }`.
- Fields resolvable via plain getters on the parent object need no dedicated data fetcher.
- Add a nested-field data fetcher only when it performs actual work such as custom loading, computed values, or cross-aggregate lookup.
- Do not add `DataLoader` preemptively.
- Introduce `DataLoader` only when an actual access pattern demonstrates an N+1 problem.
- Test the GraphQL contract rather than the underlying database representation.

## Test strategy by layer

### Pure unit tests

Use:

- JUnit 5;
- Mockito;
- `@Mock`;
- `@InjectMocks`.

Do not start a Spring context unless the behavior under test requires one.

### DGS data fetcher execution tests

Use the existing DGS testing pattern:

- `@SpringBootTest(classes = {...})`;
- `@EnableDgsTest`;
- `DgsQueryExecutor`;
- `@MockitoBean` for dependencies that must exist inside the Spring context.

### GraphQL HTTP tests

Use:

- `@EnableDgsMockMvcTest`;
- `@AutoConfigureHttpGraphQlTester`;
- `HttpGraphQlTester`.

Use raw `MockMvc` for GraphQL only when testing low-level HTTP behavior itself.

`@WebMvcTest` is not the default tool for DGS data fetchers.

### REST controllers

Once REST controllers exist, prefer:

- `@WebMvcTest`;
- `MockMvc`;
- `@MockitoBean`.

### Full integration

Use `@SpringBootTest` + Testcontainers only when real infrastructure behavior matters.

Examples:

- migrations;
- real repository queries;
- JPA mappings;
- PostgreSQL constraints;
- cascade behavior;
- triggers;
- Kafka integration.

Do not use full integration infrastructure for behavior that can be represented by a mock.

## Mockito conventions

`@Mock` creates a plain Mockito mock outside the Spring context.

`@MockitoBean` replaces or creates a bean inside Spring TestContext.

Repositories may be mocked in:

- controller tests;
- data-fetcher tests;
- service tests.

When a repository is mocked, the test verifies how the unit under test interacts with the repository and handles its result.

For example:

```java
when(userAddressRepository.findAllByUserId(userId))
    .thenReturn(List.of(userAddress));
```

This does **not** verify that Spring Data JPA correctly implements:

```java
List<UserAddress> findAllByUserId(UUID userId);
```

It verifies only the behavior of the unit consuming that repository result.

Real repository behavior belongs in an integration test against PostgreSQL/Testcontainers when such verification is actually needed.

Do not introduce Testcontainers into a test merely because the mocked dependency happens to be a repository.

## JPA entity conventions

Inspect the actual entity before assuming its shape.

General rules:

- protected no-args constructors exist for Hibernate;
- do not widen constructors to `public` merely to simplify tests;
- prefer existing factory methods or test fixtures;
- do not weaken encapsulation for test convenience;
- be careful with generated IDs;
- be careful with generated timestamps;
- do not rely on full entity equality when only part of the object is relevant.

A repository returning:

```java
List<UserAddress>
```

returns a collection of complete `UserAddress` entities, not a collection of individual address fields.

Tests consuming mocked repository results should construct only the data required by the behavior being tested.

## GraphQL assertions

Assert the GraphQL contract, not the JPA entity.

If a query selects:

```graphql
user {
  username
  email
}
```

assert:

```text
user.username
user.email
```

Do not deserialize and compare the entire JPA entity merely because the resolver returned one internally.

For lists, address the actual GraphQL response shape.

For example:

```graphql
user {
  addresses {
    firstName
    city
  }
}
```

returns an array, so an assertion for its first item uses paths such as:

```text
user.addresses[0].firstName
user.addresses[0].city
```

Only assert fields present in the selection set.

Do not add assertions for fields the GraphQL operation did not request.

Do not introduce response DTOs/records solely for assertions unless they genuinely improve the test.

## Test tags

`./gradlew test -Pgroup=unit` supports comma-separated JUnit tag filtering.

Available groups include:

- `unit`
- `graphql`
- `integration`
- `e2e`

No tests currently carry all of these tags.

Do not mass-edit existing tests to add tags as part of unrelated work.

Only add or change tags when relevant to the test already being modified.

## Minimal diff rule

Before editing, identify exactly which files are necessary.

Touch only those files.

Do not:

- reformat unrelated files;
- rename unrelated classes;
- reorganize packages;
- rewrite imports project-wide;
- bump dependencies;
- modify build configuration for convenience;
- perform opportunistic cleanup;
- fix unrelated warnings;
- change neighboring code merely because it could be improved.

For a single-file request, assume the task should remain single-file unless another file is genuinely required.

## Mechanical change policy

Mechanical and locally verifiable changes should be handled directly with minimal tool usage.

Examples:

- completing existing `GraphQlTester` assertions;
- changing `user.addresses.firstName` to `user.addresses[0].firstName`;
- adding assertions for fields already present in a selection set;
- extending an existing repetitive assertion chain;
- completing obvious Mockito stubbing;
- adding an obvious `thenReturn(List.of(...))`;
- fixing selectors;
- fixing imports;
- trivial syntax corrections;
- straightforward mappings;
- renaming;
- formatting;
- repetitive edits following an established pattern.

For these tasks:

1. Read only the minimum context necessary.
2. Apply the existing pattern.
3. Make the smallest correct diff.
4. Do not inspect unrelated files.
5. Do not investigate architecture.
6. Do not search for alternative designs.
7. Do not run tests unless requested or required to resolve actual uncertainty.
8. Do not compile merely to validate obvious syntax.
9. Stop immediately after the requested edit is complete.

Do not turn a mechanical edit into a full TDD ceremony.

## Repository inspection policy

Do not explore the repository speculatively.

Before reading another file, determine whether its contents could materially change the requested edit.

If not, do not read it.

Prefer inspection in this order:

1. the file explicitly named by the user;
2. directly referenced contracts/types when required;
3. nearby tests when an unknown convention must be established;
4. broader search only when required information cannot otherwise be obtained.

Do not:

- reread unchanged files whose relevant contents are already available;
- inspect implementations merely to confirm obvious Java/Spring behavior;
- inspect unrelated packages;
- search for abstractions that are not required;
- perform repository-wide searches for a local mechanical edit;
- inspect documentation unrelated to the requested behavior.

Every additional file read should answer a concrete unresolved question.

## Tool and token efficiency

Tool usage and reasoning must be proportional to task complexity and uncertainty.

Correctness takes priority over token efficiency, but every additional tool call must have a concrete purpose.

For small tasks:

- prefer direct edits over extensive analysis;
- prefer known local context over repository exploration;
- prefer existing patterns over researching alternatives;
- avoid proving facts already evident from Java types, method signatures, GraphQL schema types, mocks, or surrounding code;
- avoid investigating hypothetical edge cases outside the requested scope;
- avoid unnecessary test execution;
- avoid unnecessary compilation;
- avoid rereading unchanged context.

Do not use tools merely to increase confidence in facts already evident from available code.

Do not perform additional work solely because tools are available.

## Test Execution Policy

User-driven verification is the default.

The user is responsible for running tests locally unless execution is genuinely required to complete the requested task.

Do not run tests automatically after every code change.

Do not run tests merely because:

- a test file changed;
- production code changed;
- the task is in RED;
- the task is in GREEN;
- TDD convention normally includes execution;
- verification would be nice to have.

Run tests only when:

- the user explicitly asks to run or verify them;
- test output is required to diagnose a failure;
- an existing failure must be reproduced;
- compilation/runtime behavior cannot be reliably determined statically;
- the result is required to decide the next implementation step.

When execution is required:

1. Run the narrowest possible scope.
2. Prefer a single test method when practical.
3. Otherwise run a single test class.
4. Run a package/group only when necessary.
5. Never run the full test suite when a narrower command answers the same question.
6. Do not repeatedly rerun an unchanged test unless the previous result provides information required for the next change.

When execution is unnecessary, provide the exact narrowest command the user can run.

For example:

```bash
./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest"
```

Treat test execution as an expensive operation.

## Compilation policy

Compilation is not mandatory after every change.

Compile only when:

- a signature or generic type is genuinely uncertain;
- generated GraphQL/DGS types may affect compilation;
- API usage cannot be reliably determined from available code;
- compilation failure is being diagnosed;
- the user explicitly requests verification.

Do not run Gradle merely to validate:

- repetitive assertions;
- obvious method chaining;
- straightforward Mockito stubbing;
- formatting;
- trivial syntax;
- an edit that directly follows an existing compiling pattern.

## Existing tests are requirements

Do not modify a test merely because production code currently fails it.

When an existing test fails:

1. determine whether it represents intended behavior;
2. check its relevant schema/contract if necessary;
3. during an explicit GREEN step, fix production code.

Only change the test when:

- the user explicitly asks to change it;
- the test is demonstrably incorrect;
- the public contract itself intentionally changed.

Never:

- weaken assertions merely to get green;
- delete a failing test merely to get green;
- loosen expected behavior merely to match the current implementation.

## User controls scope — examples

### Example: RED

User:

```text
Write a test for Query.user
```

Action:

- inspect only required schema/code;
- write the test;
- do not modify `src/main`;
- do not add a service;
- do not add DTOs;
- do not touch migrations;
- do not automatically run the test unless execution is required or requested;
- provide the narrowest verification command;
- stop.

### Example: GREEN

User:

```text
Now make it pass
```

Action:

- implement the minimum production change;
- preserve existing architecture;
- do not refactor;
- run the test only if verification is required/requested;
- stop.

### Example: REFACTOR

User:

```text
Refactor this into a service
```

Action:

- move the requested logic into a service;
- preserve behavior;
- do not introduce unrelated layers;
- verify when necessary;
- stop.

### Example: mechanical test edit

User:

```text
Finish graphQlTester in UserDataFetcherTest.java
```

If the existing query and assertion pattern already make the required edit obvious:

- read `UserDataFetcherTest.java`;
- complete the assertion chain;
- use list paths such as `addresses[0]` where required by the response shape;
- do not inspect unrelated production files;
- do not redesign the resolver;
- do not run Gradle merely to verify repetitive assertions;
- stop.

## Reporting

Keep reporting proportional to the requested change.

For substantial TDD work, report:

- current TDD phase;
- files changed;
- whether tests were executed;
- result if executed;
- whether production code was modified;
- intentionally unfinished behavior when relevant.

If tests were not executed, say so briefly when relevant and provide the narrowest verification command.

For trivial/mechanical changes, keep reporting minimal.

Example:

```text
Current phase: RED

Changed:
- UserDataFetcherTest.java — completed GraphQlTester assertions for addresses[0].

Tests not executed; change is mechanical and statically verifiable.

To verify:
./gradlew test --tests "ee.bytecore.backend.graphql.datafetchers.UserDataFetcherTest"

Production code modified: No.
```

Do not spend more tokens reporting a trivial change than were required to make it.

## Critical execution rules

1. Never implement production code unless explicitly requested.
2. A request to write tests is not permission to implement the tested behavior.
3. Never automatically advance from RED to GREEN.
4. During RED, do not modify `src/main`.
5. Prefer the smallest possible diff.
6. Do not introduce architecture preemptively.
7. Do not create a service layer merely because it would make the architecture cleaner.
8. Business logic may intentionally begin in controllers or DGS data fetchers and be extracted later on request.
9. GraphQL data fetchers correspond to GraphQL fields, not database entities.
10. Do not create a top-level GraphQL operation for every entity/repository.
11. Do not modify existing tests merely to make an implementation pass.
12. Testcontainers are for real integration behavior, not ordinary unit tests.
13. Use `@MockitoBean` when replacing a dependency inside a Spring test context.
14. Use ordinary Mockito for pure unit tests.
15. Stop after completing the phase the user requested.
16. Never broaden scope without explicit instruction.
17. Do not run tests automatically for mechanical or statically verifiable changes.
18. Test execution must resolve uncertainty or satisfy an explicit user request; it is not a ritual.
19. Read only files required for the requested change.
20. Do not perform speculative repository exploration.
21. Do not reread unchanged context unnecessarily.
22. Apply established repetitive patterns directly.
23. Keep tool calls and reasoning proportional to task complexity.
24. Prefer user-driven test execution when execution is not required to perform the edit.
25. Never run the full test suite when a narrower command answers the same question.
26. Do not compile automatically after trivial changes.
27. Do not load unrelated documentation or inspect unrelated packages.
28. Do not investigate hypothetical improvements outside requested behavior.
29. Do not use tool calls merely to increase confidence in facts already evident from available code.
30. Every additional tool call must have a concrete purpose.

See `.claude/skills/tdd/SKILL.md` for the detailed TDD state machine and phase-specific workflow.

**Enforcement note:** there is no hook blocking edits to `src/main` during RED. Reliably detecting the current conversational TDD phase would require fragile heuristics. This remains instruction-enforced unless an explicit project-local state mechanism is introduced later; do not add such a mechanism speculatively.