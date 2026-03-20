# FR-018: Demand Signal API Pivot

**Linear issue:** RAT-22
**Created:** 2026-03-19
**Status:** Specification

---

## Problem Statement

Amazon PA-API 5.0 is deprecated with an endpoint shutdown date of May 15, 2026. Its replacement, the Amazon Creators API, requires 10 qualifying sales in the last 30 days to maintain access. This prerequisite is structurally incompatible with a zero-capital, pre-revenue autonomous system that operates demand-first — the system will never generate qualifying Amazon sales because it does not list products on Amazon and does not hold inventory.

If no action is taken, the `AmazonCreatorsApiAdapter` will stop returning data after May 15, reducing the system's demand signal coverage by one of its three active providers. More critically, the system would lose its only adapter that returns product-level demand signals with identifiers (ASINs, BSR rankings, seller counts) — signals that are qualitatively different from the trend-level data returned by Google Trends.

The system needs replacement demand signal sources that:
- Are free or have generous free tiers (consistent with the zero-capital operating model)
- Provide product-level demand signals (not just trend keywords)
- Require no sales history or revenue prerequisites for API access
- Can be authenticated without end-user OAuth flows (the system is autonomous, not interactive)

## Business Requirements

### BR-1: Replace Amazon demand signals with two new sources

Add two new demand signal providers that collectively replace the product-level demand intelligence lost by the Amazon PA-API deprecation:

1. **YouTube Data API v3** — Product review videos, "best of" roundups, and unboxing content are strong demand signals. High view counts and engagement on product review videos indicate consumer interest and purchase intent. YouTube Data API v3 is free (10,000 quota units/day) and requires only an API key for public data access.

2. **Reddit API** — Subreddits like r/BuyItForLife, r/shutupandtakemymoney, r/gadgets, and r/homeautomation contain organic product recommendations with community validation (upvotes, comment counts). Reddit's authenticated API allows 60 requests/minute and uses OAuth 2.0 client credentials (no user interaction required).

### BR-2: Deactivate — do not delete — the Amazon adapter

The `AmazonCreatorsApiAdapter` and its stub (`StubAmazonCreatorsApiProvider`) must be deactivated via configuration so they no longer participate in demand scans. The code must be retained (not deleted) in case Amazon relaxes its access requirements or the system eventually qualifies for Creators API access.

### BR-3: Zero changes to the demand scan orchestrator

The `DemandScanJob` must require no code modifications. New providers must be discovered automatically via Spring's `List<DemandSignalProvider>` injection. This validates that the existing provider architecture is properly extensible.

### BR-4: Local development must remain functional

Stub implementations of both new providers must be available under the `local` profile, returning deterministic test data. This ensures developers can run the full demand scan pipeline without API credentials.

### BR-5: Credential management follows existing patterns

API credentials must be stored in environment variables (`.env`, gitignored), referenced in `application.yml` via `${ENV_VAR:}` syntax with empty defaults, and documented in `.env.example`. No credentials may appear in committed source code or configuration files.

## Success Criteria

### SC-1: Amazon adapter is inactive by default
- `AmazonCreatorsApiAdapter` does not participate in demand scans unless explicitly enabled via configuration property.
- `StubAmazonCreatorsApiProvider` is similarly gated — it does not load under the `local` profile unless explicitly enabled.
- All existing tests continue to pass without modification.

### SC-2: YouTube adapter fetches and maps product review signals
- `YouTubeDataAdapter` implements `DemandSignalProvider` and returns `RawCandidate` instances.
- Each candidate includes demand signals: `video_id`, `view_count`, `like_count`, `comment_count`, `channel_subscriber_count`, `publish_date`, `search_term`.
- `productName` is derived from video title; `supplierUnitCost` is null (demand-side signal only).
- Authentication uses an API key (no OAuth flow required).

### SC-3: Reddit adapter fetches and maps community demand signals
- `RedditDemandAdapter` implements `DemandSignalProvider` and returns `RawCandidate` instances.
- Each candidate includes demand signals: `post_id`, `subreddit`, `upvote_count`, `comment_count`, `post_age_hours`, `subreddit_subscribers`.
- `productName` is derived from post title; `supplierUnitCost` is null (demand-side signal only).
- Authentication uses OAuth 2.0 client credentials grant (no user interaction).
- Subreddits to search are configurable via application properties.

