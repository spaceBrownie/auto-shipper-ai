# FR-019: PM-012 Prevention Constraints — Implementation Plan

## Technical Design

Six changes to prevent the three bug classes identified in PM-012 from recurring:

1. **CLAUDE.md constraints #13, #14, #15** — codify `@Value` defaults, `internal constructor` prohibition on Spring beans, and `get()` vs `path()` Jackson rule.
2. **AmazonCreatorsApiAdapter fix** — apply the new constraints to the existing adapter: add empty defaults to all five `@Value` annotations, change `path("access_token")` to `get("access_token")`, add blank credential guard in `fetch()`.
3. **CjDropshippingAdapter fix** — same `@Value` default treatment (two parameters missing defaults), add blank credential guard in `fetch()`.
4. **Gradle testLogging** — configure `exceptionFormat = "full"` and `showStackTraces = true` in the root `build.gradle.kts` `subprojects` block so all modules get full exception output. Publish HTML test report as CI artifact.
5. **Portfolio @SpringBootTest** — minimal context load test using `@SpringBootTest(classes = [...])` with a test-scoped `@TestConfiguration` that provides mock beans for external dependencies (repositories, REST clients) and disables JPA/Flyway. No database required.

## Architecture Decisions

### AD-1: Root-level testLogging vs per-module

Configure `testLogging` in the root `build.gradle.kts` `subprojects` block. This ensures every module benefits automatically, including future modules. Per-module configuration would require repeating the block in each `build.gradle.kts`.

### AD-2: Portfolio @SpringBootTest scope

Use `@SpringBootTest` with `@ActiveProfiles("test")` and a `@TestConfiguration` that supplies stubs for JPA repositories and external adapters. Disable Flyway and JPA auto-configuration via test properties (`spring.autoconfigure.exclude`). This validates bean wiring without requiring a database, keeping the test fast and CI-friendly.

The test needs a `@SpringBootApplication`-annotated class scoped to the portfolio module (a test-only application class), since the portfolio module is a library module — it has no main application class. The test application class scans only `com.autoshipper.portfolio`.

### AD-3: CjDropshippingAdapter included in scope

The spec focuses on `AmazonCreatorsApiAdapter`, but `CjDropshippingAdapter` has the same two bugs: `@Value` without defaults and no blank credential guard. Constraint #13 says "all `@Value` annotations on adapter constructor params" — applying the fix only to Amazon would violate the constraint we are simultaneously codifying. Fix both in this FR.

### AD-4: Existing adapters already compliant

`RedditDemandAdapter`, `YouTubeDataAdapter`, and `GoogleTrendsAdapter` already have empty defaults on all `@Value` params and blank credential guards. No changes needed for those.

Adapters in `catalog` and `fulfillment` modules (`FedExRateAdapter`, `UpsRateAdapter`, `UspsRateAdapter`, `StripeProcessingFeeProvider`, `ShopifyPlatformFeeProvider`, `InventoryCheckAdapter`, `StripeRefundAdapter`) also lack defaults but are outside the portfolio module. Per the spec's scope (BR-4 targets AmazonCreatorsApiAdapter specifically), we note these as follow-up work but do not change them in this FR.

## Layer-by-Layer Implementation

### Layer 1: CLAUDE.md Constraints

**File:** `CLAUDE.md`

Add three constraints after existing constraint #12, inside the "Critical Engineering Constraints" section:

**Constraint #13 — `@Value` annotations on adapter constructor parameters must include empty defaults:**
```
13. **All `@Value` annotations on adapter constructor parameters must include empty defaults** — Use `${key:}` syntax (colon before closing brace) so beans can instantiate under any Spring profile without crashing. Spring resolves `@Value` during constructor injection *before* evaluating `@ConditionalOnProperty`, so a missing property causes `Could not resolve placeholder` even for disabled beans. Adapters receiving blank values must guard in their `fetch()` method with an early return (log warning + return empty list), not proceed with blank credentials.
```

**Constraint #14 — No `internal constructor` on Spring beans:**
```
14. **Never use Kotlin `internal constructor` on `@Component`/`@Service`/`@Repository` classes** — Kotlin compiles `internal` visibility to JVM `public` with an additional synthetic `DefaultConstructorMarker` parameter. Spring's constructor resolution cannot satisfy the extra parameter and throws `NoSuchMethodException` at runtime. Unit tests with mocked dependencies won't catch this. For test-only injection, use `internal var` fields set via `.also{}` blocks, or `@TestConfiguration` beans.
```

