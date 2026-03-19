# FR-018: Demand Signal API Pivot — Implementation Plan

**Linear issue:** RAT-22
**Feature directory:** `feature-requests/FR-018-demand-signal-api-pivot/`
**Spec:** `feature-requests/FR-018-demand-signal-api-pivot/spec.md`

---

## Technical Design

### Overview

This feature replaces the soon-to-be-defunct Amazon Creators API adapter with two new demand signal providers (YouTube Data API v3 and Reddit API) while deactivating — but not deleting — the existing Amazon adapter. The architecture is designed so that `DemandScanJob` requires zero code changes: new providers are discovered automatically via Spring's `List<DemandSignalProvider>` injection, and the Amazon adapter is suppressed via a configuration property gate.

### Components affected

| Component | Change type | File |
|---|---|---|
| `AmazonCreatorsApiAdapter` | Modify (add `@ConditionalOnProperty`) | `modules/portfolio/src/main/kotlin/.../proxy/AmazonCreatorsApiAdapter.kt` |
| `StubAmazonCreatorsApiProvider` | Modify (add `@ConditionalOnProperty`) | `modules/portfolio/src/main/kotlin/.../proxy/StubAmazonCreatorsApiProvider.kt` |
| `YouTubeDataAdapter` | Create | `modules/portfolio/src/main/kotlin/.../proxy/YouTubeDataAdapter.kt` |
| `StubYouTubeDataProvider` | Create | `modules/portfolio/src/main/kotlin/.../proxy/StubYouTubeDataProvider.kt` |
| `RedditDemandAdapter` | Create | `modules/portfolio/src/main/kotlin/.../proxy/RedditDemandAdapter.kt` |
| `StubRedditDemandProvider` | Create | `modules/portfolio/src/main/kotlin/.../proxy/StubRedditDemandProvider.kt` |
| `application.yml` | Modify (add YouTube, Reddit, Amazon toggle config) | `modules/app/src/main/resources/application.yml` |
| `application-local.yml` | No change needed | `modules/app/src/main/resources/application-local.yml` |
| `DemandScanJob` | **No change** | `modules/portfolio/src/main/kotlin/.../domain/service/DemandScanJob.kt` |
| `DemandSignalProvider` | **No change** | `modules/portfolio/src/main/kotlin/.../domain/DemandSignalProvider.kt` |
| `RawCandidate` | **No change** | `modules/portfolio/src/main/kotlin/.../domain/RawCandidate.kt` |
| `DemandScanConfig` | **No change** | `modules/portfolio/src/main/kotlin/.../config/DemandScanConfig.kt` |
| `build.gradle.kts` (portfolio) | **No change** (RestClient already available via `spring-boot-starter-web`) | `modules/portfolio/build.gradle.kts` |
| `StubProviderTest` | Modify (add YouTube + Reddit stub tests) | `modules/portfolio/src/test/.../proxy/StubProviderTest.kt` |
| `YouTubeDataAdapterTest` | Create | `modules/portfolio/src/test/.../proxy/YouTubeDataAdapterTest.kt` |
| `RedditDemandAdapterTest` | Create | `modules/portfolio/src/test/.../proxy/RedditDemandAdapterTest.kt` |
| `AmazonAdapterDeactivationTest` | Create | `modules/portfolio/src/test/.../proxy/AmazonAdapterDeactivationTest.kt` |

### Components explicitly NOT affected

- **`DemandScanJob`** — The job iterates over `List<DemandSignalProvider>` injected by Spring. Adding or removing providers is purely a bean registration concern. Zero code changes needed.
- **`CjDropshippingAdapter` / `StubCjDropshippingProvider`** — Supply-side signals, completely independent.
- **`GoogleTrendsAdapter` / `StubGoogleTrendsProvider`** — Trend-level signals, completely independent.
- **`DemandScanConfig`** — Provider-specific config (API keys, URLs, search terms) does not belong in the shared scan config. New config is injected directly via `@Value` on each adapter, matching the existing `CjDropshippingAdapter` pattern.
- **`build.gradle.kts`** — `spring-boot-starter-web` already provides `RestClient`, `@ConditionalOnProperty` comes from `spring-boot-autoconfigure` (transitive via `spring-boot-starter-web`), and `java.net.URLEncoder` is in the JDK. No new dependencies needed.

