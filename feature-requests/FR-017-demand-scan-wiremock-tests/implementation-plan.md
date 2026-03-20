# FR-017: Demand Scan WireMock Tests & Smoke-Test Endpoint — Implementation Plan

## Technical Design

### Overview

Two complementary testing/verification layers for the four demand signal adapters:

1. **WireMock integration tests** — replay recorded HTTP responses against real adapter code in CI (no network, no API keys)
2. **Smoke-test REST endpoint** — post-deploy probe that hits real providers and reports structured status

### Architecture

```
┌─────────────────── Test Infrastructure ───────────────────┐
│                                                           │
│  WireMock JUnit 5 Extension                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │
│  │ CJ Adapter   │  │ Trends       │  │ YouTube      │    │
│  │ Test         │  │ Adapter Test │  │ Adapter Test │    │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘    │
│         │                 │                 │             │
│  ┌──────┴─────────────────┴─────────────────┴──────────┐  │
│  │              WireMock Server (per-class)             │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌──────────────┐                                         │
│  │ Reddit       │  (WireMock serves both OAuth +          │
│  │ Adapter Test │   API endpoints on same port)           │
│  └──────────────┘                                         │
└───────────────────────────────────────────────────────────┘

┌─────────────────── Smoke-Test Endpoint ───────────────────┐
│                                                           │
│  POST /api/portfolio/demand-scan/smoke-test               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │ DemandScanSmokeController                           │  │
│  │  @ConditionalOnProperty(smoke-test-enabled)         │  │
│  │  Rate-limited: 1 req/min                            │  │
│  └────────────┬────────────────────────────────────────┘  │
│               │                                           │
│  ┌────────────▼────────────────────────────────────────┐  │
│  │ DemandScanSmokeService                              │  │
│  │  Injects List<DemandSignalProvider>                 │  │
│  │  Runs all providers concurrently (bounded pool)     │  │
│  │  Maps exceptions → SmokeStatus enum                 │  │
│  │  Per-source timeout (10s default)                   │  │
│  └─────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────┘
```

## Architecture Decisions

1. **WireMock over Mockito for these tests** — The existing YouTube/Reddit tests mock the RestClient chain at the Java API level. WireMock tests exercise the full HTTP layer: URL construction, header serialization, content-type negotiation, and response deserialization. Both test styles are valuable; WireMock catches HTTP-level regressions that Mockito tests miss.

2. **One WireMock server per test class** — `@RegisterExtension static` with `WireMockExtension.newInstance()`. Server starts once per class, stubs reset between tests. Faster than per-test server startup.

3. **Adapter wiring in WireMock tests** — Each adapter already exposes its RestClient as `internal var` (YouTube, Reddit) or uses a base URL constructor param (CJ). For Google Trends, we override the URL via constructor reflection or a test-friendly factory. Each test constructs the real adapter, pointing its base URL at the WireMock server port.

4. **Smoke-test as separate @ConditionalOnProperty bean** — When `demand-scan.smoke-test-enabled=false` (default), the controller bean is not registered at all, so the endpoint returns 404 from Spring's default handler. No custom 404 logic needed.

5. **Smoke status as sealed interface** — `SmokeStatus` enum with fixed values. Raw exception messages are caught and mapped internally, never serialized to the response.

6. **Rate limiting via in-memory token bucket** — Simple `AtomicReference<Instant>` tracking last invocation. No external dependency needed for 1 req/min on a single-instance endpoint.

7. **Dynamic provider-count thread pool** — Smoke service sizes its thread pool to `providers.size` (not hardcoded 4) so it auto-scales as new adapters are added without code changes.

8. **Shared WireMock test base class** — `WireMockAdapterTestBase` provides common setup (server lifecycle, fixture loading, `loadFixture(path)` helper) and shared assertion helpers (`assertValidRawCandidates(candidates, expectedSource)` — verifies non-empty productName, correct sourceType, non-null demandSignals). Concrete test classes extend it and only define adapter-specific stubs and assertions. This keeps the per-adapter test cost low as we add Amazon, Etsy, TikTok Shop, GA4, etc.

## Layer-by-Layer Implementation

### Test Infrastructure (WireMock dependency + fixtures)

**build.gradle.kts change:**
Add `testImplementation("org.wiremock:wiremock-standalone:3.4.2")` to `modules/portfolio/build.gradle.kts`.

**Fixture files** under `modules/portfolio/src/test/resources/wiremock/`:

| File | Contents |
|---|---|
| `cj/product-list-success.json` | CJ product list response with 3+ products, realistic nested structure |
| `cj/product-list-empty.json` | CJ response with empty product list |
| `cj/error-401.json` | CJ unauthorized response body |
| `cj/error-429.json` | CJ rate-limited response body |
| `trends/rss-feed-success.xml` | Google Trends RSS with 3+ `<item>` elements, `ht:approx_traffic` |
| `trends/rss-feed-empty.xml` | Valid RSS with zero `<item>` elements |
| `trends/rss-feed-malformed.xml` | Broken XML (unclosed tags) |
| `youtube/search-success.json` | YouTube search.list response with 3+ video results |
| `youtube/videos-success.json` | YouTube videos.list response with statistics for matching IDs |
| `youtube/channels-success.json` | YouTube channels.list response with subscriber counts |
| `youtube/error-403-invalid-key.json` | YouTube 403 with "keyInvalid" error reason |
| `youtube/error-403-quota.json` | YouTube 403 with "quotaExceeded" error reason |
| `reddit/token-success.json` | Reddit OAuth token response (`access_token`, `expires_in`) |
| `reddit/token-error-401.json` | Reddit OAuth 401 response |
| `reddit/subreddit-success.json` | Reddit listing with 3+ posts, full `data.children[].data` structure |
| `reddit/error-429.json` | Reddit 429 rate-limited response |

All fixtures must use synthetic values: `"accessToken": "test-token-xxx"`, `"pid": "CJ-TEST-001"`, etc.

### WireMock Test Classes

#### CjDropshippingAdapterWireMockTest

- Construct `CjDropshippingAdapter` with `baseUrl = wireMock.baseUrl()` and `accessToken = "test-token"`
- **Happy path**: stub `/product/listV2` → 200 + `product-list-success.json`. Assert `RawCandidate` count, `productName`, `supplierUnitCost` (Money with correct amount/currency), `estimatedSellingPrice` (2.5x), `demandSignals` keys (`cj_pid`, `cj_category_id`, `cj_product_image`), `sourceType` = "CJ_DROPSHIPPING"
- **Empty response**: stub → 200 + `product-list-empty.json`. Assert empty list returned
- **401 Unauthorized**: stub → 401 + `error-401.json`. Assert empty list (not exception)
- **429 Rate limited**: stub → 429 + `error-429.json`. Assert empty list (not exception)
- **Malformed JSON**: stub → 200 + `{"broken":`. Assert empty list (not exception)

#### GoogleTrendsAdapterWireMockTest

- Google Trends adapter fetches from a hardcoded URL (`https://trends.google.com/...`). The adapter constructs a `DocumentBuilder` and parses the URL stream directly — no RestClient. For WireMock testing, we need to make the URL configurable. **Approach**: Add an `internal var feedUrl: String` field (same pattern as YouTube/Reddit RestClient injection) that defaults to the real URL but can be overridden in tests to point at WireMock.
- **Happy path**: WireMock serves `rss-feed-success.xml` at the stubbed path. Assert `RawCandidate` count, `productName` from `<title>`, `demandSignals` keys (`approx_traffic`, `trend_date`, `geo`), null `supplierUnitCost`/`estimatedSellingPrice`, `sourceType` = "GOOGLE_TRENDS"
- **Empty feed**: stub → 200 + `rss-feed-empty.xml`. Assert empty list
- **Malformed XML**: stub → 200 + `rss-feed-malformed.xml`. Assert empty list (not exception)

#### YouTubeDataAdapterWireMockTest

- Construct `YouTubeDataAdapter` with `baseUrl = wireMock.baseUrl()`, `apiKey = "test-key"`, `searchTerms = "test product"`
- **Happy path**: stub `/search` → `search-success.json`, `/videos` → `videos-success.json`, `/channels` → `channels-success.json`. Assert `RawCandidate` fields: `productName`, all 7 `demandSignals` keys (`video_id`, `view_count`, `like_count`, `comment_count`, `channel_subscriber_count`, `publish_date`, `search_term`), `sourceType` = "YOUTUBE_DATA"
- **403 Invalid API key**: stub `/search` → 403 + `error-403-invalid-key.json`. Assert empty list
- **403 Quota exceeded**: stub `/search` → 403 + `error-403-quota.json`. Assert empty list
- **Malformed JSON**: stub `/search` → 200 + `{"broken":`. Assert empty list

#### RedditDemandAdapterWireMockTest

