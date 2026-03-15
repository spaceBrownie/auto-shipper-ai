# PM-007: Executive Readiness Assessment — Operational Autonomy Gap Analysis

**Date:** 2026-03-14
**Type:** Strategic Decision Record (not an incident)
**Status:** Decisions captured; implementation pending
**Author:** Auto-generated from session

## Summary

An executive-level assessment was conducted to determine whether the Commerce Engine can operate autonomously — discovering deals, listing profitable products, fulfilling orders, and generating durable profit without human intervention. The system operates a **zero-capital model**: the operator pays only for infrastructure (~$6-12/mo) and subscriptions (~$45/mo); every product is a listing hypothesis with zero upfront cost; the customer's payment covers all costs and the remainder is profit.

The defensive layer (margin protection, vendor governance, fulfillment, capital reserves) is complete and autonomous. Four gaps prevent full operational autonomy: no automated deal sourcing, no product listing adapter, no compliance gate, and no marketing/SEO automation. Key architectural decisions were made for FR-010 and FR-011, and three new Linear tasks (RAT-12, RAT-13, RAT-14) were created for the remaining gaps.

A follow-up session (same day) refined the business model and identified two additional critical rules: (1) discovery must source from original suppliers only, never resellers; (2) systemic refund patterns across the portfolio must be detected and analyzed, not just per-SKU rates. RAT-5 (FR-016: Discovery) was consolidated into RAT-12 after alignment review found it conflicted with PM-007 decisions.

## Current State: What's Built

### Implemented (FR-001 through FR-009, FR-013, FR-014, FR-015)

| Module | Key Capability | Autonomous? |
|--------|---------------|-------------|
| `shared` | Money, Percentage, SkuId, 10 domain events | N/A (foundation) |
| `catalog` | SKU lifecycle state machine, cost gate (13 verified components), stress test (5 scenarios, 50%/30% floors), `LaunchReadySku` proof type | Yes |
| `pricing` | Backward induction pricing, 4 signal types, margin recalculation, Shopify price sync | Yes |
| `vendor` | 5-point activation checklist, reliability scoring, `VendorSlaMonitor` (15 min), auto-pause on breach | Yes |
| `fulfillment` | Order lifecycle, `ShipmentTracker` (30 min polling), delay alerts, auto-refund on vendor breach via Stripe | Yes |
| `capital` | Rolling reserve (10-15%), `MarginSweepJob` (6h), 4 kill rules, append-only audit, SKU P&L reporting | Yes |

### Scheduled Jobs Running

| Job | Frequency | Module |
|-----|-----------|--------|
| `MarginSweepJob` | Every 6h | capital |
| `ReserveCalcJob` | Nightly 02:00 | capital |
| `ShipmentTracker` | Every 30 min | fulfillment |
| `VendorSlaMonitor` | Every 15 min | vendor |

### Automated Kill Rules (all live)

| Signal | Threshold | Window | Action |
|--------|-----------|--------|--------|
| Net margin | < 30% | 7+ consecutive days | Pause |
| Refund rate | > 5% | Rolling 30 days | Pause |
| Chargeback rate | > 2% | Rolling 30 days | Pause + compliance flag |
| CAC variance | > 15% | Rolling 14 days | Pricing engine re-run |

## Gaps Identified

### Gap 1: No Automated Deal Sourcing

The system cannot discover new product opportunities. SKUs must be created manually via `POST /api/skus`. `DemandScanJob` (Google Trends RSS, Reddit, Amazon PA-API) is specified in the solo-operator spec (§2.4, §4.1) but not implemented. Without this, the pipeline runs dry when the operator steps away.

**Critical rule:** Discovery must source from **original suppliers** (CJ Dropshipping, Printful, Gelato) — never from marketplace resellers. Amazon/TikTok/Google Trends data is used as a *demand signal* only; the product is sourced independently from a direct supplier at true source cost. Buying from a reseller at $10 when the original source is $6 creates double-markup pricing that leads to uncompetitive prices, refunds, and customer churn.

