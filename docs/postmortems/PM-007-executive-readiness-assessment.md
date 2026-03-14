# PM-007: Executive Readiness Assessment — Operational Autonomy Gap Analysis

**Date:** 2026-03-14
**Type:** Strategic Decision Record (not an incident)
**Status:** Decisions captured; implementation pending
**Author:** Auto-generated from session

## Summary

An executive-level assessment was conducted to determine whether the Commerce Engine can operate autonomously — discovering deals, listing profitable products, fulfilling orders, and generating durable profit without human intervention. The defensive layer (margin protection, vendor governance, fulfillment, capital reserves) is complete and autonomous. Three gaps prevent full operational autonomy: no automated deal sourcing, no product listing adapter, and no compliance gate. Key architectural decisions were made for FR-010 and FR-011, and two new Linear tasks (RAT-12, RAT-13) were created for the remaining gaps.

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

**Linear task:** [RAT-12](https://linear.app/ratrace/issue/RAT-12) — Blocked by FR-010 (needs `Experiment` entity).

### Gap 2: No Product Listing Adapter

`ShopifyPriceSyncAdapter` syncs prices to existing Shopify variants, but no adapter can **create** a product listing. A SKU that passes all gates still requires manual product creation on Shopify. The `PlatformAdapter` interface is defined in the solo-operator spec (§4.2) but not implemented.

**Linear task:** [RAT-13](https://linear.app/ratrace/issue/RAT-13) — Blocked by FR-011 (compliance must gate before public listing).

### Gap 3: No Compliance Gate

FR-011 (compliance guards) is spec'd and plan is finalized but not implemented. SKUs currently bypass IP/trademark/regulatory checks. This is a legal exposure risk — especially once the platform listing adapter is built and products are auto-published.

**No separate task needed** — FR-011 implementation plan is ready to execute.

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
     └─────────────────┘     └─────────────────┘
```

- FR-010 and FR-011 are independent — can be built in parallel
- RAT-12 blocked by FR-010: needs `Experiment` entity and `ExperimentService`
- RAT-13 blocked by FR-011: compliance gate must exist before auto-publishing to Shopify

## Final Assessment

> **"The engine is built. The fuel line isn't connected yet."**

The defensive layer is production-grade and fully autonomous. Any SKU that is already listed will be monitored, margin-protected, auto-paused on breach, auto-refunded on vendor failure, and price-adjusted on cost changes.

The system **cannot yet sustain itself** because it cannot:
1. Discover new product opportunities automatically
2. Publish products to a storefront automatically
3. Run compliance checks before listing

Once FR-010, FR-011, RAT-12, and RAT-13 are complete, the loop closes:

```
discover → validate → cost gate → stress test → compliance → list → monitor → kill/scale → reallocate → repeat
```

At that point, the answer to "can the owner step away for twelve months?" changes from **no** to **yes, with the auto-terminate flag enabled**.
