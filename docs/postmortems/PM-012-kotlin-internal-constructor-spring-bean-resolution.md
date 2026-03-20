# PM-012: Kotlin Internal Constructor Breaks Spring + Jackson path() Silent Token Failure

**Date:** 2026-03-20
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session
**Related:** RAT-22 (demand signal API pivot), PR #25

## Summary

Three interrelated bugs were discovered during the RAT-22 implementation (replacing Amazon Creators API with YouTube + Reddit adapters). (1) Kotlin `internal` secondary constructors compiled to `public` JVM constructors with synthetic `DefaultConstructorMarker` parameters, breaking Spring's constructor resolution and causing `NoSuchMethodException` on startup. (2) `@Value` annotations without defaults caused `Could not resolve placeholder` errors under the `test` profile. (3) Jackson's `path()` returns `MissingNode` (never null), so `path("access_token").asText()` silently returns `""` instead of triggering the `?:` fallback — caching an empty OAuth token. All three bugs were caught by Unblocked's automated PR review and CI before reaching production.

## Timeline

| Time | Event |
|------|-------|
| Session start | RAT-22 implementation begins — 3 parallel agents build YouTube adapter, Reddit adapter, and config/stubs |
| +30 min | All 3 agents complete, 31 new tests pass in `:portfolio:test` |
| +35 min | PR #25 created, pushed to GitHub |
| +37 min | CI fails: `AutoShipperApplicationTest > contextLoads() FAILED` — `NoSuchMethodException` |
| +37 min | Unblocked PR review flags 2 issues: (1) YouTubeDataAdapter primary constructor takes `RestClient` param Spring can't inject, (2) Reddit adapter `path()` vs `get()` for token extraction |
| +40 min | First fix attempt: restructure YouTubeDataAdapter constructor, fix `path()` → `get()`. Push. |
| +50 min | CI still fails: same `NoSuchMethodException` on `redditDemandAdapter` |
| +55 min | Local reproduction reveals actual root cause: `Could not resolve placeholder 'youtube.api.search-terms'` — the `NoSuchMethodException` was a red herring in truncated CI logs |
| +60 min | Root cause chain identified: (1) `internal constructor` → synthetic JVM parameter, (2) missing `@Value` defaults, (3) `test` profile loads `@Profile("!local")` beans without matching config |
| +65 min | Fix applied: remove all `internal` secondary constructors, add empty `@Value` defaults, use `internal var` fields + `.also{}` for test injection |
| +70 min | `contextLoads()` passes locally, all `:app:test` passes, pushed |
| +75 min | Full E2E playbook validated — all 8 phases pass including demand scan with 4 sources |

## Symptom

CI `build-and-test` job failed with 23/25 `:app:test` failures. All failures cascaded from a single root:

```
AutoShipperApplicationTest > contextLoads() FAILED
  java.lang.IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:180
    Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException:
      Error creating bean with name 'youTubeDataAdapter':
        Unexpected exception during bean creation
          Caused by: java.lang.IllegalArgumentException:
            Could not resolve placeholder 'youtube.api.search-terms'
            in value "${youtube.api.search-terms}"
```

The CI Gradle test runner truncated the exception to just `NoSuchMethodException at Class.java:3763`, hiding the actual `Could not resolve placeholder` error. This misdirected the first fix attempt.

A second latent bug was identified by Unblocked's PR review:

```kotlin
// RedditDemandAdapter.kt line 140
cachedToken = response?.path("access_token")?.asText()
    ?: throw IllegalStateException("Failed to obtain Reddit access token")
```

When Reddit returns `{"error": "invalid_grant"}` (no `access_token` field), `path()` returns `MissingNode`, `asText()` returns `""`, the `?:` never fires, and `cachedToken` is set to empty string. All subsequent API calls silently fail.

## Root Cause

### Bug 1: Kotlin `internal constructor` + Spring constructor resolution

**5 Whys:**

