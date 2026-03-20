# FR-017: Demand Scan WireMock Tests & Smoke-Test Endpoint — Summary

## Feature Summary

Added WireMock-based integration tests for all four demand signal adapters (CJ Dropshipping, Google Trends, YouTube Data, Reddit) and a `POST /api/portfolio/demand-scan/smoke-test` endpoint for post-deploy health verification. The WireMock tests exercise real adapter code against recorded HTTP/XML responses in CI without API keys or network access. The smoke-test endpoint hits real providers concurrently and returns structured per-source status reports with enumerated error codes.

All 16 fixture files were verified against official API documentation to ensure they match real response contracts — not reverse-engineered from adapter code.

## Changes Made

### Production Code
- **GoogleTrendsAdapter** — added `internal var feedUrl` field for WireMock test injection (default remains the real Google Trends URL)
- **RedditDemandAdapter** — two fixes:
  1. Wrapped `getAccessToken()` call in `fetch()` with try-catch so OAuth auth failures return an empty list instead of throwing `IllegalStateException`
  2. Fixed `expires_in` default fallback from `86400` to `3600` — Reddit tokens expire after 1 hour, not 24 hours. The previous default would have cached an expired token for 23 hours if the field was missing from the response.
- **SmokeTestTypes.kt** — new `SmokeStatus` enum (`OK`, `AUTH_FAILURE`, `TIMEOUT`, `RATE_LIMITED`, `PARSE_ERROR`, `UNKNOWN_ERROR`), `SourceSmokeResult`, `SmokeTestReport`
- **DemandScanSmokeService** — concurrent provider probing with dynamic thread pool (`providers.size`), per-source timeout (configurable, default 10s), exception-to-status classification
- **DemandScanSmokeController** — `@ConditionalOnProperty(demand-scan.smoke-test-enabled=true)`, rate-limited to 1 req/min via `AtomicReference<Instant>`
- **application.yml** — added `demand-scan.smoke-test-enabled: false` and `smoke-test-timeout-seconds: 10`

### Test Infrastructure
- **WireMock dependency** — `org.wiremock:wiremock-standalone:3.4.2` (testImplementation)
- **WireMockAdapterTestBase** — shared base class with `loadFixture(path)`, `assertValidRawCandidates()`, and `assertSignalPresent()` helpers
- **16 fixture files** in `src/test/resources/wiremock/` across 4 subdirectories (cj, trends, youtube, reddit), all verified against API docs, with synthetic data and zero real credentials

### Fixture Verification (API documentation cross-reference)