- Construct `RedditDemandAdapter` with `baseUrl = wireMock.baseUrl()`, `authUrl = wireMock.baseUrl()`, `clientId = "test-id"`, `clientSecret = "test-secret"`, subreddits = "TestSub"
- **Happy path**: stub auth endpoint → 200 + `token-success.json`, stub `/r/TestSub/hot` → 200 + `subreddit-success.json`. Assert OAuth request has correct `Authorization: Basic` header and form-encoded body with `URLEncoder.encode()` compliance. Assert `RawCandidate` fields: `productName`, all 6 `demandSignals` keys (`post_id`, `subreddit`, `upvote_count`, `comment_count`, `post_age_hours`, `subreddit_subscribers`), `sourceType` = "REDDIT"
- **Token caching**: call `fetch()` twice. Assert WireMock received exactly 1 request to the auth endpoint (token reused)
- **OAuth auth failure**: stub auth → 401 + `token-error-401.json`. Assert empty list (not exception). **Note**: Current adapter throws `IllegalStateException` on auth failure — test may reveal this needs a try-catch fix to match spec's "return empty list" requirement.
- **429 Rate limited**: stub auth → 200 + token, stub subreddit → 429. Assert empty list

### Smoke-Test Endpoint (Production code)

#### Domain types — `modules/portfolio/src/main/kotlin/.../domain/`

```kotlin
enum class SmokeStatus {
    OK, AUTH_FAILURE, TIMEOUT, RATE_LIMITED, PARSE_ERROR, UNKNOWN_ERROR
}

data class SourceSmokeResult(
    val source: String,
    val status: SmokeStatus,
    val responseTimeMs: Long,
    val sampleCount: Int
)

data class SmokeTestReport(
    val results: List<SourceSmokeResult>,
    val timestamp: Instant
)
```

#### Service — `modules/portfolio/src/main/kotlin/.../domain/DemandScanSmokeService.kt`

- Inject `List<DemandSignalProvider>` (all 4 real + stub providers available)
- `runSmokeTest(): SmokeTestReport`
  - Create bounded thread pool (`Executors.newFixedThreadPool(providers.size)`)
  - Submit each provider's `fetch()` as a `CompletableFuture` with timeout (configurable, default 10s)
  - Map results:
    - Success → `SmokeStatus.OK`, sampleCount = candidates.size
    - `TimeoutException` → `SmokeStatus.TIMEOUT`
    - `HttpClientErrorException(401/403)` → `SmokeStatus.AUTH_FAILURE`
    - `HttpClientErrorException(429)` → `SmokeStatus.RATE_LIMITED`
    - Parse-related exceptions (`JsonProcessingException`, `SAXException`) → `SmokeStatus.PARSE_ERROR`
    - All other → `SmokeStatus.UNKNOWN_ERROR`
  - Measure elapsed time per provider
  - Shut down thread pool after use

#### Controller — `modules/portfolio/src/main/kotlin/.../handler/DemandScanSmokeController.kt`

- `@RestController` + `@ConditionalOnProperty(name = ["demand-scan.smoke-test-enabled"], havingValue = "true")`
- `POST /api/portfolio/demand-scan/smoke-test`
- Rate limiting: `AtomicReference<Instant>` tracking last call. If < 60s since last, return 429 with message.
- Delegates to `DemandScanSmokeService`
- Returns `SmokeTestReport` as JSON

#### Configuration — `modules/app/src/main/resources/application.yml`

```yaml
demand-scan:
  smoke-test-enabled: false
  smoke-test-timeout-seconds: 10
```

### Smoke-Test Endpoint Tests

#### DemandScanSmokeServiceTest

- Mock `List<DemandSignalProvider>` with controlled behavior
- **All OK**: all providers return candidates → all `SmokeStatus.OK` with correct sample counts
- **Timeout**: one provider blocks forever → `SmokeStatus.TIMEOUT` for that source
- **Auth failure**: provider throws 401 → `SmokeStatus.AUTH_FAILURE`
- **Rate limited**: provider throws 429 → `SmokeStatus.RATE_LIMITED`
- **Parse error**: provider throws `JsonProcessingException` → `SmokeStatus.PARSE_ERROR`
- **Mixed results**: combination of OK/TIMEOUT/ERROR → correct per-source mapping

#### DemandScanSmokeControllerTest (MockMvc / WebMvcTest)

- **Happy path**: mock service returns report → 200 with correct JSON structure
- **Disabled endpoint**: without `smoke-test-enabled=true` → 404
- **Rate limited**: two rapid calls → second returns 429
- **Unauthenticated**: no auth → 401 (Spring Security)
- **No raw errors in response**: verify response body contains only enum status values

