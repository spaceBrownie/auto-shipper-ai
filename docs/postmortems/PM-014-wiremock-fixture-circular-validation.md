# PM-014: WireMock Fixtures Built from Adapter Code Created Circular Validation

**Date:** 2026-03-20
**Severity:** Medium
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

WireMock integration test fixtures for 4 external API adapters were initially synthesized by reverse-engineering the adapter parsing code, rather than verified against real API documentation. This created circular validation — tests passed because fixtures matched what the code expected, not what the APIs actually return. Cross-referencing against official docs revealed fabricated CJ error codes, incorrect HTTP status behavior (CJ returns 200 for errors), a Reddit token expiry default that would cache expired tokens for 23 hours, and unrealistic Google Trends traffic values. A separate PR review caught that the smoke-test service lost provider identity on timeout (`source = "UNKNOWN"`).

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-017 Phase 4 implementation begins — WireMock tests + smoke endpoint |
| +20 min | 16 fixture files written based on adapter code analysis (not API docs) |
| +30 min | All 27 tests pass, full build green |
| +35 min | User asks: "are these the actual response contracts we're expecting from the APIs?" |
| +40 min | Honest assessment: fixtures were inferred from adapter code, not verified against docs |
| +45 min | User feedback: "you need to research and websearch...Next time let me know before you make this assumption" |
| +50 min | 4 parallel research agents launched against official API documentation |
| +65 min | All 4 agents return — YouTube correct, Google Trends minor issues, CJ significant issues, Reddit medium issues |
| +70 min | All fixtures corrected, CJ test stubs changed from HTTP 401/429 to HTTP 200 with error codes, Reddit adapter bug fixed |
| +75 min | PR created, Unblocked bot catches `source = "UNKNOWN"` bug in smoke service |
| +80 min | Smoke service fix applied, all tests passing |

## Symptom

No runtime error — all tests passed. The symptom was conceptual: fixtures were designed to make tests pass rather than to validate adapter correctness against real APIs. This was caught by the user's review question, not by any automated check.

## Root Cause

**5 Whys:**

1. **Why** were the fixtures wrong? → They were synthesized from what the adapter code reads (`response.path("data").path("list")`, etc.), not from what the APIs actually return.

2. **Why** were they synthesized from code? → The developer (Claude) prioritized speed of implementation over accuracy, treating fixture creation as a mechanical task rather than a research task.

3. **Why** wasn't this caught automatically? → There is no validation step in the feature-request workflow that requires external API documentation review before writing integration test fixtures.

4. **Why** was the assumption not flagged? → The developer didn't recognize that reverse-engineering fixtures from adapter code creates circular validation — the tests can only confirm the adapter is self-consistent, not that it matches reality.

5. **Why** does this matter? → The entire purpose of WireMock tests (vs Mockito unit tests) is to catch mismatches between adapter assumptions and real API response shapes. Circular fixtures defeat this purpose entirely.

### Specific issues found by API doc research:

**CJ Dropshipping (significant):**
- Error codes fabricated: used `1600100` / `1600429` (made up) instead of real `1600001` (auth) / `1600200` (rate limit)
- CJ API returns **HTTP 200 for all responses** — errors are in the JSON `code` field. Test stubs used `.withStatus(401)` and `.withStatus(429)`, testing a code path that never fires in production.
- Missing `requestId` field (present in every real CJ response)
- Product IDs should be UUIDs, not `"CJ-TEST-001"` format

**Reddit (medium):**
- `expires_in` set to `86400` (24h) in fixture — real value is `3600` (1h). This also exposed a **real adapter bug**: `RedditDemandAdapter.getAccessToken()` line 123 had `asLong(86400)` as default fallback, meaning if `expires_in` was missing from the response, the adapter would cache a token for 24 hours when it actually expires after 1 hour.
- Token error response used `"error": "invalid_grant"` instead of `"invalid_client"`, and `"message"` instead of OAuth 2.0 standard `"error_description"`