| API | Docs Source | Key Findings |
|---|---|---|
| **CJ Dropshipping** | [developers.cjdropshipping.cn](https://developers.cjdropshipping.cn/en/api/api2/) | API returns HTTP 200 for ALL responses including errors — error codes in JSON `code` field (1600001=auth, 1600200=rate limit). Product IDs are UUIDs. All responses include `requestId`. |
| **YouTube Data API v3** | [developers.google.com/youtube/v3/docs](https://developers.google.com/youtube/v3/docs) | Fixtures match official schemas exactly. Statistics values are strings (not numbers). Error format follows standard Google API error envelope. |
| **Google Trends RSS** | Live feed at `trends.google.com/trending/rss` | Real `approx_traffic` values are `200+` to `10,000+` range (not 500K+). Namespace `ht:` is correct. Feed includes `<ht:picture>`, `<description/>`, `<atom:link>` elements. |
| **Reddit API** | [github.com/reddit-archive/reddit/wiki/oauth2](https://github.com/reddit-archive/reddit/wiki/oauth2) | Token `expires_in` is `3600` (1 hour), not 86400. Client credentials scope returns `"*"`. Token error uses OAuth 2.0 standard: `error_description` field, `invalid_client` code. Listing format with `created_utc` as float is correct. |

### Test Classes (27 new tests)
- **CjDropshippingAdapterWireMockTest** (5) — happy path with Money assertions, empty response, auth error (HTTP 200 + code 1600001), rate limit (HTTP 200 + code 1600200), malformed JSON
- **GoogleTrendsAdapterWireMockTest** (3) — RSS happy path with namespace-aware XML parsing, empty feed, malformed XML
- **YouTubeDataAdapterWireMockTest** (4) — 3-endpoint chain happy path with all 7 demand signals, 403 invalid key, 403 quota exceeded, malformed JSON
- **RedditDemandAdapterWireMockTest** (4) — OAuth + subreddit happy path with auth header verification, token caching (1 auth across 2 fetches), OAuth 401 auth failure, 429 rate limit
- **DemandScanSmokeServiceTest** (8) — all 6 status mappings, XML parse error, mixed results across providers
- **DemandScanSmokeControllerTest** (3) — 200 happy path, 429 rate limit, no-raw-errors verification

## Files Modified

| File | Change |
|---|---|
| `modules/portfolio/build.gradle.kts` | Added WireMock test dependency |
| `modules/portfolio/src/main/kotlin/.../proxy/GoogleTrendsAdapter.kt` | Added `internal var feedUrl` |
| `modules/portfolio/src/main/kotlin/.../proxy/RedditDemandAdapter.kt` | Wrapped `getAccessToken()` in try-catch; fixed `expires_in` default from 86400→3600 |
| `modules/app/src/main/resources/application.yml` | Added smoke-test config properties |

## Files Created

| File | Description |
|---|---|
| `modules/portfolio/src/main/kotlin/.../domain/SmokeTestTypes.kt` | SmokeStatus enum, SourceSmokeResult, SmokeTestReport |
| `modules/portfolio/src/main/kotlin/.../domain/service/DemandScanSmokeService.kt` | Concurrent provider probing with exception classification |
| `modules/portfolio/src/main/kotlin/.../handler/DemandScanSmokeController.kt` | REST endpoint with ConditionalOnProperty + rate limiting |
| `modules/portfolio/src/test/kotlin/.../proxy/WireMockAdapterTestBase.kt` | Shared test base class |
| `modules/portfolio/src/test/kotlin/.../proxy/CjDropshippingAdapterWireMockTest.kt` | 5 tests |
| `modules/portfolio/src/test/kotlin/.../proxy/GoogleTrendsAdapterWireMockTest.kt` | 3 tests |
| `modules/portfolio/src/test/kotlin/.../proxy/YouTubeDataAdapterWireMockTest.kt` | 4 tests |
| `modules/portfolio/src/test/kotlin/.../proxy/RedditDemandAdapterWireMockTest.kt` | 4 tests |
| `modules/portfolio/src/test/kotlin/.../domain/service/DemandScanSmokeServiceTest.kt` | 8 tests |
| `modules/portfolio/src/test/kotlin/.../handler/DemandScanSmokeControllerTest.kt` | 3 tests |
| `modules/portfolio/src/test/resources/wiremock/cj/*.json` (4 files) | CJ recorded response fixtures |
| `modules/portfolio/src/test/resources/wiremock/trends/*.xml` (3 files) | Google Trends RSS fixtures |
| `modules/portfolio/src/test/resources/wiremock/youtube/*.json` (5 files) | YouTube API response fixtures |
| `modules/portfolio/src/test/resources/wiremock/reddit/*.json` (4 files) | Reddit OAuth + listing fixtures |

## Testing Completed

- `./gradlew build` — **BUILD SUCCESSFUL** (all 41 tasks, all modules)
- 27 new tests all passing
- No API keys or network access required
- All fixture files verified against official API documentation
- All fixture files contain zero real credentials, tokens, or PII
- Existing test suite (portfolio + all other modules) unaffected

## Deployment Notes

- **No migration required** — purely test infrastructure + a config-gated endpoint
- Smoke endpoint is **disabled by default** (`demand-scan.smoke-test-enabled: false`)
- To enable post-deploy: set `demand-scan.smoke-test-enabled=true` in environment config
- Smoke endpoint requires authentication and is rate-limited to 1 invocation per minute

## Bug Fixes

1. **RedditDemandAdapter OAuth auth failure** — previously threw `IllegalStateException` when token endpoint returned non-200 response. Now catches the exception in `fetch()` and returns an empty list with a warning log, consistent with all other adapter error handling patterns.

2. **RedditDemandAdapter token expiry default** — `expires_in` fallback changed from 86400 (24h) to 3600 (1h) to match Reddit's actual token lifetime. Previous default risked caching an expired token for 23 hours if the field was absent from the response.
