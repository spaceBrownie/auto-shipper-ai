# FR-017: Demand Scan WireMock Tests & Smoke-Test Endpoint

## Problem Statement

FR-016 (RAT-12) introduced DemandScanJob with external API adapters for demand signal ingestion. RAT-22 is pivoting the adapter set — replacing `AmazonCreatorsApiAdapter` with `YouTubeDataAdapter` + `RedditDemandAdapter` (Amazon PA-API deprecated April 30, 2026; Creators API requires 10 sales/30 days, incompatible with a pre-revenue system).

The final adapter set is:
- `CjDropshippingAdapter` (REST/JSON) — supply-side product search
- `GoogleTrendsAdapter` (RSS/XML) — demand-side trending topics
- `YouTubeDataAdapter` (REST/JSON, API key auth) — demand-side product interest via video engagement
- `RedditDemandAdapter` (OAuth 2.0 + REST/JSON) — demand-side organic product discussions

All four are annotated `@Profile("!local")` and are bypassed in development and CI by `@Profile("local")` stub providers that return hardcoded data.

This means the real adapters' JSON/XML parsing, HTTP header construction, OAuth token lifecycle, and error-to-domain mapping are entirely untested. A breaking change in any upstream API response shape will silently produce zero or corrupt `RawCandidate` objects in production with no test to catch the regression. Additionally, there is no post-deploy mechanism to verify that API credentials are valid and each provider is reachable in a deployed environment.

## Business Requirements

### WireMock recorded-response tests (CI-safe)

- Each of the four external API adapters (`CjDropshippingAdapter`, `GoogleTrendsAdapter`, `YouTubeDataAdapter`, `RedditDemandAdapter`) must have integration tests that exercise the real adapter code against a local HTTP server serving recorded response fixtures.
- **CJ Dropshipping** tests must cover: successful product list response producing correct `RawCandidate` objects with `Money` values; malformed/empty JSON response; and HTTP error responses (e.g., 401 unauthorized, 429 rate-limited).
- **Google Trends** tests must cover: valid RSS/XML feed producing `RawCandidate` objects with correct `demandSignals` extraction; malformed XML (not well-formed); and empty feed (zero `<item>` elements).
- **YouTube Data API** tests must cover: successful `search.list` response producing correct `RawCandidate` objects with `demandSignals` (video_id, view_count, like_count, comment_count, publish_date, search_term); API key authentication failure (403 forbidden); quota exceeded response (403 with `quotaExceeded` reason); and malformed/empty JSON response.
- **Reddit API** tests must cover: successful OAuth token acquisition followed by subreddit search producing correct `RawCandidate` objects with `demandSignals` (post_id, subreddit, upvote_count, comment_count, post_age_hours, subreddit_subscribers); OAuth token caching (second call reuses token, does not re-authenticate); OAuth authentication failure (401 from token endpoint); and rate limit response (429).
- Recorded response fixtures (JSON files for CJ, YouTube, and Reddit; XML files for Google Trends) must be checked into version control under a test resources directory.
- All tests must pass in CI without any real API keys or network access.

### Smoke-test endpoint (post-deploy verification)

- A `POST /api/portfolio/demand-scan/smoke-test` endpoint must exist that hits each real demand signal provider with a single minimal request and returns a structured per-source status report.
- The status report for each source must include: source name, status enum (OK, AUTH_FAILURE, TIMEOUT, RATE_LIMITED, PARSE_ERROR, UNKNOWN_ERROR), response time in milliseconds, and sample count (number of candidates returned). Raw upstream error messages must NOT be returned — only the enumerated status codes.
- The smoke-test endpoint must NOT persist any candidates, create experiments, or trigger any side effects. It is a read-only diagnostic probe.
- The endpoint must be gated behind a configuration flag (`demand-scan.smoke-test-enabled`, default `false`) and return 404 when disabled.
- The endpoint must require authentication (Spring Security). Unauthenticated requests must receive 401.
- The endpoint must be rate-limited to at most 1 invocation per minute to prevent upstream API rate limit exhaustion or account lockout.

## Security Requirements

### Fixture sanitization
- All recorded response fixtures committed to version control must be scrubbed of sensitive data before commit: API keys, access tokens, account identifiers, real supplier PII, and any values from `.env`.
- A checklist must be completed for each fixture file: (1) no tokens or credentials in headers or body, (2) no real account/partner IDs, (3) no real supplier contact information or PII.
- Fixture files must use synthetic/placeholder values (e.g., `"accessToken": "test-token-xxx"`, `"partnerId": "PARTNER-001"`) that are clearly not real.