**Google Trends (minor):**
- Traffic values `500,000+` / `1,000,000+` were unrealistically high — real feed shows `200+` to `10,000+` range

**Smoke service (caught by Unblocked PR review):**
- `DemandScanSmokeService.runSmokeTest()` used `futures.map` which lost the provider reference on timeout/error catch paths, resulting in `source = "UNKNOWN"` — defeating the per-source diagnostic purpose

## Fix Applied

### Fixture corrections
- All 16 fixtures updated to match official API documentation
- CJ: error codes corrected, HTTP 200 stubs for all responses, `requestId` added, UUID product IDs
- Reddit: `expires_in` → `3600`, `scope` → `"*"`, error field names corrected to OAuth 2.0 spec
- Google Trends: traffic values scaled to realistic range, added `xmlns:atom`, `<ht:picture>`, `<description/>` elements

### Adapter bug fixes
- `RedditDemandAdapter.kt` line 123: default fallback changed from `86400` to `3600`
- `RedditDemandAdapter.fetch()`: wrapped `getAccessToken()` in try-catch (was throwing `IllegalStateException` on auth failure)

### Test corrections
- CJ test stubs changed from `.withStatus(401)` / `.withStatus(429)` to `.withStatus(200)` with error codes in body
- Smoke service: `futures.map` → `providers.zip(futures).map` to preserve provider identity

### Files Changed
- `modules/portfolio/src/test/resources/wiremock/cj/*.json` — corrected error codes, added requestId, UUID PIDs
- `modules/portfolio/src/test/resources/wiremock/trends/rss-feed-success.xml` — realistic traffic values, additional elements
- `modules/portfolio/src/test/resources/wiremock/reddit/token-success.json` — expires_in 3600, scope "*"
- `modules/portfolio/src/test/resources/wiremock/reddit/token-error-401.json` — OAuth 2.0 standard fields
- `modules/portfolio/src/test/kotlin/.../proxy/CjDropshippingAdapterWireMockTest.kt` — HTTP 200 stubs for error cases
- `modules/portfolio/src/main/kotlin/.../proxy/RedditDemandAdapter.kt` — token expiry default fix + auth failure handling
- `modules/portfolio/src/main/kotlin/.../domain/service/DemandScanSmokeService.kt` — providers.zip(futures)

## Impact

- **No production impact** — caught before merge during development session
- **Reddit token expiry default** was a latent production bug: if Reddit ever omitted `expires_in` from a token response, the adapter would cache an expired token for ~23 hours, causing all Reddit demand signal fetches to fail with 401 until restart
- **CJ error handling** was testing the wrong code path — HTTP exception handling instead of JSON error code handling. In production, CJ auth failures would silently return zero candidates (correct behavior accidentally) but via a different mechanism than tested

## Lessons Learned

### What went well
- User caught the assumption before it shipped — "are these the actual response contracts?"
- Parallel research agents efficiently verified all 4 APIs against official docs simultaneously
- Unblocked PR review bot caught the `source = "UNKNOWN"` bug independently
- API doc research surfaced a real production-risk adapter bug (Reddit token expiry default)

### What could be improved
- WireMock fixtures should never be written without first consulting API documentation
- The developer should have flagged the assumption ("I'm inferring these from adapter code — should I verify against docs?") instead of proceeding silently
- The feature-request workflow has no checkpoint for "verify external API fixtures against documentation"

## Prevention

- [x] Saved feedback memory: WireMock fixtures must be based on real API docs, not reverse-engineered from adapter code
- [ ] Add to CLAUDE.md: "WireMock/recorded-response fixtures must be verified against official API documentation before commit. Never synthesize fixtures solely from adapter parsing code — this creates circular validation."
- [x] Consider adding a fixture README template in `src/test/resources/wiremock/` that requires documenting the API doc source URL for each fixture set — Addressed via `_comment` / `_comment_source` / `_comment_verified` JSON fields in every CJ fixture (FR-027 PR #47 + FR-028 PR #48). All 12 CJ fixtures now cite their API doc URL inline.
