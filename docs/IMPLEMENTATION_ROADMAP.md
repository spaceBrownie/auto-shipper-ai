# Implementation Roadmap

This document maps the feature request implementation plan to the product flow diagram (`docs/product-flow.mermaid`).

## Product Flow Stages vs. Implementation Progress

### Stage 1: Discovery & Validation 🔍
**Product Flow:** Demand signal ingestion → willingness to pay validation → pre-sell/waitlist

**Implementation Status:** ⏳ Planned (Phase 2+)
- Currently no demand signal intake or WTP validation
- Requires: analytics pipeline, survey/presale adapter, decision logic
- Blocked on: open design decision (how is "willingness to pay" captured?)

---

### Stage 2: Cost Gate 🔒 (Catalog Module)
**Product Flow:** Build cost envelope → fetch live rates (UPS/FedEx/USPS/Stripe/Shopify) → verify all components → run stress test (2× ship, +15% CAC, +10% supplier, 5% refund, 2% chargeback) → check margins (gross ≥ 50%, net ≥ 30%)

| Step | FR | Status |
|---|---|---|
| SKU state machine + basic CRUD | FR-003 | ✅ Complete |
| Cost gate service + carrier/processor adapters | **FR-004** | 📋 Planned |
| Stress test gate + thresholds | **FR-005** | 📋 Planned |

**Implementation Plan:**
- **FR-004** (Cost Gate):
  - `CostEnvelope` sealed class (Unverified → Verified)
  - Carrier rate adapters (UPS, FedEx, USPS with circuit breakers)
  - Stripe & Shopify adapters for fees
  - `CostGateService` orchestrating verification

- **FR-005** (Stress Test):
  - `StressTestedMargin` value class enforcing 30% floor
  - `LaunchReadySku` as proof of passing
  - `StressTestService` applying all 5 multipliers
  - Auto-terminate if margin doesn't pass

---

### Stage 3: Launch 🚀 (Catalog + Pricing Modules)
**Product Flow:** Construct LaunchReadySku (proof of verification + stress test pass) → set initial price (backward induction from WTP ceiling) → activate on platform

| Step | FR | Status |
|---|---|---|
| Dynamic pricing engine + backward induction | **FR-006** | 📋 Planned |
| Platform activation (Shopify/Amazon) | **FR-006** (partial) | 📋 Planned |

**Implementation Plan:**
- **FR-006** (Pricing Engine):
  - `BackwardInductionPricer` (pure function: WTP ceiling + cost envelope → price)
  - `PricingEngine` listening for `PricingSignal` events
  - `PricingDecision` (Adjusted, PauseRequired, TerminateRequired)
  - `ShopifyPriceSyncAdapter` pushing price updates

---

### Stage 4: Vendor Governance 🏭 (Vendor Module)
**Product Flow:** Vendor registry → SLA confirmation → reliability scoring → breach detection → auto-pause SKUs

| Step | FR | Status |
|---|---|---|
| Vendor registration + SLA monitoring + scoring | **FR-007** | 📋 Planned |

**Implementation Plan:**
- **FR-007** (Vendor Governance):
  - `Vendor` aggregate with activation checklist (5 requirements)
  - `VendorReliabilityScore` computed from on-time rate, defect rate, breach count, response time
  - `VendorSlaMonitor` running on schedule, emitting `VendorSlaBreached` events
  - `CatalogVendorBreachListener` auto-pausing linked SKUs on breach

---

### Stage 5: Fulfillment Orchestration 📦 (Fulfillment Module)
**Product Flow:** Order received → inventory sync check → route to vendor (drop-ship/POD/3PL) → real-time tracking → delay alerts → auto-refund on SLA breach

| Step | FR | Status |
|---|---|---|
| Order lifecycle + fulfillment routing + tracking | **FR-008** | 📋 Planned |

**Implementation Plan:**
- **FR-008** (Fulfillment Orchestration):
  - `Order` aggregate with idempotency key
  - `InventoryCheckAdapter` (Shopify inventory pre-check)
  - `ShipmentTracker` polling carrier APIs every 30 min
  - `DelayAlertService` detecting delays + notifications
  - `VendorSlaBreachRefunder` listening for `VendorSlaBreached`, auto-refunding via Stripe
  - `OrderFulfilled` event published on delivery

---

### Stage 6: Pricing Engine (Signal Processing) 💲 (Pricing Module)
**Product Flow:** Listen for pricing signals (shipping/CAC/vendor/platform changes) → recalculate margin → emit decision (adjust/pause/terminate) → sync to Shopify

