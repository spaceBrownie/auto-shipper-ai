# Commerce Engine — Product Requirements & Engineering Specification

## Vision

Build an autonomous, capital-light commerce engine that discovers, validates, launches, and scales profitable physical and digital products with minimal human oversight. The system must be self-correcting, financially resilient, and architecturally extensible.

**Mandate: Long-term durable net profit — not short-term revenue.**

---

## 1. Primary Objectives

Non-negotiable operating goals, in priority order:

1. Net profitability per SKU before any scaling decision
2. Protected margin maintained under stress conditions
3. Positive, trustworthy customer experience
4. Automated compliance and financial safeguards
5. Recurring or repeatable revenue wherever viable
6. Dynamic cost-aware pricing with automatic adjustment
7. Automatic shutdown of underperforming SKUs
8. Continuous capital reallocation toward highest risk-adjusted return

---

## 2. Core Business Model — Demand-First Product Engine

The system operates strictly demand-first. No production or sourcing occurs before validated demand.

**Mandatory sequencing:**

1. Identify unmet or under-served demand (signal-based discovery)
2. Validate willingness to pay before any production commitment
3. Pre-sell or confirm order intent where possible
4. Orchestrate vendor sourcing only after validation is confirmed
5. Fulfill via drop-ship, print-on-demand, white-label, or contract manufacturing
6. Scale profitable SKUs; terminate unprofitable ones quickly
7. Reallocate freed capital dynamically to higher-return opportunities

**Constraint:** Inventory ownership is prohibited unless a documented risk-adjusted return analysis justifies an exception.

---

## 3. Mandatory Pre-Listing Cost Gate

> **No SKU may be listed without a fully verified and stress-tested cost envelope. No exceptions.**

### 3.1 Fully Burdened Cost Components

Every component must be verified — not estimated — before a SKU advances:

| Component | Verification Method |
|---|---|
| Unit production cost | Vendor quote or contract |
| Packaging | Supplier confirmation |
| Freight / inbound logistics | Carrier API or confirmed rate |
| Last-mile shipping | Live carrier API (UPS, FedEx, USPS) |
| Dimensional weight surcharge | Calculated from actual package dims |
| Payment processing fee | Stripe API or processor schedule |
| Platform fee | Shopify / marketplace published rate |
| Modeled CAC | Derived from channel benchmarks or historical data |
| Refund reserve | Fixed percentage applied per stress-test model |
| Chargeback reserve | Fixed percentage applied per stress-test model |
| Sales tax / VAT | Jurisdiction-specific calculation |
| Currency risk buffer | Applied for non-USD sourcing |
| Returns + reverse logistics | Carrier rate or vendor policy |
| Customer support allocation | Per-ticket cost × modeled contact rate |

**Live API integrations required:** UPS, FedEx, USPS (carrier rates), Stripe (processing fees), Shopify (platform fees). No hardcoded rates.

### 3.2 Engineering: Cost Envelope as a Sealed Type

The cost gate must be enforced structurally — not by convention. A `CostEnvelope` is either `Unverified` or `Verified`; a SKU cannot transition to `Listed` state without holding a `Verified` envelope.

```kotlin
sealed class CostEnvelope {
    data class Unverified(val skuId: SkuId) : CostEnvelope()

    data class Verified(
        val skuId: SkuId,
        val unitCost: Money,
        val packagingCost: Money,
        val freightCost: Money,
        val lastMileCost: Money,           // from live carrier API
        val dimWeightSurcharge: Money,
        val paymentProcessingFee: Percentage,
        val platformFee: Percentage,
        val modeledCac: Money,
        val refundReserve: Percentage,
        val chargebackReserve: Percentage,
        val taxHandling: Money,
        val currencyBuffer: Percentage,
        val reverseLogistics: Money,
        val supportAllocation: Money,
        val verifiedAt: Instant
    ) : CostEnvelope() {
        val fullyBurdened: Money get() = /* sum all cost components */
    }
}
```

A `Money` type must be a value class carrying both amount and currency — raw `Double` or `BigDecimal` are prohibited in domain models:

```kotlin
data class Money(val amount: BigDecimal, val currency: Currency) {
    operator fun plus(other: Money): Money { /* enforce same currency */ }
    fun marginAgainst(revenue: Money): Percentage { /* (revenue - cost) / revenue */ }
}
```

---

## 4. Stress-Test Requirement

Before listing, every SKU must pass a simulated worst-case scenario:

| Stress Variable | Applied Multiplier |
|---|---|
| Shipping cost | 2× |
| CAC | +15% |
| Supplier cost | +10% |
| Refund rate | 5% of revenue |
| Chargeback rate | 2% of revenue |

**Pass condition:**
- Gross margin ≥ 50%
- Protected net margin after all stress variables ≥ 30%

**Fail condition:** SKU is terminated. No override permitted.

### Engineering: Stress Test as a Type Gate

