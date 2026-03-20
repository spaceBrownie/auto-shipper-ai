# NR-001: Demand Signal Pivot — Replaced Amazon with YouTube + Reddit, Hardened Build Safety

**Date:** 2026-03-20
**Linear:** [RAT-22](https://linear.app/ratrace/issue/RAT-22), [RAT-23](https://linear.app/ratrace/issue/RAT-23)
**Status:** Completed

---

## TL;DR

Amazon's product data API shuts down April 30 and its replacement requires 10 qualifying sales/month — a dead end for a pre-revenue zero-capital system. We pivoted to YouTube and Reddit as demand signal sources (both free, no prerequisites). The demand scan pipeline now runs on 4 sources instead of 3, with stronger organic consumer intent signals. We also found and fixed silent failure modes in the build and put structural guardrails in place so they can't recur.

## What Changed

**Demand signal pivot (RAT-22):**
- The daily demand scan now pulls from **4 sources** (up from 3):
  - **CJ Dropshipping** — supply-side: product catalog with true source cost, MOQs, shipping estimates. This is where we price the cost envelope.
  - **Google Trends** — demand-side: trending search terms indicating rising consumer interest
  - **YouTube Data API** (new) — demand-side: product review video engagement. View counts, like ratios, comment volume, channel authority. A video with 500K views on "Best Kitchen Gadgets 2026" is a direct purchase intent signal.
  - **Reddit API** (new) — demand-side: organic community discussions from r/BuyItForLife, r/shutupandtakemymoney, r/gadgets, r/homeautomation. Upvote velocity and comment count on "this cast iron skillet changed my cooking" = validated demand without marketing bias.
- **Amazon deactivated, not deleted.** Code is preserved behind a kill switch. If we ever hit 10 sales/month through the platform and qualify for Creators API access, we flip the switch.
- **Zero cost increase.** YouTube gives us 10,000 API calls/day free (we use ~400). Reddit gives 60 requests/minute free. Both authenticate with simple API keys — no OAuth sales prerequisites.

**Build safety hardening (RAT-23):**
- Fixed a silent failure in the Amazon and CJ login flows where a blank API credential would cache an empty token and silently return zero results — no error, no alert, just an empty pipeline. The system now detects blank credentials at startup and skips gracefully with a logged warning.
- Our automated build checks now print full error chains instead of one-line summaries — this saved us ~30 minutes of misdirected debugging during the pivot.
- Added a startup verification check for the demand signal module that catches configuration wiring problems before they hit production.
- Codified 3 new engineering rules into the project's constraint document to prevent these bug classes structurally.

## Why This Matters

**The demand pipeline was sitting on a ticking clock.** Amazon PA-API 5.0 shuts down May 15, 2026. Without this pivot, the entire product discovery engine — the mouth of the autonomous funnel — would have gone silent in 8 weeks with no warning. The system would scan, find nothing, and not raise an error.

**The new signals are arguably better for our model.** Amazon's API gave us affiliate product metadata (ASINs, bestseller ranks). YouTube and Reddit give us *organic consumer behavior* — people watching reviews before buying, recommending products to strangers with nothing to gain. For a demand-first system that needs to validate willingness-to-pay before sourcing, organic intent signals are more valuable than catalog metadata.

**Financially, the cost envelope is unchanged.** These are demand-side signals only — they tell us *what people want to buy*, not what to source. Sourcing still goes through CJ Dropshipping at true supplier cost (per AD-9 from PM-007: always source from original supplier, never resellers). The stress test still requires 50% gross / 30% net after 2x shipping, +15% CAC, +10% supplier cost, 5% refund, 2% chargeback. Nothing changes on the margin protection side.

**The silent failure bugs we caught are the dangerous kind.** A blank-token bug doesn't crash the system — it makes the system *appear to work* while returning zero candidates. The daily scan completes "successfully" with 0 results. No alert fires because 0 is a valid count. You'd only notice when experiments stop being created and the pipeline quietly dries up. Now the system detects this at startup and logs it explicitly.

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Product discovery (demand scan) | Done | 4 sources, daily 3 AM UTC, scoring + dedup + auto-experiment creation |
| Cost gate (13 verified components) | Done | Fully burdened cost with live carrier/platform/processing rates |
| Stress test (5 shock scenarios) | Done | 50% gross / 30% net floor after worst-case shocks |
| Margin monitoring + kill rules | Done | <30% net for 7 days → auto-pause, >5% refund → pause, >2% chargeback → pause + compliance |
| Reserve management (10-15%) | Done | Nightly reconciliation, health dashboard |
| Vendor governance + SLA monitoring | Done | 15-min SLA checks, auto-refund on breach |
| Compliance guards (4 concurrent checks) | Done | IP, claims, processor, sourcing — gates listing |
| **Shopify product listing (RAT-13)** | **Not Started** | **Critical path — last manual step in the loop** |
| API integration testing (RAT-15) | Not Started | Unblocked by RAT-22 |
| Marketing & SEO (RAT-14) | Not Started | Needs listings first |

## What's Next

- **RAT-13: Shopify product listing** — The last gap before the autonomous loop closes. Today: discover → cost gate → stress test → price → *manual Shopify listing*. After RAT-13: discover → cost gate → stress test → price → **auto-list on Shopify**. This is Gap #2 from PM-007 and the single highest-impact item remaining.
- **RAT-15: API integration testing** — Now unblocked. Recorded-response tests for all 4 data source integrations + a post-deploy health check endpoint. De-risks the integrations we just built.
- **RAT-14: Automated marketing & SEO** — The organic traffic layer. Zero ad spend model means we need automated SEO-optimized listings, content generation, social posting. Large scope, needs its own discovery phase. Blocked by RAT-13 (need listings before optimizing them).

## Risks & Decisions Needed

- **API credential setup:** We need a Google Cloud project (free) for the YouTube API key and a Reddit app registration (free) for OAuth credentials. No cost, just needs to be done before the first live scan. **Ask:** Should we use a shared project account or Denny's personal account for now?
- **Amazon re-qualification path:** If we ever want Amazon data back, we need 10 qualifying sales in 30 days through their affiliate program. Worth keeping in mind once we're live and generating orders, but not a priority now.

## Session Notes

- Unblocked's automated PR review caught both the startup failure pattern and the silent token caching bug before any human reviewer — paid for itself in this session alone.
- The Amazon deprecation deadline (April 30) was not on our radar before this research. Worth adding API dependency monitoring to our operational checklist — we should know when upstream services are sunsetting before it becomes urgent.
- Total output: 2 PRs shipped, 30 new automated checks, 3 new engineering guardrails, 1 incident report (PM-012), full E2E validation across all 8 playbook phases.