### SC-4: DemandScanJob discovers new providers with zero code changes
- After adding the new adapters, `DemandScanJob` automatically includes YouTube and Reddit providers in its scan cycle via Spring injection.
- The scan run log shows all active providers queried.

### SC-5: Stub providers available for local development
- `StubYouTubeDataProvider` loads under the `local` profile and returns deterministic YouTube-shaped test data.
- `StubRedditDemandProvider` loads under the `local` profile and returns deterministic Reddit-shaped test data.

### SC-6: Configuration is complete and documented
- `application.yml` contains configuration entries for YouTube and Reddit API settings with environment variable placeholders.
- `.env.example` documents all required environment variables (already present: `YOUTUBE_API_KEY`, `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`).

### SC-7: Unit tests cover both new adapters
- Unit tests verify `YouTubeDataAdapter` correctly maps YouTube API responses to `RawCandidate` instances.
- Unit tests verify `RedditDemandAdapter` correctly maps Reddit API responses to `RawCandidate` instances.
- Unit tests verify OAuth token acquisition for the Reddit adapter.
- Unit tests verify the Amazon adapter's conditional deactivation.

## Non-Functional Requirements

### NFR-1: API rate limits must be respected
- YouTube Data API v3: 10,000 quota units/day. `search.list` costs 100 units per call. The adapter must not exceed ~100 searches per day. Since the demand scan runs once daily (3 AM cron), a reasonable search term list (4-8 terms) stays well within budget.
- Reddit API: 60 requests/minute when authenticated. The adapter must include appropriate rate limiting or stay within this budget for a single scan cycle.

### NFR-2: Adapter failures must not break the scan
- Each provider's `fetch()` is already called inside a try-catch in `DemandScanJob.collectFromSources()`. New adapters must not throw exceptions that bypass this isolation — they should handle transient API failures gracefully and return partial results or an empty list.

### NFR-3: Security constraints
- Reddit OAuth token requests that include user-supplied values in form-encoded bodies must use `URLEncoder.encode()` per CLAUDE.md constraint #12.
- No API keys, client secrets, or access tokens may appear in committed code or configuration files.

### NFR-4: Backward compatibility
- The `DemandSignalProvider` interface must not change.
- Existing `CjDropshippingAdapter` and `GoogleTrendsAdapter` (and their stubs) must be completely unaffected.
- All existing tests must pass without modification.

### NFR-5: Deadline
- This work must be completed before May 15, 2026 (Amazon PA-API 5.0 endpoint shutdown). The Amazon adapter should be deactivated proactively — there is no benefit to waiting for the shutdown date.

## Dependencies

### Internal
- **`DemandSignalProvider` interface** (`modules/portfolio/.../domain/DemandSignalProvider.kt`) — new adapters implement this. No changes required.
- **`RawCandidate` data class** (`modules/portfolio/.../domain/RawCandidate.kt`) — new adapters produce these. No changes required.
- **`DemandScanJob`** (`modules/portfolio/.../domain/service/DemandScanJob.kt`) — discovers providers via `List<DemandSignalProvider>` injection. No changes required.
- **`DemandScanConfig`** (`modules/portfolio/.../config/DemandScanConfig.kt`) — may need provider-specific sub-configuration added.
- **`application.yml`** (`modules/app/src/main/resources/application.yml`) — needs YouTube and Reddit configuration sections.
- **`.env.example`** — already contains `YOUTUBE_API_KEY`, `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`.

### External
- **YouTube Data API v3** — Google Cloud Console project with YouTube Data API enabled. Free tier: 10,000 quota units/day.
- **Reddit API** — Reddit application registration (script type) at https://www.reddit.com/prefs/apps. Free: 60 requests/minute authenticated.

### Existing adapters (unchanged)
- `CjDropshippingAdapter` / `StubCjDropshippingProvider` — supply-side signals, unaffected.
- `GoogleTrendsAdapter` / `StubGoogleTrendsProvider` — trend-level signals, unaffected.
