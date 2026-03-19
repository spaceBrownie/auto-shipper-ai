# FR-018: Demand Signal API Pivot — Summary

**Linear issue:** RAT-22
**Branch:** `feat/RAT-22-demand-signal-api-pivot`
**Completed:** 2026-03-19

---

## Feature Summary

Replaced the Amazon Creators API adapter (PA-API 5.0 deprecated April 30, 2026; Creators API requires 10 qualifying sales/30 days) with two new free demand signal sources: YouTube Data API v3 and Reddit API. The Amazon adapter code is retained but deactivated via `@ConditionalOnProperty`. `DemandScanJob` required zero code changes — new providers are discovered automatically via Spring's `List<DemandSignalProvider>` injection.

## Changes Made

### New adapters
- **YouTubeDataAdapter** — searches for product review videos, unboxings, and "best of" content. Extracts demand signals (view count, likes, comments, channel subscribers, publish date) from YouTube Data API v3. Uses API key auth (no OAuth). Batches `videos.list` and `channels.list` calls to minimize quota usage (~408 units/day of 10,000 budget).
- **RedditDemandAdapter** — searches configurable subreddits (r/BuyItForLife, r/shutupandtakemymoney, r/gadgets, r/homeautomation) for organic product discussions. Extracts signals (upvotes, comments, post age, subscriber count). OAuth 2.0 client credentials with `ReentrantLock` token caching (same pattern as Amazon adapter). URL-encoded form body per CLAUDE.md #12.

### Amazon deactivation
- `@ConditionalOnProperty(name = ["amazon-creators.enabled"], havingValue = "true", matchIfMissing = false)` added to both `AmazonCreatorsApiAdapter` and `StubAmazonCreatorsApiProvider`. Code retained for potential future use.

### Configuration
- `application.yml` — YouTube, Reddit, and Amazon toggle sections added
- `.env.example` — `CJ_ACCESS_TOKEN`, `YOUTUBE_API_KEY`, `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET` documented

## Files Modified

| File | Change |
|------|--------|
| `modules/app/src/main/resources/application.yml` | Added youtube, reddit, amazon-creators.enabled config |
| `modules/portfolio/.../proxy/AmazonCreatorsApiAdapter.kt` | Added `@ConditionalOnProperty` |
| `modules/portfolio/.../proxy/StubAmazonCreatorsApiProvider.kt` | Added `@ConditionalOnProperty` |
| `modules/portfolio/.../proxy/YouTubeDataAdapter.kt` | **New** — YouTube Data API v3 adapter |
| `modules/portfolio/.../proxy/RedditDemandAdapter.kt` | **New** — Reddit API adapter |
| `modules/portfolio/.../proxy/StubYouTubeDataProvider.kt` | **New** — local profile stub |
| `modules/portfolio/.../proxy/StubRedditDemandProvider.kt` | **New** — local profile stub |
| `modules/portfolio/.../proxy/YouTubeDataAdapterTest.kt` | **New** — 6 tests |
| `modules/portfolio/.../proxy/RedditDemandAdapterTest.kt` | **New** — 8 tests |
| `modules/portfolio/.../proxy/AmazonAdapterDeactivationTest.kt` | **New** — 2 tests |
| `modules/portfolio/.../proxy/StubProviderTest.kt` | Extended — 6 new tests (15 total) |
| `.env.example` | Added demand signal API env vars |
| `feature-requests/FR-018-demand-signal-api-pivot/implementation-plan.md` | All checkboxes checked |

## Testing Completed

| Test class | Tests | Status |
|-----------|-------|--------|
| `YouTubeDataAdapterTest` | 6 | All pass |
| `RedditDemandAdapterTest` | 8 | All pass |
| `AmazonAdapterDeactivationTest` | 2 | All pass |
| `StubProviderTest` | 15 (6 new) | All pass |
| All other portfolio tests | 22 | All pass (no regressions) |
| All other module tests | Pass | No regressions |

**Total new tests: 22** (6 YouTube + 8 Reddit + 2 Amazon deactivation + 6 stub)

## Deployment Notes

1. **No database migration required** — this is adapter-only work
2. **Amazon adapter is off by default** — `amazon-creators.enabled: false` in config
3. **Set env vars before deploy:**
   - `YOUTUBE_API_KEY` — from Google Cloud Console (enable YouTube Data API v3)
   - `REDDIT_CLIENT_ID` + `REDDIT_CLIENT_SECRET` — from reddit.com/prefs/apps (script type app)
4. **DemandScanJob auto-discovers** new providers on next startup — no manual wiring needed
5. **Rollback:** revert PR; Amazon adapter returns to unconditional activation
6. **Re-enable Amazon if needed:** set `amazon-creators.enabled: true` in env config