**Part of FR-006** — already covered above.

**Additional signal handling:**
- `PricingSignal.ShippingCostChanged` (from carrier API updates)
- `PricingSignal.VendorCostChanged` (from supplier changes)
- `PricingSignal.CacChanged` (from conversion monitoring)
- `PricingSignal.PlatformFeeChanged` (from Shopify/Amazon fee updates)

---

### Stage 7: Capital Protection 🏦 (Capital Module)
**Product Flow:** Maintain 10–15% rolling reserve → 90-day margin monitoring → SKU P&L dashboard → kill rules (net margin < 30% for 7+ days, refund rate > 5%, chargeback rate > 2%, CAC variance > 15%) → auto-terminate

| Step | FR | Status |
|---|---|---|
| Reserve management + margin monitoring + kill rules | **FR-009** | 📋 Planned |

**Implementation Plan:**
- **FR-009** (Capital Protection):
  - `ReserveAccount` entity tracking logical ledger balance
  - `SkuMarginSnapshot` daily time-series (gross margin, net margin, refund rate, chargeback rate)
  - `SkuMarginMonitor` scheduled daily, taking snapshots
  - `ShutdownRuleEngine` evaluating 4 kill rules:
    1. Net margin < 30% for 7+ consecutive days → emit pause event
    2. 30-day rolling refund rate > 5% → emit pause event
    3. 30-day rolling chargeback rate > 2% → emit pause + compliance flag
    4. CAC variance > 15% over 14 days → publish `PricingSignal.CacChanged`
  - `SkuPnlReporter` aggregating snapshots into P&L reports
  - All rule firings immutably logged to `capital_rule_audit`

---

### Stage 8: Portfolio Engine 📊 (Portfolio Module)
**Product Flow:** Track experiments with monthly test budgets → scale winners → kill losers (30-day window) → branded vertical conversion → subscription/digital layer

| Step | FR | Status |
|---|---|---|
| Experiment tracking + portfolio orchestration + reallocation | **FR-010** | 📋 Planned |

**Implementation Plan:**
- **FR-010** (Portfolio Orchestration):
  - `Experiment` aggregate (name, hypothesis, budget, validation window, status)
  - `ExperimentStatus` (ACTIVE, VALIDATED, FAILED, LAUNCHED, TERMINATED)
  - `KillWindowMonitor` scheduled daily, finding SKUs with sustained negative signals past kill window
  - `CapitalReallocator` scoring active SKUs by risk-adjusted return (margin × revenue / risk)
  - `PortfolioReporter` aggregating portfolio KPIs (counts, blended margin, capital deployed) with 5-minute cache
  - Advisory reallocation recommendations (human-approved in Phase 1)

---

### Stage 9: Compliance Guards 🛡️ (Compliance Module)
**Product Flow:** Pre-listing checks on every SKU before leaving Ideation stage

| Check | FR | Status |
|---|---|---|
| IP infringement, misleading claims, processor prohibited, gray market source, disclosure violations | **FR-011** | 📋 Planned |

**Implementation Plan:**
- **FR-011** (Compliance Guards):
  - `ComplianceOrchestrator` running 4 checks in parallel (Kotlin coroutines):
    1. **IP Check**: rule-based trademarked terms or LLM-backed via Claude API
    2. **Claims Check**: regex on superlatives/regulated language or LLM analysis
    3. **Processor Check**: validate category against locally-cached Stripe prohibited list
    4. **Sourcing Check**: validate vendor against locally-cached sanctions list (daily refresh)
  - `ComplianceCheckResult` sealed class (Cleared, Failed with reason)
  - `CatalogComplianceListener`: on `ComplianceCleared` → advance to `ValidationPending`; on `ComplianceFailed` → terminate
  - All checks immutably audited in `compliance_audit` table

---

### Stage 10: Feedback Loop & Self-Correction 🔄 (Cross-Module)
**Product Flow:** Conversion rates → CAC trends → refund drivers → vendor metrics → margin signals → self-correction engine → signals back to Pricing/Capital/Portfolio

**Implementation Status:** ⏳ Emerging from stages 1–9

The feedback loop is **implicit** in the event architecture:
- **Pricing** listens to fulfillment (`OrderFulfilled`) and capital (`SkuMarginSnapshot`) to emit `PricingSignal` events
- **Capital** listens to fulfillment (`OrderFulfilled`) and pricing (`PricingDecision`) to update margin snapshots
- **Portfolio** reads capital snapshots and pricing history to compute reallocation recommendations
- All modules publish domain events to `ApplicationEventPublisher` — no module directly calls another