1. **Why** did `contextLoads()` fail? → Spring couldn't instantiate `YouTubeDataAdapter` and `RedditDemandAdapter` beans.
2. **Why** couldn't Spring instantiate them? → The `@Value("${youtube.api.search-terms}")` placeholder couldn't be resolved.
3. **Why** couldn't the placeholder be resolved? → The `test` profile (used by `@SpringBootTest`) is not `local`, so `@Profile("!local")` beans load, but `application-test.yml` doesn't define YouTube/Reddit config.
4. **Why** was there no default? → The `@Value` annotations used `${youtube.api.search-terms}` without a `:default` suffix, unlike other adapters' patterns (e.g., `${youtube.api.max-results-per-search:10}`).
5. **Why** wasn't this caught earlier? → Portfolio module unit tests don't boot a Spring context. Only `:app:test`'s `@SpringBootTest` loads the full context, and it runs under the `test` profile (not `local`).

**Contributing factor:** The initial implementation also used a secondary `internal constructor` for test injection:

```kotlin
// BROKEN — Kotlin internal compiles to public with synthetic DefaultConstructorMarker
class YouTubeDataAdapter(
    @Value("...") private val baseUrl: String,
    ...
    private val restClient: RestClient  // Spring tries to inject this — no bean exists
) : DemandSignalProvider {
    constructor(...) : this(..., RestClient.builder().baseUrl(baseUrl).build())  // Spring never calls this
```

Kotlin's `internal` visibility modifier compiles to `public` at the JVM bytecode level, with an additional synthetic `kotlin.jvm.internal.DefaultConstructorMarker` parameter. Spring sees multiple `public` constructors and its `AutowiredAnnotationBeanPostProcessor` picks the primary constructor (which has a `RestClient` parameter that no bean satisfies).

### Bug 2: Jackson `path()` vs `get()` for null safety

```kotlin
// path() returns MissingNode, never null
// MissingNode.asText() returns "" (empty string)
// ?: only triggers on null, not on ""
cachedToken = response?.path("access_token")?.asText()
    ?: throw IllegalStateException("...")  // NEVER THROWN for missing field
```

This is a semantic mismatch between Jackson's `path()` (which is designed to be null-safe by returning sentinel nodes) and Kotlin's `?:` operator (which only checks for `null`). The adapter would cache `""` as the token, then send `Authorization: Bearer ` (empty) on every subsequent request, returning 0 candidates with no error logging.

**Note from Unblocked review:** The same bug exists in the pre-existing `AmazonCreatorsApiAdapter.getAccessToken()` (line 106), but that adapter is now deactivated via `@ConditionalOnProperty`.

## Fix Applied

### Fix 1: Remove internal constructors, use internal var fields

Replaced the secondary `internal constructor` pattern with `internal var` fields that tests can set directly:

```kotlin
// BEFORE (broken)
class YouTubeDataAdapter(
    @Value("...") private val baseUrl: String,
    private val restClient: RestClient  // Can't be injected
) {
    internal constructor(baseUrl: String, ..., restClient: RestClient)
        : this(baseUrl, ..., RestClient.builder().baseUrl(baseUrl).build())
}

// AFTER (works)
class YouTubeDataAdapter(
    @Value("\${youtube.api.base-url:https://www.googleapis.com/youtube/v3}") private val baseUrl: String,
    @Value("\${youtube.api.key:}") private val apiKey: String,
    @Value("\${youtube.api.search-terms:}") private val searchTerms: List<String>,
    @Value("\${youtube.api.max-results-per-search:10}") private val maxResultsPerSearch: Int
) {
    internal var restClient: RestClient = RestClient.builder().baseUrl(baseUrl).build()
}

// Test usage:
val adapter = YouTubeDataAdapter("http://test", "key", listOf("term"), 10)
    .also { it.restClient = mockRestClient }
```

Same pattern applied to `RedditDemandAdapter` (two fields: `authClient` and `apiClient`).

### Fix 2: Add empty defaults to @Value annotations

All `@Value` annotations now have empty defaults so beans can instantiate under any profile:

```kotlin
@Value("\${youtube.api.key:}") private val apiKey: String,          // was: ${youtube.api.key}
@Value("\${youtube.api.search-terms:}") private val searchTerms: List<String>,  // was: ${youtube.api.search-terms}
@Value("\${reddit.api.client-id:}") private val clientId: String,   // was: ${reddit.api.client-id}
@Value("\${reddit.api.client-secret:}") private val clientSecret: String,       // was: ${reddit.api.client-secret}
```

Blank credentials trigger early return in `fetch()` — no behavioral change for configured environments.