**Linear task:** [RAT-12](https://linear.app/ratrace/issue/RAT-12) — Blocked by FR-010 (needs `Experiment` entity). Consolidates RAT-5 (FR-016), which proposed a misaligned standalone `discovery` module. RAT-5, RAT-8, RAT-10 cancelled.

### Gap 2: No Product Listing Adapter

`ShopifyPriceSyncAdapter` syncs prices to existing Shopify variants, but no adapter can **create** a product listing. A SKU that passes all gates still requires manual product creation on Shopify. The `PlatformAdapter` interface is defined in the solo-operator spec (§4.2) but not implemented.

**Linear task:** [RAT-13](https://linear.app/ratrace/issue/RAT-13) — Blocked by FR-011 (compliance must gate before public listing).

### Gap 3: No Compliance Gate

FR-011 (compliance guards) is spec'd and plan is finalized but not implemented. SKUs currently bypass IP/trademark/regulatory checks. This is a legal exposure risk — especially once the platform listing adapter is built and products are auto-published.

**No separate task needed** — FR-011 implementation plan is ready to execute.

### Gap 4: No Marketing/SEO Automation

The zero-capital model requires zero ad spend — all traffic acquisition must be organic. Without automated marketing/SEO, products get listed but nobody sees them. This includes: SEO-optimized product titles/descriptions, content generation (blog posts, buying guides), social media automation (Pinterest, TikTok, Instagram), and platform-native SEO (Amazon backend keywords, Etsy tags).

**Linear task:** [RAT-14](https://linear.app/ratrace/issue/RAT-14) — Blocked by RAT-13 (need listings before optimizing them). Uses discovery data from RAT-12 for trending keywords and Claude API infrastructure from FR-011.

## Architectural Decisions Made

### AD-1: KillWindowMonitor — Feature-Flagged Hybrid (FR-010)

**Decision:** Option C — `portfolio.auto-terminate.enabled=false` by default.

Three options were evaluated:

| Option | Behaviour | Tradeoff |
|--------|-----------|----------|
| A — Fully automated | Emit event → auto-terminate | Risk of false positives killing recovering SKUs |
| B — Advisory only | Write recommendation → dashboard | Requires daily review; defeats zero-intervention goal |
| **C — Feature-flagged hybrid** ✅ | Flag off: advisory. Flag on: auto-terminate. | Start safe, enable when trusted. No code rework. |

**Why this matters:** Capital module (FR-009) handles short-term signals — net margin < 30% for 7 days → pause (reversible). Portfolio's `KillWindowMonitor` handles long-term verdict — sustained underperformance past 30 days → terminate (permanent). These are complementary, not duplicate. The feature flag lets the operator build trust in the system before enabling irreversible automated termination.

**Implementation detail:** When flag is ON, `KillWindowMonitor` emits `KillWindowBreached` event. `CatalogKillWindowListener` in catalog module terminates the SKU using PM-005 double-annotation pattern. When flag is OFF, recommendation is written to `kill_recommendations` table and surfaced in dashboard for one-click manual confirmation via `POST /api/portfolio/kill-recommendations/{id}/confirm`.

### AD-2: Compliance as First-Class REST Resource (FR-011)

**Decision:** Compliance endpoints at `/api/compliance/skus/{id}` instead of `/api/skus/{id}/compliance`.

**Rationale:** Matches the module-level convention used by `/api/capital`, `/api/portfolio`, `/api/vendor`. Each bounded context owns its own REST namespace. Nesting under `/api/skus` would make the catalog module appear to own compliance.

### AD-3: Compliance Auto-Check Feature Flag (FR-011)

**Decision:** `compliance.auto-check.enabled=true` by default (production), `false` for development.

**Rationale:** Compliance is a hard gate, not an irreversible action — safe to run automatically. The flag exists so developers can step through the flow manually during implementation. In production it should always be `true`. When `false`, compliance checks are triggered via `POST /api/compliance/skus/{id}/check`.

### AD-4: ExperimentId Value Class (FR-010)

**Decision:** Use `ExperimentId` value class (already exists in shared module) instead of raw `UUID` throughout the portfolio module.

**Rationale:** Consistency with `SkuId`, `VendorId`, `OrderId` used everywhere else. Type safety prevents accidental UUID mixing between entities.

### AD-5: ScalingFlagService Added (FR-010)

**Decision:** Add `ScalingFlagService` to portfolio module. Identifies SKUs with ≥3 consecutive margin snapshots above 50% net margin. Writes flag to `scaling_flags` table for dashboard review. No auto-action in Phase 1.

**Rationale:** Spec's success criteria listed `ScalingFlagService` but the original implementation plan omitted it. Lightweight addition — read-only scan, no automated scaling.

### AD-6: Coroutines in Compliance Module (FR-011)

**Decision:** Use `coroutineScope { async {} }` for parallel compliance checks. First use of coroutines in this codebase.

**Key constraint documented:** Spring's `@Transactional` does NOT propagate across coroutine context switches. The `ComplianceOrchestrator` must structure work so that parallel checks produce pure results (no JPA writes inside `async` blocks), and audit writes + event publication happen outside the coroutine scope in a normal `@Transactional` method.

### AD-7: PM-005 Pattern Mandated for All New Listeners

**Decision:** All cross-module event listeners must use the double-annotation pattern:
```kotlin
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
```

**Applies to:** `CatalogComplianceListener` (FR-011), `CatalogKillWindowListener` (FR-010). This pattern was established in PM-005 after silent data loss was discovered when either annotation was omitted.

**Note:** `VendorSlaBreachRefunder` in the fulfillment module predates PM-005 and still uses plain `@EventListener` + `@Transactional`. Should be updated eventually but is not blocking.

### AD-8: Zero-Capital Business Model (All Modules)

**Decision:** The system operates with zero upfront capital per product. An experiment is a **listing hypothesis** — "list this product and see if it sells." The customer's payment covers all costs (sourcing, shipping, handling, platform fees, processing fees); the remainder is profit.

**Impact on FR-010:**
- `Experiment` entity has no `budget` field — replaced with `sourceSignal` and `estimatedMarginPerUnit`
- `CapitalReallocator` renamed to `PriorityRanker` — ranks candidates by risk-adjusted return for listing priority, not fund transfers
- `ReallocationRecommendation` renamed to `PriorityRanking`
- `capital_reallocation_log` renamed to `priority_ranking_log`

**Operator costs (fixed):** Infrastructure (~$6-12/mo VPS or Mac mini) + Shopify ($39/mo) + SaleHoo ($5.60/mo) + buffer (~$15/mo) ≈ $60-75/mo total.

### AD-9: Source-Level Pricing Rule (Discovery + Compliance)

**Decision:** The discovery engine must always source from the **original manufacturer or supplier**, never from marketplace resellers.

**Rationale:** If a product sells for $10 on TikTok (which is $6 product + $2 shipping + $2 seller markup), buying from the TikTok seller at $10 and reselling at $12 means the customer overpays for a double-marked-up product. This leads to poor value perception, refunds, negative reviews, and permanent customer loss. The system uses marketplace data (Amazon, TikTok, Google Trends) as *demand signals* only — then sources the product independently from a direct supplier (CJ Dropshipping, Printful, Gelato) at true source cost.

**Implementation:**
- RAT-12 (`DemandScanJob`): Phase 1 data sources are supplier APIs (CJ) for sourcing, marketplace APIs (Amazon PA-API, Google Trends) for demand signals only
- FR-011 (`SourcingCheckService`): validates vendor is a direct supplier, not a reseller; `RESELLER_SOURCE` added to `ComplianceFailureReason` enum
- Cost envelope's `supplierUnitCost` must always reflect original source cost

### AD-10: Systemic Refund Pattern Analysis (FR-010)

**Decision:** Add `RefundPatternAnalyzer` to the portfolio module. Per-SKU refund kill rules (>5% → pause) are necessary but not sufficient. When multiple SKUs spike refunds simultaneously, the root cause is systemic — not product-specific.

**Rationale:** Mass refunds across the portfolio indicate listing quality issues, shipping partner failures, or storefront UX problems. Treating each SKU independently misses the pattern and allows systemic problems to destroy customer trust at scale. First-time buyers with a bad experience never come back.

**Implementation:**
- `RefundPatternAnalyzer` monitors refund trends across the entire portfolio
- Flags when 3+ SKUs exceed 3% refund rate in the same 7-day window → systemic alert
- Categorizes root causes: listing accuracy, shipping delays, product quality, price competitiveness
- Feeds blacklist data back to discovery: categories/suppliers that consistently generate refunds are excluded from `DemandScanJob` scoring
- Tables: `refund_alerts`, `discovery_blacklist`

### AD-11: RAT-5 Consolidation into RAT-12

**Decision:** RAT-5 (FR-016: Discovery & Demand Signal Ingestion) cancelled and consolidated into RAT-12.

**Rationale:** RAT-5 (created 2026-03-07) proposed a standalone `discovery` module with `CandidateProduct` entities feeding directly into the catalog cost gate. This conflicted with PM-007 decisions in five ways: (1) spec §2.4 assigns discovery to `portfolio` module, not a separate module; (2) output should be `Experiment` records via `ExperimentService`, not standalone entities; (3) blocking relationships were reversed (RAT-5 claimed to block FR-010; actually RAT-12 is blocked BY FR-010); (4) RAT-5 claimed to block FR-009 which is already implemented; (5) Flyway V13 conflict (already taken by `capital.sql`).

**Preserved from RAT-5:** pgvector for dedup/similarity search, CJ Dropshipping as a data source, composite scoring engine (demand/margin/competition), structured rejection logging. Sub-tasks RAT-8 and RAT-10 also cancelled.

## Build Order

```
     ┌─────────────────┐     ┌─────────────────┐
     │  FR-010          │     │  FR-011          │
     │  Portfolio       │     │  Compliance      │
     │  Orchestration   │     │  Guards          │
     └────────┬────────┘     └────────┬────────┘
              │                       │
              ▼                       ▼
     ┌─────────────────┐     ┌─────────────────┐
     │  RAT-12          │     │  RAT-13          │
     │  DemandScanJob   │     │  Platform        │
     │  (deal sourcing) │     │  Listing Adapter  │
     └─────────────────┘     └────────┬────────┘
                                      │
                                      ▼
                             ┌─────────────────┐
                             │  RAT-14          │
                             │  Marketing/SEO   │
                             │  (organic, $0)   │
                             └─────────────────┘
```

- FR-010 and FR-011 are independent — can be built in parallel
- RAT-12 blocked by FR-010: needs `Experiment` entity and `ExperimentService`
- RAT-13 blocked by FR-011: compliance gate must exist before auto-publishing to Shopify
- RAT-14 blocked by RAT-13: need listings before optimizing them for organic traffic

## Final Assessment

> **"The engine is built. The fuel line isn't connected yet."**

The defensive layer is production-grade and fully autonomous. Any SKU that is already listed will be monitored, margin-protected, auto-paused on breach, auto-refunded on vendor failure, and price-adjusted on cost changes.

The system **cannot yet sustain itself** because it cannot:
1. Discover new product opportunities automatically (RAT-12)
2. Publish products to a storefront automatically (RAT-13)
3. Run compliance checks before listing (FR-011)
4. Drive organic traffic to listings without ad spend (RAT-14)

Once FR-010, FR-011, RAT-12, RAT-13, and RAT-14 are complete, the full autonomous loop closes:

```
discover → source (direct supplier only) → cost gate → stress test → comply → list → SEO/market → sell → fulfill → profit → monitor → kill/scale → repeat
```

**Critical constraints baked into the loop:**
- Source-level pricing: always source from original supplier, never resellers (AD-9)
- Systemic refund detection: portfolio-wide pattern analysis, not just per-SKU (AD-10)
- Zero capital: customer payment covers all costs; profit flows to bank account (AD-8)

At that point, the answer to "can the owner step away for twelve months?" changes from **no** to **yes, with the auto-terminate flag enabled**.