---

## Architecture Decisions

### AD-1: Amazon deactivation via `@ConditionalOnProperty` on the adapter classes

**Decision:** Add `@ConditionalOnProperty(name = ["amazon-creators.enabled"], havingValue = "true", matchIfMissing = false)` directly on both `AmazonCreatorsApiAdapter` and `StubAmazonCreatorsApiProvider`.

**Why this approach:**
- The adapter is the unit that needs to be toggled on/off. Placing the annotation directly on the class makes the deactivation co-located with the component definition — no indirection through a separate config class.
- `matchIfMissing = false` means the Amazon adapter is **off by default** unless explicitly enabled. This is the correct posture: the system should not attempt to use a deprecated API unless the operator explicitly opts in.
- Existing adapters (CJ, Google Trends) use `@Profile("!local")` / `@Profile("local")` for environment selection. `@ConditionalOnProperty` stacks with `@Profile` — both conditions must be true for the bean to load. The Amazon adapter already has `@Profile("!local")`; adding `@ConditionalOnProperty` gives fine-grained control without removing profile-based gating.

**Alternatives considered:**
- *Separate `@Configuration` class with `@Bean` methods:* Adds unnecessary indirection. The adapters are `@Component`s; wrapping them in a config class would require removing `@Component` and creating factory methods. Over-engineered for a simple enable/disable.
- *`@Profile("amazon")` instead of `@ConditionalOnProperty`:* Profile activation is a coarser mechanism. Using a property is more explicit and composable — operators can toggle individual providers without managing profile lists.

### AD-2: RestClient creation inline in each adapter (not via shared config bean)

**Decision:** Each new adapter creates its own `RestClient` inline in the constructor or as a field, following the existing `CjDropshippingAdapter` and `AmazonCreatorsApiAdapter` patterns.

**Why this approach:**
- The existing portfolio adapters all create `RestClient` instances inline. The `ExternalApiConfig` pattern (named beans) exists only in the `catalog` module for carrier/payment integrations. Following the established portfolio convention avoids architectural inconsistency within the module.
- YouTube and Reddit each have a single adapter consuming their RestClient. There is no bean-sharing benefit that would justify the indirection of a config class.
- The adapter owns the full lifecycle: base URL, default headers, and error handling are all adapter-specific.

**Alternatives considered:**
- *`DemandSignalApiConfig` class with named beans:* Would be appropriate if multiple classes in the portfolio module shared the same RestClient instance. Currently, each adapter is self-contained. Adding a config class increases file count and coupling without benefit.

### AD-3: Reddit OAuth token caching using ReentrantLock (same as Amazon adapter)

**Decision:** The `RedditDemandAdapter` uses the same `ReentrantLock` + `@Volatile` pattern for OAuth token caching that `AmazonCreatorsApiAdapter` already uses.

**Why this approach:**
- Proven pattern in the codebase. The Amazon adapter has been using this since FR-016.
- `DemandScanJob` runs on a single scheduled thread (Spring `@Scheduled`), so contention is unlikely, but the lock is cheap insurance for correctness if the adapter is ever called concurrently (e.g., from a REST endpoint for manual scans).
- Reddit's OAuth tokens (`access_token` with `expires_in`) follow the same structure as Amazon's, so the caching logic is nearly identical.

**Alternatives considered:**
- *Caffeine cache:* Already a dependency in the portfolio module. However, a single-entry cache with TTL is functionally identical to a volatile field + expiry timestamp, and adds API surface area for no benefit.
- *No caching (request token every call):* Reddit allows 60 req/min. A single scan calls `search` once per subreddit (4-8 calls). Getting a fresh token per search wastes a request on each call and adds latency. Since `expires_in` is typically 3600s and a scan completes in seconds, caching is clearly beneficial.

### AD-4: YouTube uses simple API key in query parameter

**Decision:** The `YouTubeDataAdapter` passes the API key as a `key` query parameter on every request. No OAuth flow, no token caching, no lock.

**Why:** YouTube Data API v3 supports API key authentication for public data access. This is the simplest authentication model — a single `@Value`-injected string appended to each request URL. No additional infrastructure needed.