### Fix 3: Jackson get() instead of path()

```kotlin
// BEFORE: path() returns MissingNode, asText() returns ""
cachedToken = response?.path("access_token")?.asText()
    ?: throw IllegalStateException("Failed to obtain Reddit access token")

// AFTER: get() returns null for missing fields
cachedToken = response?.get("access_token")?.asText()
    ?: throw IllegalStateException("Failed to obtain Reddit access token")
```

### Files Changed

- `modules/portfolio/.../proxy/YouTubeDataAdapter.kt` — removed `RestClient` from primary constructor, removed `internal constructor`, made `restClient` an `internal var` field, added `@Value` defaults
- `modules/portfolio/.../proxy/RedditDemandAdapter.kt` — removed `internal constructor`, made `authClient`/`apiClient` `internal var` fields, added `@Value` defaults, changed `path()` to `get()` for token extraction
- `modules/portfolio/.../proxy/YouTubeDataAdapterTest.kt` — changed all test setup from 5-arg constructor to 4-arg + `.also { it.restClient = mock }`
- `modules/portfolio/.../proxy/RedditDemandAdapterTest.kt` — changed all test setup from 8-arg internal constructor to primary constructor + `.also { it.authClient = mock; it.apiClient = mock }`

## Impact

- **CI was broken** for PR #25 across 3 commits until the fix landed
- **No production impact** — caught before merge
- **Bug 2 (Jackson path/get)** would have caused silent failure in production when Reddit returns auth errors — the adapter would return 0 candidates with no error, masking a credential/auth configuration problem
- **Bug 2 also exists** in the pre-existing `AmazonCreatorsApiAdapter` (now deactivated but code retained)

## Lessons Learned

### What went well
- **Unblocked's automated PR review** caught both the constructor issue and the Jackson `path()` bug immediately — before any human review
- **CI caught the regression** — `contextLoads()` in `:app:test` exercises the full Spring context and caught the bean creation failure
- **Local reproduction** was quick once the truncated CI trace was bypassed by reading the XML test report directly
- **E2E playbook** provided a comprehensive validation after fixes

### What could be improved
- **CI test output truncation** hid the real error (`Could not resolve placeholder`) behind a misleading `NoSuchMethodException`. The first fix attempt addressed the wrong root cause.
- **The `internal constructor` pattern was used by 2 of 3 implementation agents** — the prompt didn't warn against it, and the pattern was copied from `AmazonCreatorsApiAdapter` (which worked only because it was gated by `@ConditionalOnProperty` and never instantiated)
- **No static analysis or convention** prevents `@Value` annotations without defaults — every new adapter must remember to add `:` defaults
- **Jackson `path()` vs `get()` is a known footgun** but there's no lint rule or project convention documented for it
- **Portfolio unit tests don't catch bean instantiation issues** because they don't boot a Spring context — the gap is only covered by `:app:test`

## Prevention

- [ ] **Add CLAUDE.md constraint #13**: "All `@Value` annotations on adapter constructor parameters must include empty defaults (e.g., `${key:}`) so beans can instantiate under any Spring profile. Adapters must guard against blank values in `fetch()` with early return."
- [ ] **Add CLAUDE.md constraint #14**: "Never use Kotlin `internal constructor` on Spring `@Component` classes — Kotlin compiles `internal` to `public` with a synthetic `DefaultConstructorMarker` parameter that breaks Spring's constructor resolution. For test injection, use `internal var` fields set via `.also{}` in tests."
- [ ] **Add CLAUDE.md constraint #15**: "Use Jackson `get()` (returns null) instead of `path()` (returns MissingNode) when the absence of a field should trigger error handling via Kotlin's `?:` operator. `path().asText()` silently returns empty string for missing fields."
- [ ] **Fix `AmazonCreatorsApiAdapter.getAccessToken()` line 106** — same `path()` vs `get()` bug exists in the deactivated adapter. Fix proactively for when/if it's re-enabled.
- [ ] **Improve CI test output** — configure Gradle to print full exception messages (not just class+line) for failed tests, or always publish the HTML test report as a CI artifact for easier debugging.
- [ ] **Add `@SpringBootTest` smoke test to portfolio module** — a minimal context load test that catches bean instantiation failures without requiring the full `:app` module's database setup.