A `LaunchReadySku` can only be constructed after passing the stress test. The type itself is the proof:

```kotlin
@JvmInline value class StressTestedMargin(val value: Percentage) {
    init { require(value >= Percentage(30)) { "Protected margin below 30% threshold" } }
}

data class LaunchReadySku(
    val sku: Sku,
    val envelope: CostEnvelope.Verified,
    val stressTestedMargin: StressTestedMargin  // construction requires passing the test
)
```

The `launch()` use case accepts only `LaunchReadySku`. Structurally impossible to bypass.

---

## 5. SKU Lifecycle & State Machine

SKUs move through a strict lifecycle. State transitions emit domain events consumed by downstream modules (pricing, capital, vendor, fulfillment).

```kotlin
sealed class SkuState {
    object Ideation        : SkuState()
    object ValidationPending : SkuState()
    object CostGating      : SkuState()
    object StressTesting   : SkuState()
    object Listed          : SkuState()
    object Paused          : SkuState()
    object Scaled          : SkuState()
    data class Terminated(val reason: TerminationReason) : SkuState()
}

enum class TerminationReason {
    MARGIN_BELOW_THRESHOLD,
    COST_EXCEEDS_PRICE_CEILING,
    HIGH_REFUND_RATE,
    HIGH_CHARGEBACK_RATE,
    VENDOR_SLA_BREACH,
    CAC_INSTABILITY,
    DYNAMIC_PRICING_INEFFECTIVE
}
```

**Valid transitions only** — invalid transitions throw a domain exception, never silently succeed.

---

## 6. Pricing Logic

Pricing follows a strict backward-induction model:

> Validated willingness-to-pay → fit cost envelope inside price ceiling with required margin buffer → confirm → launch.

If the cost envelope cannot fit within the validated price ceiling while maintaining target margins, the SKU is terminated — not discounted or approximated.

### Dynamic Pricing

The pricing engine listens for cost signals and recalculates in real time:

```kotlin
sealed class PricingSignal {
    data class ShippingCostChanged(val delta: Percentage) : PricingSignal()
    data class VendorCostChanged(val delta: Percentage)   : PricingSignal()
    data class CacChanged(val delta: Percentage)          : PricingSignal()
    data class PlatformFeeChanged(val delta: Percentage)  : PricingSignal()
}

sealed class PricingDecision {
    data class Adjusted(val newPrice: Money)          : PricingDecision()
    data class PauseRequired(val reason: String)      : PricingDecision()
    data class TerminateRequired(val reason: String)  : PricingDecision()
}
```

If dynamic adjustment would either harm conversion rate beyond a defined threshold or push margin below the 30% floor, the SKU is automatically paused and a `PauseRequired` event is emitted.

---

## 7. Vendor Governance

### Pre-Activation Checklist (all required before SKU activation)

- SLA confirmed in writing
- Defect rate documented and within tolerance
- Scalability ceiling confirmed
- Fulfillment time windows confirmed
- Replacement and refund policies confirmed
- Vendor reliability score calculated

### Ongoing Monitoring

Vendor performance is continuously tracked. If SLA breach rate exceeds tolerance threshold, all associated SKUs are auto-paused and a `VendorSlaBreached` domain event is emitted. Resolution is required before reinstatement.

**Vendor reliability scoring** must factor: on-time rate, defect rate, breach history, responsiveness.

---

## 8. Customer Experience Safeguards

The following must be implemented — not optional:

- Transparent delivery timelines displayed at point of sale
- Real-time shipment tracking integration
- Automated proactive delay alerts to customers
- Clear, accessible refund policy
- Auto-refund trigger on confirmed SLA breach
- Inventory availability validated before payment is captured

Customer trust is treated as a long-term balance sheet asset.

---

## 9. Capital Protection Framework

### Reserve Requirements

- Rolling reserve account: 10–15% of revenue, maintained at all times
- 90-day rolling margin monitoring per SKU
- SKU-level P&L dashboards with real-time visibility

### Automatic Shutdown Triggers

A SKU is automatically paused or terminated when any of the following conditions are sustained beyond the defined window:

| Signal | Threshold | Action |
|---|---|---|
| Net margin compression | Below 30% for 7+ days | Pause → review → terminate |
| Refund rate | > 5% rolling 30-day | Pause |
| Chargeback rate | > 2% rolling 30-day | Pause + compliance review |
| Vendor SLA breach rate | > tolerance | Pause all vendor SKUs |
| CAC instability | > 15% variance 14-day | Pricing engine re-run |

**No SKU may continue operating at sustained negative net margin.**

---

## 10. Portfolio & Scaling Logic

The system operates as an active portfolio, not a static catalog:

- Run multiple experiments monthly (small budget, defined validation window)
- Launch selectively based on stress-test results only
- Terminate underperformers within a defined kill window (default: 30 days from first signal)
- Scale validated winners aggressively
- Convert high-performing SKUs into branded verticals over time
- Develop subscription or digital layers on proven physical SKUs
- Continuously pursue recurring revenue conversion from one-time buyers

