## Token and Tool Efficiency

- Do not run tests for trivial, locally verifiable edits unless explicitly requested.
- Do not perform RED/GREEN verification for mechanical test completion tasks.
- If the requested change is limited to completing assertions, selectors, mappings, DTO fields, mocks, or similar obvious code:
    - read only the necessary file/context;
    - make the minimal edit;
    - stop.
- Do not inspect unrelated production code unless required to determine expected behavior.
- Do not run Gradle/Maven commands just to confirm syntax that can be checked statically.
- Do not repeatedly read files that were already loaded and have not changed externally.
- Prefer the smallest possible tool scope and patch.
- After a trivial edit, report what changed and optionally provide the command the user can run to verify it.