**Constraint #15 — Jackson `get()` vs `path()`:**
```
15. **Use Jackson `get()` instead of `path()` when absence should trigger `?:` null-coalescing** — `get()` returns `null` for missing fields; `path()` returns `MissingNode` whose `asText()` returns `""` (never null). Using `path()` with `?.asText() ?: fallback` silently bypasses the fallback because `MissingNode` is not null. Use `path()` only for nested traversal where `asText("default")` provides an explicit default.
```

### Layer 2: AmazonCreatorsApiAdapter Fix

**File:** `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/AmazonCreatorsApiAdapter.kt`

Three changes:

1. **Add empty defaults to all five `@Value` annotations:**
   - `@Value("${amazon-creators.api.base-url:}")`
   - `@Value("${amazon-creators.api.credential-id:}")`
   - `@Value("${amazon-creators.api.credential-secret:}")`
   - `@Value("${amazon-creators.api.partner-tag:}")`
   - `@Value("${amazon-creators.api.marketplace:}")`

2. **Add blank credential guard at top of `fetch()`:**
   ```kotlin
   if (baseUrl.isBlank() || credentialId.isBlank() || credentialSecret.isBlank()) {
       logger.warn("Amazon Creators API credentials are blank — skipping fetch")
       return emptyList()
   }
   ```

3. **Fix `path()` to `get()` on line 106:**
   ```kotlin
   // Before:
   cachedToken = response?.path("access_token")?.asText()
   // After:
   cachedToken = response?.get("access_token")?.asText()
   ```

4. **Lazy-init `restClient`** — currently `RestClient.builder().baseUrl(baseUrl).build()` is called in the field initializer, which would fail on a blank URL. Change to lazy initialization or move into `fetch()` behind the guard.

### Layer 3: CjDropshippingAdapter Fix

**File:** `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/CjDropshippingAdapter.kt`

Two changes:

1. **Add empty defaults to both `@Value` annotations:**
   - `@Value("${cj-dropshipping.api.base-url:}")`
   - `@Value("${cj-dropshipping.api.access-token:}")`

2. **Add blank credential guard at top of `fetch()`:**
   ```kotlin
   if (baseUrl.isBlank() || accessToken.isBlank()) {
       logger.warn("CJ Dropshipping credentials are blank — skipping fetch")
       return emptyList()
   }
   ```

3. **Lazy-init `restClient`** — same issue as AmazonCreatorsApiAdapter; `RestClient.builder().baseUrl(baseUrl).build()` in field initializer will fail on blank URL.

### Layer 4: Gradle testLogging

**File:** `build.gradle.kts` (root)

Add a `tasks.withType<Test>` block inside `subprojects`:

```kotlin
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        // ... existing config ...

        tasks.withType<Test>().configureEach {
            testLogging {
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
                showCauses = true
            }
        }
    }
}
```

**File:** `.github/workflows/ci.yml`

Add an artifact upload step after the build step to publish HTML test reports:

```yaml
- name: Upload test reports
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: test-reports
    path: '**/build/reports/tests/test/'
    retention-days: 7
```

### Layer 5: Portfolio @SpringBootTest

**New files:**

1. **`modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/PortfolioContextLoadTest.kt`**

   ```kotlin
   @SpringBootTest
   @ActiveProfiles("test")
   class PortfolioContextLoadTest {
       @Test
       fun contextLoads() {
           // Verifies all portfolio beans instantiate successfully
       }
   }
   ```

2. **`modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/TestPortfolioApplication.kt`**

   A minimal `@SpringBootApplication(scanBasePackages = ["com.autoshipper.portfolio"])` class for the test context. This is needed because the portfolio module is a library — it has no main-class entry point.

3. **`modules/portfolio/src/test/resources/application-test.yml`**

   Minimal config that disables JPA/Flyway and provides empty adapter properties:
   ```yaml
   spring:
     autoconfigure:
       exclude:
         - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
         - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
         - org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
     profiles:
       active: test,local
   ```

   The `local` profile ensures `@Profile("!local")` adapters (Amazon, CJ, Reddit, YouTube, GoogleTrends) are excluded, so their `@Value` properties don't need to be provided. Only the stub providers and non-adapter beans need to wire.

