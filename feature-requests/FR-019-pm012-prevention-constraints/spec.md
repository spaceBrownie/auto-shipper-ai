# FR-019: PM-012 Prevention Constraints

## Problem Statement

Post-mortem PM-012 identified three bug classes that surfaced during RAT-22 (demand signal API pivot). Each bug was individually fixable, but the root causes are systemic — they can recur in any future adapter or Spring component unless prevented structurally.

The three bug classes are:

1. **Spring `@Value` placeholders without defaults crash bean instantiation** when the property is absent in the active profile. The `AmazonCreatorsApiAdapter` used `@Value("${amazon-creators.api.base-url}")` without a default, causing `Could not resolve placeholder` even though the bean was `@ConditionalOnProperty`-gated — because Spring resolves `@Value` during constructor injection *before* evaluating `@ConditionalOnProperty`.

2. **Kotlin `internal constructor` on Spring `@Component` classes** breaks constructor resolution. Kotlin compiles `internal` to JVM `public` with a synthetic `DefaultConstructorMarker` parameter that Spring cannot satisfy. This caused a `NoSuchMethodException` at runtime that unit tests with mocked repositories never caught.

3. **Jackson `path()` silently returns empty string instead of null** for missing fields. `path("access_token").asText()` returns `""` when the field is absent, which bypasses Kotlin's `?:` null-coalescing error handling. The `AmazonCreatorsApiAdapter.getAccessToken()` method at line 106 has this exact bug, which will silently fail when/if the adapter is re-enabled.

Additionally, CI test output truncates exception messages (showing only class name and line number), which hid the real `Could not resolve placeholder` error during debugging. And the portfolio module has zero `@SpringBootTest` tests, so bean wiring failures are invisible until the full `:app` module integration tests run.

## Business Requirements

### BR-1: Codify CLAUDE.md Constraint #13 — @Value defaults

All `@Value("${...}")` annotations on adapter constructor parameters must include empty defaults (e.g., `${key:}`), so beans can instantiate under any Spring profile without crashing. Adapters that receive blank values must guard against them in their `fetch()` method with an early return (log + return empty list), rather than proceeding with blank credentials.

This prevents deployment failures and test failures caused by missing configuration properties, especially for adapters gated by `@ConditionalOnProperty` or `@Profile`.

### BR-2: Codify CLAUDE.md Constraint #14 — No internal constructor on Spring beans

Never use Kotlin `internal constructor` on Spring `@Component`/`@Service`/`@Repository` classes. Kotlin compiles `internal` visibility to JVM `public` with an additional synthetic `DefaultConstructorMarker` parameter. Spring's constructor resolution cannot handle the extra parameter and throws `NoSuchMethodException` at runtime.

For test-only injection needs, use `internal var` fields set via `.also{}` blocks in tests, or use `@TestConfiguration` beans.

### BR-3: Codify CLAUDE.md Constraint #15 — Jackson get() vs path()

When the absence of a JSON field should trigger error handling (e.g., via Kotlin's `?:` operator), use Jackson `get()` (returns `null` for missing fields) instead of `path()` (returns `MissingNode`). `MissingNode.asText()` silently returns `""`, which bypasses null-coalescing logic and causes silent failures.

`path()` remains appropriate for traversing nested structures where providing a default value via `asText("default")` is the intended behavior.

### BR-4: Fix AmazonCreatorsApiAdapter.getAccessToken() path() bug

The `AmazonCreatorsApiAdapter.getAccessToken()` method at line 106 uses `response?.path("access_token")?.asText()`. Because `path()` returns `MissingNode` (never null), the `?:` fallback to `throw IllegalStateException` is unreachable when the `access_token` field is absent — the method would instead cache an empty string as the token. Change to `get("access_token")?.asText()` so a missing field correctly triggers the error path.

This is a proactive fix. The adapter is currently deactivated via `@ConditionalOnProperty(matchIfMissing = false)`, but the bug would cause silent authentication failures if re-enabled.

### BR-5: Improve CI test failure output

Configure the CI workflow so that failed tests produce full exception messages (including the root cause chain), not just the class name and line number. The truncated `NoSuchMethodException` output during RAT-22 hid the underlying `Could not resolve placeholder` error, adding significant debugging time.

Either configure Gradle's test task to print full exception messages to stdout, or publish the HTML test report as a CI artifact (or both).

### BR-6: Add @SpringBootTest smoke test to portfolio module

Add a minimal Spring context load test (`contextLoads()` equivalent) to the portfolio module. This test should boot a Spring context scoped to the portfolio module (without requiring the full `:app` module's database/Flyway setup) and verify that all beans instantiate successfully.

Currently, all portfolio tests use Mockito with no Spring context, so constructor/bean wiring issues (like the `internal constructor` and `@Value` placeholder bugs) are invisible until the `:app:test` phase.

## Success Criteria

1. **CLAUDE.md constraints #13, #14, #15 are present** — numbered sequentially after the existing constraint #12, with clear rationale and examples.
2. **AmazonCreatorsApiAdapter uses `get()` for token extraction** — `getAccessToken()` uses `get("access_token")?.asText()` instead of `path("access_token")?.asText()`, ensuring the `?:` error path is reachable.
3. **AmazonCreatorsApiAdapter @Value annotations have defaults** — all five `@Value` parameters include empty defaults (`:}` syntax), and `fetch()` guards against blank values with an early return.
4. **CI workflow produces full exception output** — either via Gradle `testLogging` configuration or HTML report artifact (or both). A developer reading CI logs can see the full root cause chain for any test failure.
5. **Portfolio module has a @SpringBootTest context load test** — the test boots a Spring context scoped to the portfolio module, verifying bean instantiation succeeds. The test is included in CI test count gates if appropriate.
6. **All existing tests pass** — no regressions introduced by any of the changes.

## Non-Functional Requirements

### NFR-1: Zero runtime behavior change

Adding defaults to `@Value` annotations and guarding `fetch()` must not change the behavior of adapters that are correctly configured. Only the failure mode changes (graceful early return instead of crash).

### NFR-2: CI build time impact

The portfolio `@SpringBootTest` must not significantly increase CI build time. It should use a minimal context (e.g., `@SpringBootTest` with a test-scoped configuration) rather than loading the full application context.

### NFR-3: Constraint discoverability

CLAUDE.md constraints must be numbered sequentially and follow the existing format (bold title, em-dash, explanation with rationale). Each constraint must be self-contained — a developer reading it should understand the bug class without needing to reference PM-012.

## Dependencies

- **CLAUDE.md** — constraints #1-#12 already exist; new constraints #13-#15 must be appended in sequence.
- **AmazonCreatorsApiAdapter** — the existing adapter at `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/AmazonCreatorsApiAdapter.kt` must be modified.
- **CI workflow** — `.github/workflows/ci.yml` must be updated.
- **Portfolio module test infrastructure** — a `@SpringBootTest` test requires a test application context configuration. The portfolio module currently has no Spring test dependencies or test application properties.
- **Gradle test logging** — may require changes to the root `build.gradle.kts` or the `modules/app/build.gradle.kts` to configure `testLogging`.