---

## 11. Data & Feedback Loop

Continuous collection and automated analysis of:

- Conversion rates per channel and SKU
- CAC trends (absolute and vs. baseline)
- Customer reviews and sentiment signals
- Refund drivers (categorized by reason code)
- Margin compression signals (cost component level)
- Vendor performance metrics (SLA, defect, fulfillment time)

Data drives automatic self-correction. Capital is reallocated toward highest risk-adjusted return opportunities without manual intervention.

---

## 12. Ethical and Legal Compliance

Non-negotiable constraints:

- No IP infringement
- No misleading product claims
- No regulatory violations (consumer protection, advertising standards)
- Full compliance with payment processor acceptable use policies
- No gray-market or unverified sourcing
- Respect all applicable consumer protection laws

Long-term viability overrides short-term revenue tactics in every case.

---

## 13. Long-Term Strategic Evolution

The system evolves in phases:

| Phase | Focus |
|---|---|
| 1 — Transactional | Validate model, achieve consistent net margin |
| 2 — Vertical | Convert top SKUs into branded micro-verticals |
| 3 — Recurring | Introduce subscription and digital add-on layers |
| 4 — Data | Leverage proprietary demand and margin data as a strategic asset |
| 5 — Enterprise | Build durable brand equity and higher-margin owned assets |

---

## 14. System Architecture

### Bounded Contexts (Modular Monolith — Phase 1)

Start as a modular monolith. Promote to independent services only when a module's scaling or deployment needs diverge.

| Module | Responsibility |
|---|---|
| `catalog` | SKU lifecycle, cost gate, stress test, state machine |
| `pricing` | Dynamic pricing engine, signal processing |
| `vendor` | Vendor registry, SLA monitoring, scoring |
| `fulfillment` | Order routing, carrier integration, tracking, delay alerts |
| `capital` | Reserve management, margin dashboards, kill-rule execution |
| `compliance` | IP checks, regulatory guards, processor rule validation |
| `portfolio` | Experiment tracking, scale/kill orchestration, reinvestment logic |
| `shared` | `Money`, `Percentage`, `SkuId`, domain events, common value types |

### Technology Stack

| Concern | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Null safety, sealed classes, value types for domain modeling |
| Framework | Spring Boot 3.x | Familiar, production-proven, excellent Kotlin support |
| Persistence | PostgreSQL + Spring Data JPA + Flyway | Reliable, schema-versioned |
| Events (internal) | Spring `ApplicationEventPublisher` | Decouples modules without infrastructure overhead |
| Events (async, Phase 2+) | Kafka | When modules need independent deployment |
| Carrier APIs | `CarrierRateProvider` interface + UPS/FedEx/USPS adapters | Adapter pattern; swap providers without domain changes |
| Scheduling | Spring `@Scheduled` | SLA sweeps, margin checks, reserve calculations |
| Observability | Micrometer + Grafana | SKU-level margin and KPI dashboards |
| Frontend | React + Vite + shadcn/ui | Portfolio dashboard, SKU management |
| Python (optional) | FastAPI microservice | ML-based demand signal scoring, CAC modeling if needed |

### Key Engineering Constraints

- **No raw `Double` or `BigDecimal` in domain models** — all monetary values use the `Money` value type with explicit currency
- **No SKU state transitions by convention** — all transitions are explicit, validated, and emit domain events
- **No hardcoded rates** — carrier fees, platform fees, and processing rates are fetched from live APIs
- **Cost gate bypass is architecturally impossible** — `LaunchReadySku` cannot be constructed without `CostEnvelope.Verified` and `StressTestedMargin`
- **All shutdown triggers are automated** — no manual step required to pause or terminate a SKU once thresholds are breached

---

## 15. Open Decisions (Resolve Before Module Design)

These must be answered before implementing the affected modules:

| Decision | Impact |
|---|---|
| How is "willingness to pay" captured? (survey, pre-order, waitlist) | Shapes the `ValidationPending` state and `catalog` module input |
| Is the rolling reserve a real bank escrow or a logical ledger? | Determines `capital` module's external integration needs |
| Which sales platforms are in scope at launch? (Shopify, Amazon, both) | Determines `PlatformAdapter` implementations needed |
| Will automation use LLM-based agents, rule-based logic, or hybrid? | Determines `portfolio` orchestration architecture |
| Multi-currency support required at launch? | `Money` type complexity and `capital` module FX handling |
| Target launch channel for Phase 1? | Constrains CAC modeling and pricing engine inputs |

---

## 16. Operating Philosophy

> Optimize for: **durable net profit, capital efficiency, low volatility, automated resilience, and scalable architecture.**

Not revenue. Not growth. Profit that compounds.

Validate before scaling. Protect margin before revenue. Terminate weaknesses early. Compound strengths aggressively.