### Adapter Fix (if needed)

The `RedditDemandAdapter.getAccessToken()` currently throws `IllegalStateException` when the token endpoint returns a non-200 response. The spec requires that auth failures return an empty list, not throw. If WireMock tests confirm this throws, wrap `getAccessToken()` call in `fetch()` with a try-catch that logs a warning and returns empty list.

## Task Breakdown

### Test Infrastructure
- [x] Add WireMock dependency to `modules/portfolio/build.gradle.kts`
- [x] Create `WireMockAdapterTestBase` with shared server lifecycle, `loadFixture(path)` helper, and `assertValidRawCandidates()` assertion
- [x] Create fixture directory `modules/portfolio/src/test/resources/wiremock/` with subdirs `cj/`, `trends/`, `youtube/`, `reddit/`
- [x] Write all 16 fixture files (JSON + XML) with synthetic data

### CJ Dropshipping WireMock Tests
- [x] Write `CjDropshippingAdapterWireMockTest` — happy path
- [x] Write `CjDropshippingAdapterWireMockTest` — error cases (401, 429, malformed JSON, empty response)

### Google Trends WireMock Tests
- [x] Add `internal var feedUrl` to `GoogleTrendsAdapter` for test-friendly URL override
- [x] Write `GoogleTrendsAdapterWireMockTest` — happy path
- [x] Write `GoogleTrendsAdapterWireMockTest` — error cases (malformed XML, empty feed)

### YouTube Data WireMock Tests
- [x] Write `YouTubeDataAdapterWireMockTest` — happy path (search + videos + channels)
- [x] Write `YouTubeDataAdapterWireMockTest` — error cases (403 invalid key, 403 quota, malformed JSON)

### Reddit Demand WireMock Tests
- [x] Write `RedditDemandAdapterWireMockTest` — happy path (OAuth + subreddit fetch)
- [x] Write `RedditDemandAdapterWireMockTest` — token caching (1 auth request across 2 fetches)
- [x] Write `RedditDemandAdapterWireMockTest` — error cases (OAuth 401, 429 rate limit)
- [x] Fix `RedditDemandAdapter.fetch()` to catch auth failure and return empty list (if WireMock tests confirm it throws)

### Smoke-Test Endpoint
- [x] Create `SmokeStatus` enum + `SourceSmokeResult` + `SmokeTestReport` domain types
- [x] Create `DemandScanSmokeService` with concurrent provider execution and exception-to-status mapping
- [x] Create `DemandScanSmokeController` with `@ConditionalOnProperty` + rate limiting
- [x] Add `demand-scan.smoke-test-enabled` and `smoke-test-timeout-seconds` to `application.yml`

### Smoke-Test Tests
- [x] Write `DemandScanSmokeServiceTest` — all status mappings (OK, TIMEOUT, AUTH_FAILURE, RATE_LIMITED, PARSE_ERROR, UNKNOWN_ERROR)
- [x] Write `DemandScanSmokeControllerTest` — endpoint behavior (200, 404 when disabled, 429 rate limit, 401 unauthenticated, no raw errors)

### Verification
- [x] Run `./gradlew test` — all tests pass without API keys or network
- [x] Verify no real credentials in any fixture file

## Testing Strategy

### Unit tests (Mockito)
- `DemandScanSmokeServiceTest` — mock providers, verify status mapping logic
- `DemandScanSmokeControllerTest` — MockMvc, verify HTTP behavior and rate limiting

### Integration tests (WireMock)
- 4 adapter WireMock test classes — real adapter code against recorded HTTP responses
- Each covers happy path + 2-4 error scenarios
- No Spring context needed — adapters instantiated directly with WireMock base URL

### Test count estimate
- ~5 CJ tests + ~3 Trends tests + ~4 YouTube tests + ~4 Reddit tests = ~16 WireMock tests
- ~6 service tests + ~5 controller tests = ~11 smoke-test tests
- **Total: ~27 new tests**

## Rollout Plan

1. Add WireMock dependency and fixtures — no production impact
2. Write WireMock tests — validates existing adapter code, may reveal bugs
3. Fix any adapter bugs found by WireMock tests (e.g., Reddit auth failure handling)
4. Add smoke-test endpoint (disabled by default) — no impact until enabled
5. Enable smoke-test in staging/production via config: `demand-scan.smoke-test-enabled=true`
6. Run `POST /api/portfolio/demand-scan/smoke-test` post-deploy to verify all providers