### AD-5: Provider-specific configuration via `@Value` + `application.yml` sections

**Decision:** New adapter configuration (base URLs, API keys, search terms, subreddits) is added to `application.yml` under `youtube:` and `reddit:` top-level sections, and injected via `@Value` annotations on each adapter's constructor parameters. This matches the existing pattern used by `CjDropshippingAdapter`, `AmazonCreatorsApiAdapter`, and `GoogleTrendsAdapter`.

**Why not `DemandScanConfig`:** `DemandScanConfig` holds scan-level parameters (cooldown, scoring weights, thresholds) that apply across all providers. Per-provider API credentials and behavioral config (search terms, subreddits) are adapter concerns and should not pollute the shared scan config.

---

## Layer-by-Layer Implementation

### Layer 1: Configuration (`application.yml`)

Add YouTube and Reddit configuration sections with environment variable placeholders and sensible defaults. Add the Amazon toggle property defaulting to `false`.

```yaml
# YouTube Data API v3
youtube:
  api:
    base-url: ${YOUTUBE_API_BASE_URL:https://www.googleapis.com/youtube/v3}
    key: ${YOUTUBE_API_KEY:}
    search-terms:
      - "best product review"
      - "product unboxing 2026"
      - "top gadgets"
      - "must have kitchen gadgets"
    max-results-per-search: 10

# Reddit API
reddit:
  api:
    base-url: ${REDDIT_API_BASE_URL:https://oauth.reddit.com}
    auth-url: ${REDDIT_AUTH_URL:https://www.reddit.com/api/v1/access_token}
    client-id: ${REDDIT_CLIENT_ID:}
    client-secret: ${REDDIT_CLIENT_SECRET:}
    user-agent: ${REDDIT_USER_AGENT:AutoShipperAI/1.0}
    subreddits:
      - BuyItForLife
      - shutupandtakemymoney
      - gadgets
      - homeautomation
    sort: hot
    limit-per-subreddit: 25

# Amazon Creators API (deactivated by default — PA-API 5.0 deprecated May 2026)
amazon-creators:
  enabled: false
```

The existing `amazon-creators.api.*` properties remain unchanged for backward compatibility — they are only consumed if `amazon-creators.enabled=true`.

### Layer 2: Amazon adapter deactivation

#### `AmazonCreatorsApiAdapter.kt` — add `@ConditionalOnProperty`

Add the annotation to the existing class. No other changes to the file.

```kotlin
@Component
@Profile("!local")
@ConditionalOnProperty(name = ["amazon-creators.enabled"], havingValue = "true", matchIfMissing = false)
class AmazonCreatorsApiAdapter(
    // ... existing constructor unchanged
```

#### `StubAmazonCreatorsApiProvider.kt` — add `@ConditionalOnProperty`

Same gate on the stub so it also stays inactive under `local` profile by default.

```kotlin
@Component
@Profile("local")
@ConditionalOnProperty(name = ["amazon-creators.enabled"], havingValue = "true", matchIfMissing = false)
class StubAmazonCreatorsApiProvider : DemandSignalProvider {
    // ... existing code unchanged
```

### Layer 3: YouTube Data Adapter (real)

#### `YouTubeDataAdapter.kt`

New file: `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/YouTubeDataAdapter.kt`

Key design points:
- Implements `DemandSignalProvider`, `sourceType() = "YOUTUBE_DATA"`
- `@Component` + `@Profile("!local")` (matches CJ/Google Trends pattern)
- Injects via `@Value`: base URL, API key, search terms (as `List<String>`), max results per search
- Creates `RestClient` inline with `baseUrl`
- `fetch()` iterates over configurable search terms, calling YouTube `search.list` endpoint for each
- For each search result, calls `videos.list` to get `statistics` (viewCount, likeCount, commentCount) and `snippet` (channelId, publishedAt)
- Optionally calls `channels.list` for `subscriberCount` (only if quota allows — see quota note below)
- Maps each video to a `RawCandidate`:
  - `productName` = video title (cleaned of common prefixes like "Review:", "Best")
  - `category` = "Product Review" (or derived from video category if available)
  - `description` = "YouTube: {searchTerm}"
  - `sourceType` = "YOUTUBE_DATA"
  - `supplierUnitCost` = null (demand-side signal only)
  - `estimatedSellingPrice` = null (demand-side signal only)
  - `demandSignals` = `{ video_id, view_count, like_count, comment_count, channel_subscriber_count, publish_date, search_term }`