### Smoke endpoint hardening
- The smoke-test response must return enumerated status codes only (`OK`, `AUTH_FAILURE`, `TIMEOUT`, `RATE_LIMITED`, `PARSE_ERROR`, `UNKNOWN_ERROR`) — never raw upstream error messages, headers, or stack traces, to prevent information disclosure.
- Connection pool and thread pool for concurrent smoke checks must be bounded (max 4 concurrent, matching provider count) to prevent resource exhaustion.

### Adapter remediation
- `RedditDemandAdapter` OAuth form body must use `URLEncoder.encode()` for all user-supplied values per CLAUDE.md constraint #12. The WireMock tests must exercise this code path.

### Credential storage
- API keys and access tokens (e.g., `CJ_ACCESS_TOKEN`, `YOUTUBE_API_KEY`, `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`) are stored in `.env` (gitignored) and referenced via `application.yml` environment variable interpolation. This pattern must not change. No credentials in code, fixtures, or committed configuration.

## Success Criteria

- `CjDropshippingAdapterTest`: happy-path test asserts correct `RawCandidate` field mapping including `productName`, `category`, `supplierUnitCost` (Money with correct amount and currency), `estimatedSellingPrice`, and `demandSignals` map entries (`cj_pid`, `cj_category_id`, `cj_product_image`). Error-case tests assert empty list returned (not exception thrown) for 401, 429, and malformed JSON responses.
- `GoogleTrendsAdapterTest`: happy-path test asserts correct `RawCandidate` field mapping including `productName` from `<title>`, `demandSignals` entries (`approx_traffic`, `trend_date`, `geo`), and null `supplierUnitCost`/`estimatedSellingPrice`. Error-case tests assert empty list for malformed XML and zero-item feed.
- `YouTubeDataAdapterTest`: happy-path test asserts correct `RawCandidate` field mapping including `productName` from video title, `demandSignals` entries (`video_id`, `view_count`, `like_count`, `comment_count`, `publish_date`, `search_term`), and null `supplierUnitCost`. Error-case tests assert empty list for 403 (invalid API key), 403 (quota exceeded), and malformed JSON.
- `RedditDemandAdapterTest`: happy-path test asserts OAuth token request is made before subreddit search, and resulting `RawCandidate` objects have correct `productName`, `demandSignals` entries (`post_id`, `subreddit`, `upvote_count`, `comment_count`, `post_age_hours`, `subreddit_subscribers`), and null `supplierUnitCost`. Token-caching test asserts only one token request is made across two `fetch()` calls. Auth-failure test asserts empty list when token endpoint returns 401. Rate-limit test asserts empty list when search returns 429.
- Recorded response fixture files exist in `src/test/resources/wiremock/` (or equivalent) within the portfolio module.
- `POST /api/portfolio/demand-scan/smoke-test` returns a JSON array of per-source status objects (with enumerated status, not raw errors) when `demand-scan.smoke-test-enabled=true`.
- `POST /api/portfolio/demand-scan/smoke-test` returns HTTP 404 when `demand-scan.smoke-test-enabled=false` (default).
- `POST /api/portfolio/demand-scan/smoke-test` returns HTTP 401 for unauthenticated requests.
- Smoke-test endpoint does not write to any database table or publish any domain events.
- All recorded fixture files contain zero real credentials, tokens, account IDs, or supplier PII.
- `RedditDemandAdapter` OAuth form body uses `URLEncoder.encode()` for all parameter values.
- All new tests pass in CI (`./gradlew test`) without API keys or network access.

## Non-Functional Requirements

- WireMock tests must start and stop their HTTP server per test class (not per test method) to keep test execution fast.
- Recorded fixtures must represent realistic response shapes from each API, not minimal stubs -- they should include multiple items, nested structures, and optional/nullable fields as they appear in real responses.
- The smoke-test endpoint must enforce a per-source timeout (configurable, default 10 seconds) so a single unresponsive provider does not block the entire report.
- The smoke-test endpoint must run all four provider checks concurrently, not sequentially.
- WireMock dependency must be test-scoped only (`testImplementation`) and must not appear in the production classpath.

## Dependencies

- RAT-22 (demand-signal-api-pivot) -- YouTubeDataAdapter and RedditDemandAdapter must exist first
- FR-016 (demand-scan-job) -- CjDropshippingAdapter, GoogleTrendsAdapter, and `DemandSignalProvider` interface
- FR-001 (shared-domain-primitives) -- `Money`, `Currency` value types used in adapter output assertions
- FR-002 (project-bootstrap) -- Spring Boot web layer for the smoke-test endpoint