**Dependencies:** `spring-boot-starter-test` is already in `modules/portfolio/build.gradle.kts`. No new dependencies needed.

## Task Breakdown

### CLAUDE.md Constraints
- [x] Add constraint #13 (`@Value` defaults) after constraint #12
- [x] Add constraint #14 (`internal constructor` prohibition)
- [x] Add constraint #15 (Jackson `get()` vs `path()`)

### AmazonCreatorsApiAdapter Fix
- [x] Add empty defaults to all five `@Value` annotations
- [x] Change `response?.path("access_token")?.asText()` to `response?.get("access_token")?.asText()`
- [x] Add blank credential guard at top of `fetch()`
- [x] Make `restClient` lazy-initialized (`by lazy {}`)

### CjDropshippingAdapter Fix
- [x] Add empty defaults to both `@Value` annotations
- [x] Add blank credential guard at top of `fetch()`
- [x] Make `restClient` lazy-initialized (`by lazy {}`)

### CI Test Output
- [x] Add `testLogging` block with `exceptionFormat = FULL` in root `build.gradle.kts` `subprojects`
- [x] Add HTML test report artifact upload step to `.github/workflows/ci.yml`

### Portfolio @SpringBootTest
- [x] Create `TestPortfolioApplication.kt` test application class
- [x] Create `application-test.yml` in `modules/portfolio/src/test/resources/`
- [x] Create `PortfolioContextLoadTest.kt` with `contextLoads()` test (7 @MockBean repos + MarginSignalProvider)
- [x] Verify test passes with `./gradlew :portfolio:test` — 78 tests pass

### Verification
- [x] Run `./gradlew test -x :app:test` — all module tests pass
- [x] `contextLoads()` passes in `:app:test`
- [x] CLAUDE.md constraints #13-#15 numbered sequentially, match existing format
- [x] CI workflow YAML valid

## Testing Strategy

### CLAUDE.md Constraints
No automated test — constraints are documentation. Verify by reading the file and confirming sequential numbering, consistent format (bold number + title, em-dash, explanation), and self-contained rationale.

### AmazonCreatorsApiAdapter
1. **Existing test (`AmazonAdapterDeactivationTest`)** continues to pass — verifies annotation metadata.
2. **Manual code review** — confirm `get()` instead of `path()`, defaults on all `@Value`, guard in `fetch()`.
3. **Portfolio context load test** — if the adapter's `@Value` defaults are missing, context would fail to load (even though the adapter is `@ConditionalOnProperty`-gated, Spring resolves `@Value` before condition evaluation). The context load test with the `local` profile excludes the adapter entirely, so this is more of a regression net for when someone removes the profile gate.

### CjDropshippingAdapter
Same as above. The blank-guard behavior can be verified by the existing unit test patterns if desired, but since the adapter is `@Profile("!local")`-gated, the primary validation is code review.

### Gradle testLogging
Run `./gradlew test` with a deliberately failing test and verify the console output includes the full exception chain (not just class name + line number). After confirming, revert the deliberate failure.

### Portfolio @SpringBootTest
Run `./gradlew :portfolio:test` and verify the new `PortfolioContextLoadTest` passes. The test validates that all portfolio beans instantiate successfully under the test profile (with JPA/Flyway disabled and `local` profile active to exclude external adapters).

## Rollout Plan

### Deployment
This is purely a prevention/infrastructure change:
- No database migrations
- No runtime behavior changes (NFR-1)
- No API changes
- Safe to merge directly to main

### Order of Changes
1. CLAUDE.md constraints (documentation first — establishes the rules)
2. AmazonCreatorsApiAdapter + CjDropshippingAdapter fixes (apply the rules)
3. Gradle testLogging + CI artifact upload (infrastructure)
4. Portfolio @SpringBootTest (verification)

All changes ship in a single PR.

### Rollback
Every change is independently revertible. If the portfolio `@SpringBootTest` causes issues (e.g., unexpected bean scanning), it can be removed without affecting the adapter fixes or CLAUDE.md constraints. The `testLogging` config is additive and has no rollback risk.