- Error handling: each search term wrapped in try-catch, logs warning on failure, continues to next term. Returns partial results.

**YouTube API quota budget:** `search.list` = 100 units/call. `videos.list` = 1 unit/call. `channels.list` = 1 unit/call. With 4 search terms and 10 results each: 4 x 100 (search) + 4 x 1 (videos batch) + 4 x 1 (channels batch) = 408 units/day, well within the 10,000 unit daily limit. The adapter batches video IDs and channel IDs into single `videos.list` and `channels.list` calls per search term to minimize quota usage.

### Layer 4: Reddit Demand Adapter (real)

#### `RedditDemandAdapter.kt`

New file: `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/RedditDemandAdapter.kt`

Key design points:
- Implements `DemandSignalProvider`, `sourceType() = "REDDIT"`
- `@Component` + `@Profile("!local")` (matches existing pattern)
- Injects via `@Value`: base URL, auth URL, client ID, client secret, user agent, subreddits (as `List<String>`), sort order, limit per subreddit
- Creates two `RestClient` instances: one for OAuth token requests (`https://www.reddit.com`), one for API calls (`https://oauth.reddit.com`)
- OAuth token caching using `ReentrantLock` + `@Volatile` (same pattern as `AmazonCreatorsApiAdapter`):
  - POST to `https://www.reddit.com/api/v1/access_token`
  - Content-Type: `application/x-www-form-urlencoded`
  - Body: `grant_type=client_credentials` (URL-encoded per CLAUDE.md #12)
  - Basic auth header with client_id:client_secret
  - Caches token until `expires_in - 60` seconds
- `fetch()` iterates over configurable subreddits, calling `GET /r/{subreddit}/{sort}` for each
- Maps each post to a `RawCandidate`:
  - `productName` = post title (cleaned)
  - `category` = subreddit name
  - `description` = "Reddit r/{subreddit}: {title truncated}"
  - `sourceType` = "REDDIT"
  - `supplierUnitCost` = null
  - `estimatedSellingPrice` = null
  - `demandSignals` = `{ post_id, subreddit, upvote_count, comment_count, post_age_hours, subreddit_subscribers }`
- Error handling: each subreddit wrapped in try-catch, logs warning on failure, continues. Returns partial results.
- **CLAUDE.md #12 compliance:** All values interpolated into form-encoded request bodies must use `URLEncoder.encode(value, StandardCharsets.UTF_8)`. In practice for the Reddit OAuth flow, the only body parameter values are `grant_type=client_credentials` (a static literal, not user-supplied). However, since the `client_id` and `client_secret` are injected from environment variables and could theoretically contain special characters, the adapter URL-encodes them when constructing the Basic auth header (Base64 of `URLEncoder.encode(clientId):URLEncoder.encode(clientSecret)`). The form body itself uses only the static literal `grant_type=client_credentials`.

**Correction on #12 application:** After re-reading the constraint — "URL-encode user-supplied values in form-encoded request bodies" — the client_id and client_secret go in the Basic auth header, not the form body. The form body only contains `grant_type=client_credentials` which is a static string. If any user-supplied values were ever added to the form body, they must be URL-encoded. The adapter will use `URLEncoder.encode()` for the `grant_type` value as a defensive measure, demonstrating the pattern even though the current value is a safe literal.

### Layer 5: Stub providers

#### `StubYouTubeDataProvider.kt`

New file: `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/StubYouTubeDataProvider.kt`

- `@Component` + `@Profile("local")`
- `sourceType() = "YOUTUBE_DATA"`
- Returns 3 deterministic `RawCandidate` instances with YouTube-shaped demand signals:
  - Each has `video_id`, `view_count`, `like_count`, `comment_count`, `channel_subscriber_count`, `publish_date`, `search_term`
  - `supplierUnitCost = null`, `estimatedSellingPrice = null`
  - Product names reflecting product review content (e.g., "Portable Bluetooth Speaker Review", "Best Kitchen Scale 2026", "Top 5 Wireless Earbuds")

#### `StubRedditDemandProvider.kt`

New file: `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/StubRedditDemandProvider.kt`

- `@Component` + `@Profile("local")`
- `sourceType() = "REDDIT"`
- Returns 3 deterministic `RawCandidate` instances with Reddit-shaped demand signals:
  - Each has `post_id`, `subreddit`, `upvote_count`, `comment_count`, `post_age_hours`, `subreddit_subscribers`
  - `supplierUnitCost = null`, `estimatedSellingPrice = null`
  - Product names reflecting organic recommendations (e.g., "This cast iron skillet changed my cooking", "Finally found a good USB-C hub", "Recommend: ergonomic keyboard for WFH")

### Layer 6: Tests

#### `YouTubeDataAdapterTest.kt`

New file: `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/YouTubeDataAdapterTest.kt`

Unit tests using Mockito to mock RestClient responses:
1. **Happy path** — mock YouTube API responses for search, videos, and channels endpoints; verify correct `RawCandidate` mapping including all demand signal fields
2. **Video title mapping** — verify `productName` is derived from video title
3. **Demand signals completeness** — verify all 7 signal keys present: `video_id`, `view_count`, `like_count`, `comment_count`, `channel_subscriber_count`, `publish_date`, `search_term`
4. **Source type** — verify `sourceType() == "YOUTUBE_DATA"`
5. **Partial failure** — one search term fails, others succeed; verify partial results returned
6. **Empty response** — API returns no items; verify empty list returned (no exception)
7. **Null/missing fields** — video missing statistics; verify graceful defaults

Test approach: Since `YouTubeDataAdapter` creates its `RestClient` inline, the tests will construct the adapter with a mock `RestClient.Builder` or use constructor injection of a pre-built `RestClient` via a `@VisibleForTesting` secondary constructor. Alternatively, the test can use `MockRestServiceServer` if the adapter accepts a `RestClient.Builder` parameter. The simplest approach matching existing patterns (CJ adapter creates RestClient inline from `baseUrl`) is to test at a slightly higher level: pass the adapter a `baseUrl` pointing to a WireMock/mock server, or refactor the adapter to accept a `RestClient` parameter for testability. Given that the existing codebase has no WireMock dependency and tests use Mockito, the recommended approach is:

- Accept `RestClient` as an optional constructor parameter with a default that builds from `baseUrl`
- In tests, inject a mocked `RestClient` directly

This matches how the codebase could be tested without adding new test dependencies.

**Revised test approach:** Use a package-private/internal constructor that accepts a `RestClient` for testing, while the primary Spring constructor creates one from `baseUrl`. This is the pragmatic approach given the existing test infrastructure (Mockito only, no WireMock).

#### `RedditDemandAdapterTest.kt`

New file: `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/RedditDemandAdapterTest.kt`

Unit tests:
1. **OAuth token acquisition** — mock auth endpoint response; verify token is extracted and cached
2. **Token caching** — call fetch() twice; verify auth endpoint called only once (token reused)
3. **Token refresh** — simulate expired token; verify fresh token requested
4. **Happy path** — mock subreddit responses; verify correct `RawCandidate` mapping
5. **Demand signals completeness** — verify all 6 signal keys: `post_id`, `subreddit`, `upvote_count`, `comment_count`, `post_age_hours`, `subreddit_subscribers`
6. **Source type** — verify `sourceType() == "REDDIT"`
7. **Partial failure** — one subreddit fetch fails; verify others succeed
8. **URLEncoder compliance** — verify form body values are URL-encoded (CLAUDE.md #12)

Same test approach as YouTube: internal/test constructor accepting pre-built `RestClient` instances.

#### `AmazonAdapterDeactivationTest.kt`

New file: `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/AmazonAdapterDeactivationTest.kt`

This is a Spring context test that verifies `@ConditionalOnProperty` gating works:
1. **Default config (no property set)** — verify `AmazonCreatorsApiAdapter` bean is NOT present in context
2. **Explicitly disabled** — set `amazon-creators.enabled=false`; verify bean absent
3. **Explicitly enabled** — set `amazon-creators.enabled=true`; verify bean IS present

Uses `@SpringBootTest` with `@TestPropertySource` or `ApplicationContextRunner` from Spring Boot test support.

**Note:** `ApplicationContextRunner` is the lighter-weight approach and avoids full Spring Boot startup. It is available from `spring-boot-test-autoconfigure` (already transitively available via `spring-boot-starter-test`).

#### `StubProviderTest.kt` (modify existing)

Add test methods for the new stubs:
- `StubYouTubeDataProvider` — returns non-empty list, correct source type, all candidates have YouTube-specific demand signal keys
- `StubRedditDemandProvider` — returns non-empty list, correct source type, all candidates have Reddit-specific demand signal keys

---

## Task Breakdown

### Configuration

- [x] Add `youtube:` section to `modules/app/src/main/resources/application.yml` with base-url, key, search-terms list, max-results-per-search (all with `${ENV_VAR:default}` syntax)
- [x] Add `reddit:` section to `modules/app/src/main/resources/application.yml` with base-url, auth-url, client-id, client-secret, user-agent, subreddits list, sort, limit-per-subreddit
- [x] Add `amazon-creators.enabled: false` to `modules/app/src/main/resources/application.yml` (default off)

### Proxy — Amazon deactivation

- [x] Add `@ConditionalOnProperty(name = ["amazon-creators.enabled"], havingValue = "true", matchIfMissing = false)` to `AmazonCreatorsApiAdapter` in `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/AmazonCreatorsApiAdapter.kt`
- [x] Add `@ConditionalOnProperty(name = ["amazon-creators.enabled"], havingValue = "true", matchIfMissing = false)` to `StubAmazonCreatorsApiProvider` in `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/StubAmazonCreatorsApiProvider.kt`

### Proxy — YouTube adapter (real)

- [x] Create `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/YouTubeDataAdapter.kt` implementing `DemandSignalProvider` with `@Component` + `@Profile("!local")`
- [x] Implement `sourceType()` returning `"YOUTUBE_DATA"`
- [x] Implement `fetch()` — iterate search terms, call YouTube `search.list`, batch `videos.list` for statistics, batch `channels.list` for subscriber counts
- [x] Map YouTube API responses to `RawCandidate` with demand signals: `video_id`, `view_count`, `like_count`, `comment_count`, `channel_subscriber_count`, `publish_date`, `search_term`
- [x] Add per-search-term try-catch error handling with logging

### Proxy — Reddit adapter (real)

- [x] Create `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/RedditDemandAdapter.kt` implementing `DemandSignalProvider` with `@Component` + `@Profile("!local")`
- [x] Implement `sourceType()` returning `"REDDIT"`
- [x] Implement OAuth 2.0 client credentials token acquisition with `ReentrantLock` + `@Volatile` caching pattern
- [x] Ensure `URLEncoder.encode()` is used for any values in form-encoded request bodies (CLAUDE.md #12)
- [x] Implement `fetch()` — iterate configurable subreddits, call `GET /r/{subreddit}/{sort}`, map posts to `RawCandidate`
- [x] Map Reddit API responses to `RawCandidate` with demand signals: `post_id`, `subreddit`, `upvote_count`, `comment_count`, `post_age_hours`, `subreddit_subscribers`
- [x] Add per-subreddit try-catch error handling with logging

### Proxy — Stubs

- [x] Create `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/StubYouTubeDataProvider.kt` with `@Component` + `@Profile("local")`, returning 3 deterministic YouTube-shaped `RawCandidate` instances
- [x] Create `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/StubRedditDemandProvider.kt` with `@Component` + `@Profile("local")`, returning 3 deterministic Reddit-shaped `RawCandidate` instances

### Tests

- [x] Create `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/YouTubeDataAdapterTest.kt` — happy path, field mapping, demand signals completeness, partial failure, empty response, null field handling (6 tests)
- [x] Create `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/RedditDemandAdapterTest.kt` — OAuth token acquisition, token caching, token refresh, happy path, demand signals completeness, partial failure, URL encoding compliance (8 tests)
- [x] Create `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/AmazonAdapterDeactivationTest.kt` — verify `@ConditionalOnProperty` gating (2 tests)
- [x] Add YouTube stub tests to `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/StubProviderTest.kt` — non-empty list, correct source type, demand signal keys (3 tests)
- [x] Add Reddit stub tests to `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/StubProviderTest.kt` — non-empty list, correct source type, demand signal keys (3 tests)
- [x] Verify all existing tests pass without modification (run `./gradlew test`) — all modules pass

### Documentation

- [x] Verify `.env.example` already contains `YOUTUBE_API_KEY`, `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET` (confirmed — already present)

---

## Testing Strategy

### Unit tests (Mockito)

| Test class | Scope | Key assertions |
|---|---|---|
| `YouTubeDataAdapterTest` | YouTube API response parsing, RawCandidate mapping | All 7 demand signal keys present; productName from title; supplierUnitCost is null; partial failure resilience |
| `RedditDemandAdapterTest` | Reddit API response parsing, OAuth flow, RawCandidate mapping | All 6 demand signal keys present; token caching works; URLEncoder compliance; partial failure resilience |
| `AmazonAdapterDeactivationTest` | `@ConditionalOnProperty` bean gating | Bean absent by default; present when `amazon-creators.enabled=true` |
| `StubProviderTest` (extended) | Stub deterministic output | Non-empty lists; correct source types; expected demand signal keys |

### Existing tests (regression)

All existing tests must continue to pass:
- `DemandScanJobTest` — uses mocked `DemandSignalProvider` instances, no dependency on real adapters
- `StubProviderTest` — existing CJ/Google Trends/Amazon tests unchanged (Amazon tests will still pass because `StubAmazonCreatorsApiProvider` is instantiated directly in the test, not via Spring context)
- All other portfolio tests (ExperimentServiceTest, KillWindowMonitorTest, etc.) are unaffected

### What is NOT tested

- Live API integration tests (YouTube, Reddit) — these require real API credentials and would be flaky in CI. The adapter architecture is validated by unit tests with mocked responses. Live connectivity is validated manually during deployment.
- Rate limiting enforcement — YouTube quota and Reddit rate limits are respected by design (configurable search term counts, limit per subreddit). Enforcement is the API provider's responsibility.

---

## Rollout Plan

### Pre-deployment checklist

1. Run `./gradlew test` — all existing + new tests pass
2. Verify `application.yml` has `amazon-creators.enabled: false`
3. Verify `.env.example` documents `YOUTUBE_API_KEY`, `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`

### Deployment steps

1. **Merge PR** — Amazon adapter is immediately deactivated (default `enabled: false`). YouTube and Reddit adapters are available but require API keys.
2. **Set environment variables** — Add `YOUTUBE_API_KEY`, `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET` to the deployment environment.
3. **Deploy** — On next startup, Spring discovers `YouTubeDataAdapter` and `RedditDemandAdapter` via component scan. `DemandScanJob` picks them up automatically.
4. **Verify** — Wait for the next 3 AM scan (or trigger manually). Check logs for:
   - `"DemandScanJob started with N providers"` — N should include YouTube + Reddit but not Amazon
   - `"Source 'YOUTUBE_DATA' returned X candidates"`
   - `"Source 'REDDIT' returned X candidates"`
   - No `"Source 'AMAZON_CREATORS_API'..."` line (adapter not loaded)

### Rollback procedure

- **YouTube/Reddit adapter failure:** Each adapter's failures are isolated by `DemandScanJob.collectFromSources()` try-catch. A broken adapter returns 0 candidates but does not break the scan. No rollback needed — fix the adapter.
- **Need to re-enable Amazon:** Set `amazon-creators.enabled: true` in environment config and restart. The adapter code is intact and will activate.
- **Full rollback:** Revert the PR. The Amazon adapter returns to its original unconditional `@Component` + `@Profile("!local")` state.

### Post-deployment monitoring

- Watch demand scan run logs for the first 3 daily cycles after deployment
- Verify `DemandCandidate` records appear with `sourceType = 'YOUTUBE_DATA'` and `sourceType = 'REDDIT'`
- Verify no records with `sourceType = 'AMAZON_CREATORS_API'` are created (adapter deactivated)
- Monitor YouTube API quota usage in Google Cloud Console (should be well under 10,000 units/day)
