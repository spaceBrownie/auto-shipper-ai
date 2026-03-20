# NR-002: System Now Auto-Lists Products on Shopify — Plus a Hard Look at Test Confidence

**Date:** 2026-03-20
**Linear:** RAT-13 (Done), RAT-24 (Created), RAT-25 (Created)
**Status:** Completed

---

## TL;DR

The system can now automatically create a product listing on Shopify the moment a SKU passes all gates (compliance, cost verification, stress test). When a margin breach triggers an auto-pause, the Shopify listing is simultaneously pulled to draft. This was Gap #2 from the PM-007 readiness assessment — it's closed. We also discovered that none of our external API integrations (Shopify, YouTube, Reddit, CJ, Google Trends) have tests that validate whether we're actually calling the APIs correctly, so we created a plan to fix that before staging.

## What Changed

- **Automatic Shopify listing on SKU launch** — when a SKU clears compliance, cost verification, and stress test (50%+ gross / 30%+ net margin floor), it transitions to Listed and the system immediately creates a product on Shopify with the correct title, category, price, and SKU identifier. Zero manual intervention.

- **Automatic listing pause on margin breach** — when the capital protection system detects 7+ days below the 30% net margin floor and auto-pauses a SKU, the Shopify listing is simultaneously set to draft (invisible to customers). Previously, a pause would stop internal operations but the product would remain live and sellable on Shopify.

- **Automatic listing archive on termination** — when a SKU is terminated (refund rate > 5%, chargeback rate > 2%, or kill window breach after 30+ days negative), the Shopify listing is archived permanently.

- **Price sync consolidation** — when the pricing engine adjusts a price (e.g., cost signal from a supplier increase), the Shopify variant price is now updated through the same unified integration. Existing SKUs that were listed before this change continue to work via a backward-compatible path.

- **End-to-end validated** — ran the full lifecycle locally: SKU creation → compliance clear → cost gate ($58.45 fully burdened) → stress test pass (62% margin at $199.99) → auto-listed on Shopify (ACTIVE) → inserted degraded margin data (25% net, 7 days) → margin sweep fired → auto-paused → Shopify listing set to DRAFT. Every step verified.

## Why This Matters

This was the last operational gap between "the system makes decisions" and "the system acts on them." Before today, a SKU could pass every gate and the system would declare it Listed — but someone still had to manually create the product on Shopify. Now the full loop runs autonomously: discover → validate → price → list → monitor → pause/kill.

More critically, the pause integration prevents a dangerous scenario: the capital protection system pauses a money-losing SKU internally, but it keeps selling on Shopify because the listing was never pulled. That's real money walking out the door. Now they're in lockstep.

During the session, we caught a subtle bug via automated PR review: three of the four Shopify operations would silently do nothing if the API credentials were misconfigured, while the listing creation would correctly raise an error. This meant a margin breach could trigger a pause, the system would record the listing as "paused," but Shopify would still be selling the product. Fixed before merge.

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Shopify auto-listing (FR-020) | Done | Listed, paused, archived, price sync all working |
| Demand signal pipeline | Done | 4 sources active (CJ, Google Trends, YouTube, Reddit) |
| API contract verification | Not Started | No integration has tests against real API response shapes — tracked as RAT-15 → RAT-24 |
| Automated marketing/SEO | Not Started | Products get listed but no organic traffic generation yet (RAT-14) |
| User journey maps | Not Started | New initiative to map business flows through the system for better planning (RAT-25) |

## What's Next

Reprioritized the full backlog this session. Execution order:

1. **API contract verification for demand sources (RAT-15)** — establish the test pattern using YouTube, Reddit, CJ, and Google Trends. This proves our integrations actually call the APIs correctly before we spend time debugging in staging.

2. **API contract verification for Shopify (RAT-24)** — apply the same pattern to all three Shopify integrations (listing, fee lookup, price sync). After this, we have confidence in every external API call the system makes.

3. **Integration test coverage (RAT-19)** — close gaps identified across 7 prior incident reports where unit tests passed but the system broke at the seams between modules.

4. **Automated marketing & SEO (RAT-14)** — the system can now list products autonomously, but without organic traffic they won't sell. This closes the revenue loop.

5. **Backlog cleanup (RAT-21, RAT-20, RAT-16)** — one-time audits, skill tooling improvements, and demand scan enhancements. Lower priority.

## Risks & Decisions Needed

- **API contract confidence gap:** Every external integration (Shopify, YouTube, Reddit, CJ, Google Trends) currently has zero tests validating that we're calling the API correctly or parsing responses correctly. Our tests only verify internal logic. This means staging could surface basic integration failures (wrong request format, unexpected response structure). RAT-15 and RAT-24 address this. → **Ask:** Are you comfortable with the prioritization (contract tests before marketing/SEO), or do you want to push marketing/SEO ahead and accept the staging risk?

## Session Notes

- The PR review bot caught a capital-protection-level bug (silent failure on Shopify pause) that would have let money-losing products keep selling while the system thought they were paused. Good save.
- Created a new "Innovation" initiative (RAT-25) for machine-readable user journey maps — a way to define business flows through the system so that planning catches interface mismatches before coding starts. Early-stage idea but could significantly reduce implementation surprises.
- Total output: 20 files shipped, 27 tests (25 original + 2 from the bug fix), 1 postmortem, 3 new Linear issues, full backlog reprioritized.