---

### Stage 11: Frontend Dashboard 📊 (React + Vite)
**Product Flow:** Portfolio KPI display, SKU management, pricing history, margin dashboard, experiment tracker

| Component | FR | Status |
|---|---|---|
| Dashboard UI, SKU management, portfolio views | **FR-012** | 📋 Planned |

---

## Implementation Sequence

```
Foundation (Complete)
├── FR-001: Shared domain primitives ✅
└── FR-002: Project bootstrap ✅

Core Catalog Flow (Next)
├── FR-003: SKU lifecycle ✅
├── FR-004: Cost gate → LaunchReadySku
├── FR-005: Stress test → confirmed safe to list
├── FR-006: Pricing engine → signal-driven adjustments
└── FR-011: Compliance pre-launch checks

Operational Excellence
├── FR-007: Vendor governance → SLA monitoring
├── FR-008: Fulfillment → order-to-delivery
└── FR-009: Capital protection → margin safeguards

Intelligence & Scale
├── FR-010: Portfolio orchestration → reallocation
└── FR-012: Dashboard → operator visibility
```

## Cross-Module Event Flow

```
Order Placed
  ↓
Fulfillment.OrderService.create()
  → publishes OrderFulfilled
  ↓
Capital.SkuMarginMonitor (listens)
  → takes daily SkuMarginSnapshot
  → publishes SkuMarginUpdated
  ↓
Pricing.PricingEngine (listens to SkuMarginUpdated as PricingSignal)
  → recalculates margin
  → emits PricingDecision.Adjusted
  ↓
Pricing.PricingDecisionListener
  → calls ShopifyPriceSyncAdapter
  → syncs new price to Shopify
  ↓
Capital.ShutdownRuleEngine (scheduled daily)
  → evaluates all 4 kill rules
  → may emit SkuStateChanged (Paused/Terminated)
  ↓
Catalog.SkuService (listens to SkuStateChanged)
  → transitions SKU state
  → publishes SkuTerminated
  ↓
Capital.ReserveAccountService (listens to SkuTerminated)
  → frees capital for reallocation
  ↓
Portfolio.KillWindowMonitor (scheduled daily)
  → detects sustained negative signals
  → emits termination recommendation
```

## Open Design Decisions (Blocking Some Stages)

From CLAUDE.md Section 15:

1. **How is "willingness to pay" captured?** (survey, pre-order, waitlist)
   - Blocks: FR-??? (Discovery stage implementation)

2. **Rolling reserve: real bank escrow or logical ledger?**
   - FR-009 uses logical ledger in Phase 1; escrow is Phase 2+

3. **Launch platforms: Shopify, Amazon, or both?**
   - FR-006 assumes Shopify; Amazon adapter added in Phase 2

4. **Automation approach: LLM agents, rule-based, or hybrid?**
   - FR-011 (Compliance) includes optional LLM backing via Claude API
   - Portfolio reallocation (FR-010) is advisory (human-approved)

5. **Multi-currency support at launch?**
   - Shared.Money type supports currency; full implementation TBD

6. **Target launch channel for Phase 1?**
   - Assumed: Shopify (configurable in Phase 2)

---

## Metrics

| Metric | Current | Target |
|---|---|---|
| Feature requests complete | 3/12 | 12/12 |
| Modules with domain code | 1 (catalog) | 8 (all bounded contexts) |
| Automated shutdown triggers | 1 (SKU state violation) | 5 (margin, refund, chargeback, CAC, vendor SLA) |
| Event-driven modules | 0 | 6 (pricing, capital, vendor, fulfillment, compliance, portfolio) |
| External adapters | 0 | 6+ (carrier rates, payment processing, inventory, notifications) |
| Database tables | 3 | 15+ |
| Scheduled jobs | 0 | 4 (`SkuMarginMonitor`, `VendorSlaMonitor`, `ShipmentTracker`, `KillWindowMonitor`) |

---

## Notes

- All module-to-module communication goes through domain events (`ApplicationEventPublisher` in Phase 1; Kafka in Phase 2+)
- No module directly calls another module's service — only event-driven
- All state transitions are explicit and validated; invalid transitions throw domain exceptions
- All external API calls have circuit breakers and retry logic (Resilience4j)
- All automated decisions are immutably logged (audit tables)
- The system is designed to fail safe: if a carrier API is down, the SKU is paused, not discounted
