# Commerce Engine — Solo-Operator Automation Specification

> **One developer. One machine. Full automation.**  
> Built for a fleet size of one.

| Attribute | Value |
|---|---|
| Document Type | Engineering Specification — Solo Automation |
| Scope | Single operator, personal use, non-SaaS |
| Tech Stack | Kotlin + Spring Boot + Docker Compose |
| Target Deploy | Single VPS ($6–12/mo) or local machine |
| Monthly Budget | ~$80–100/mo total (platforms + infrastructure) |
| Status | Draft v1.0 |

---

## 1. Purpose & Context

This document specifies the design, architecture, and implementation plan for an autonomous commerce engine built and operated by a single developer for personal use. It supersedes the general multi-user specification and reflects all simplifications made possible when the target fleet size is exactly one.

### 1.1 What This System Does

- Discovers demand signals from free public data sources (Google Trends, Reddit, Amazon PA-API)
- Validates willingness to pay before committing to any product
- Enforces a mandatory cost gate — no SKU is listed until all 14 cost components are verified and stress-tested
- Lists products across Shopify, Amazon, Etsy, and TikTok Shop via API
- Routes fulfillment through CJ Dropshipping, Printful, Printify, or Gelato with zero manual steps
- Monitors margins, vendor SLAs, refund rates, and CAC continuously via scheduled jobs
- Auto-pauses or terminates SKUs when thresholds are breached — no human intervention required
- Reallocates capital toward highest-performing SKUs automatically

### 1.2 What This System Is Not

> **This is a personal automation tool, not a SaaS product.** No other users, no multi-tenancy, no auth system, no need to scale to concurrent traffic.

Does **not** require:

- API Gateway or CDN — traffic is only from you
- OAuth / Auth0 / Cognito — no user login system
- Kubernetes or ECS — Docker Compose handles everything
- Kafka or SQS — Spring `@Scheduled` jobs replace all messaging
- Multi-AZ RDS — a single local Postgres instance is sufficient
- Redshift or BigQuery — your data volume fits in Postgres
- Terraform / IaC — `docker-compose up` is the entire deployment
- Amazon SP-API $1,400/year developer fee — exempt as a first-party seller

### 1.3 Engineering Analogy

| Pipeline Concept | Commerce Engine Equivalent |
|---|---|
| Pull Request | Product idea entering validation |
| Test suite (must pass) | Cost gate + stress test (mandatory) |
| Green build | `LaunchReadySku` — structurally proven profitable |
| Deploy to production | SKU listed on platforms |
| Production monitoring | Margin signals, refund rates, SLA checks |
| Automatic rollback | SKU auto-pause or terminate |
| Canary deployment | Small-budget experiment before scaling |
| Resource autoscaler | Capital reallocation to winning SKUs |

---

## 2. Architecture

The entire system runs in a single Docker Compose file on one machine. The domain model is production-grade; the infrastructure is intentionally minimal.

### 2.1 Deployment Options

| Option | Spec | Cost | Best For |
|---|---|---|---|
| Local machine | Your Mac or Linux | $0 | Development and early operation |
| Hetzner VPS CX21 | 2 vCPU, 4GB RAM, 40GB SSD | $6/mo | Always-on 24/7 scheduled jobs |
| DigitalOcean Basic | 1 vCPU, 2GB RAM | $12/mo | Reliable managed networking |
| AWS Lightsail | 1 vCPU, 2GB RAM | $10/mo | Easy upgrades if needs grow |

### 2.2 Docker Compose Stack

```yaml
services:
  app:           # Spring Boot monolith — all modules
    image: commerce-engine:latest
    ports: ['8080:8080']
    env_file: .env
    depends_on: [postgres, redis]

  dashboard:     # React — your personal UI
    image: commerce-dashboard:latest
    ports: ['3000:3000']

  postgres:
    image: postgres:16
    volumes: ['postgres_data:/var/lib/postgresql/data']

  redis:         # Rate cache + pricing signal buffer
    image: redis:7-alpine

volumes:
  postgres_data:
```

### 2.3 Application Modules

| Module | Responsibility | Key Types |
|---|---|---|
| `catalog` | SKU lifecycle, cost gate, stress test, state machine | `CostEnvelope`, `LaunchReadySku`, `SkuState` |
| `pricing` | Dynamic pricing engine, signal processing | `PricingSignal`, `PricingDecision` |
| `vendor` | Supplier registry, SLA monitoring, reliability scoring | `VendorProfile`, `SlaRecord`, `ReliabilityScore` |
| `fulfillment` | Order routing, tracking, auto-refund triggers | `Order`, `FulfillmentRoute`, `TrackingEvent` |
| `capital` | Reserve management, margin dashboards, kill rules | `ReserveAccount`, `MarginSnapshot`, `KillRule` |
| `compliance` | IP/trademark checks, FTC rule enforcement | `TrademarkCheck`, `FtcComplianceRecord` |
| `portfolio` | Experiment tracker, scale/kill orchestration | `Experiment`, `AllocationDecision` |
| `shared` | Common value types used across all modules | `Money`, `Percentage`, `SkuId`, `DomainEvent` |

### 2.4 Scheduled Jobs — Replacing Kafka

`@Scheduled` jobs replace all async messaging. No brokers, no queues, no dead letter handling.

| Job | Frequency | Module | Action |
|---|---|---|---|
| `MarginSweepJob` | Every 6h | capital | Recalculate net margin per SKU; trigger kill rules if below floor |
| `SlaCheckJob` | Every 1h | vendor | Pull vendor fulfillment data; flag breaches; auto-pause affected SKUs |
| `PricingSignalJob` | Every 30min | pricing | Fetch live carrier rates + platform fees; recalculate prices |
| `DemandScanJob` | Every 24h | portfolio | Run demand signals via Google Trends, Reddit, Amazon PA-API |
| `ReserveCalcJob` | Nightly 02:00 | capital | Recalculate rolling reserve; flag if below 10% threshold |
| `VendorScoreJob` | Daily 03:00 | vendor | Recompute vendor reliability scores from rolling 30-day data |
| `StressTestRefreshJob` | Weekly | catalog | Re-run stress tests on all active SKUs against current live rates |

---

## 3. Core Domain Model

The domain model is identical to what would be built for a multi-user SaaS. Solo operation does not simplify the business logic — only the infrastructure around it.

### 3.1 Cost Gate — Sealed Type Enforcement

No SKU may be listed without a fully verified cost envelope. Enforced by the type system, not by convention.

```kotlin
sealed class CostEnvelope {
    data class Unverified(val skuId: SkuId) : CostEnvelope()

    data class Verified(
        val skuId: SkuId,
        val unitCost: Money,
        val packagingCost: Money,
        val freightCost: Money,
        val lastMileCost: Money,              // live — Shippo API
        val dimWeightSurcharge: Money,
        val paymentProcessingFee: Percentage, // live — Stripe API
        val platformFee: Percentage,          // live — platform API
        val modeledCac: Money,
        val refundReserve: Percentage,
        val chargebackReserve: Percentage,
        val taxHandling: Money,               // Shopify Tax API
        val currencyBuffer: Percentage,       // Frankfurter API
        val reverseLogistics: Money,
        val supportAllocation: Money,
        val verifiedAt: Instant
    ) : CostEnvelope() {
        val fullyBurdened: Money get() = /* sum all 14 components */
    }
}
```

### 3.2 Stress Test Gate

A `LaunchReadySku` cannot be constructed without passing the stress test. The type is the proof.

```kotlin
@JvmInline value class StressTestedMargin(val value: Percentage) {
    init { require(value >= Percentage(30)) { "Net margin below 30% floor" } }
}

data class LaunchReadySku(
    val sku: Sku,
    val envelope: CostEnvelope.Verified,
    val stressTestedMargin: StressTestedMargin  // compile-time proof of passing
)

// All five scenarios must maintain gross >= 50%, net >= 30%:
// 2x shipping · +15% CAC · +10% supplier cost · 5% refund rate · 2% chargeback
```

### 3.3 SKU State Machine

```kotlin
sealed class SkuState {
    object Ideation          : SkuState()
    object ValidationPending : SkuState()
    object CostGating        : SkuState()
    object StressTesting     : SkuState()
    object Listed            : SkuState()
    object Paused            : SkuState()
    object Scaled            : SkuState()
    data class Terminated(val reason: TerminationReason) : SkuState()
}

enum class TerminationReason {
    MARGIN_BELOW_THRESHOLD, COST_EXCEEDS_PRICE_CEILING,
    HIGH_REFUND_RATE, HIGH_CHARGEBACK_RATE,
    VENDOR_SLA_BREACH, CAC_INSTABILITY, DYNAMIC_PRICING_INEFFECTIVE
}
```

### 3.4 Money Value Type

Raw `Double` or `BigDecimal` are prohibited in all domain models.

```kotlin
data class Money(val amount: BigDecimal, val currency: Currency) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return Money(amount + other.amount, currency)
    }
    fun marginAgainst(revenue: Money): Percentage =
        Percentage((revenue.amount - amount) / revenue.amount * 100.toBigDecimal())
}

@JvmInline value class Percentage(val value: BigDecimal) {
    operator fun compareTo(other: Percentage) = value.compareTo(other.value)
}
```

---

## 4. API Integrations — Solo Budget

### 4.1 Monthly Budget Allocation (~$99.59/mo total)

| Service | Purpose | Cost | Module |
|---|---|---|---|
| Shopify Basic | Storefront + Admin API + Tax (free to $100K) | $39.00/mo | catalog, fulfillment |
| Amazon Professional | SP-API — own account, dev fee exempt | $39.99/mo | catalog, pricing |
| Etsy Open API v3 | Listings, orders, receipts | $0 | catalog, fulfillment |
| TikTok Shop API | Product mgmt, order routing, inventory | $0 | catalog, fulfillment |
| Shippo Starter | Multi-carrier rates, labels, tracking | $0 | fulfillment, pricing |
| UPS + FedEx direct | Rate redundancy | $0 | pricing |
| Stripe | Payments, disputes, Radar fraud detection | $0 + fees | capital, compliance |
| Frankfurter API | FX rates — free, no key, self-hostable | $0 | pricing, capital |
| CJ Dropshipping | Full supplier API — products, orders, tracking | $0 | vendor, fulfillment |
| Printful / Printify / Gelato | Print-on-demand fulfillment APIs | $0 | vendor, fulfillment |
| Google Merchant API | Free competitive pricing benchmarks | $0 | pricing |
| USPTO Open Data | Trademark checks before listing | $0 | compliance |
| Google Trends RSS + Reddit | Demand signal ingestion | $0 | portfolio |
| Amazon PA-API 5.0 | Product discovery, pricing, BSR | $0 | portfolio |
| Google Analytics 4 API | Conversion + attribution | $0 | portfolio |
| Google Maps Address API | Address validation (10K free/mo) | $0 | fulfillment |
| VADER (local) | Sentiment analysis — runs on-machine | $0 | portfolio |
| SaleHoo directory | Vetted supplier research | $5.60/mo | vendor |
| Buffer | On-demand DataForSEO, ad spend, overages | ~$15/mo | — |

### 4.2 Adapter Interface Pattern

Every external API lives behind an interface. Swap any provider without touching domain logic.

```kotlin
interface CarrierRateProvider {
    fun getRates(shipment: ShipmentSpec): List<CarrierRate>
    fun getTrackingStatus(trackingNumber: String): TrackingEvent
}

interface PlatformAdapter {
    fun listSku(sku: LaunchReadySku): PlatformListingId
    fun pauseSku(id: PlatformListingId)
    fun updatePrice(id: PlatformListingId, newPrice: Money)
    fun getFees(productCategory: String, price: Money): PlatformFees
}

interface SupplierAdapter {
    fun getProductCost(supplierId: String): Money
    fun placeOrder(order: Order): SupplierOrderId
    fun getOrderStatus(id: SupplierOrderId): FulfillmentStatus
}
```

---

## 5. Margin Thresholds & Kill Rules

All thresholds are read from environment variables at job execution time. No redeploy required to change them.

### 5.1 Pre-Launch Requirements

| Requirement | Threshold | Action if Fail |
|---|---|---|
| All 14 cost components verified | 100% | SKU stays in `CostGating` |
| Gross margin | >= 50% | Terminate — cannot launch |
| Net margin (stress-tested) | >= 30% | Terminate — cannot launch |
| Stress: 2x shipping | Net >= 30% | Terminate |
| Stress: +15% CAC | Net >= 30% | Terminate |
| Stress: +10% supplier cost | Net >= 30% | Terminate |
| Stress: 5% refund rate | Net >= 30% | Terminate |
| Stress: 2% chargeback rate | Net >= 30% | Terminate |
| Vendor SLA confirmed | Required | Cannot activate vendor |
| IP/trademark check passed | Required | Cannot list |

### 5.2 Live Monitoring Kill Rules

| Signal | Threshold | Window | Action |
|---|---|---|---|
| Net margin compression | < 30% | 7+ consecutive days | Pause -> auto-terminate at 14 days |
| Sustained negative margin | < 0% | Any 3-day period | Immediate terminate |
| Refund rate | > 5% | Rolling 30 days | Auto-pause + alert |
| Chargeback rate | > 2% | Rolling 30 days | Auto-pause + compliance review |
| Vendor SLA breach rate | > tolerance | Rolling 14 days | Pause all vendor SKUs |
| CAC variance | > 15% from baseline | Rolling 14 days | Trigger pricing engine re-run |
| Carrier rate spike | > 20% | Immediate | Recalculate or pause |
| Rolling reserve | < 10% of revenue | Monthly | Block new launches until restored |

---

## 6. Data Layer

### 6.1 Database Choice

| Option | When to Use | Notes |
|---|---|---|
| SQLite | Local dev, early stage | Zero setup, file-based, Spring Data JPA compatible |
| PostgreSQL (Docker) | Recommended default | Full SQL + JSON, Flyway migrations |
| PostgreSQL (managed) | VPS deployment | Hetzner Managed DB ~$12/mo handles backups |

### 6.2 Core Schema Entities

| Entity | Key Fields | Module |
|---|---|---|
| `skus` | id, state, created_at, terminated_reason, fully_burdened_cost | catalog |
| `cost_envelopes` | sku_id, all 14 cost components, verified_at | catalog |
| `stress_test_results` | sku_id, gross_margin, net_margin, passed, run_at | catalog |
| `platform_listings` | sku_id, platform, listing_id, current_price, status | catalog |
| `vendor_profiles` | id, name, sla_days, defect_rate_pct, reliability_score | vendor |
| `sla_records` | vendor_id, sku_id, expected_date, actual_date, breached | vendor |
| `orders` | id, sku_id, platform, supplier_order_id, status, tracking | fulfillment |
| `margin_snapshots` | sku_id, date, gross_margin, net_margin, cac, refund_rate | capital |
| `reserve_account` | date, revenue, reserve_amount, reserve_pct | capital |
| `experiments` | id, sku_id, budget, start_date, end_date, verdict | portfolio |
| `pricing_signals` | sku_id, signal_type, delta_pct, received_at, applied | pricing |

All schema changes managed via Flyway in `src/main/resources/db/migration/`.

---

## 7. Personal Dashboard

Served at `localhost:3000`. No login screen, no user management, no role system.

### 7.1 Core Views

| View | Purpose | Key Actions |
|---|---|---|
| SKU Portfolio | All active SKUs with real-time margin, state, signals | Pause, terminate, scale |
| Cost Gate Runner | Input product idea; run full cost gate + stress test | Approve to launch, reject to kill |
| Vendor Scorecard | Vendors with SLA performance and breach history | Pause vendor, view SKU impact |
| Margin Monitor | 90-day rolling margin chart per SKU and total portfolio | Drill into component breakdown |
| Experiment Tracker | Active experiments with budget consumed and verdict | Kill or scale |
| Capital Overview | Reserve balance, 30-day revenue, SKU-level P&L | View reserve health |
| Demand Signals | Latest `DemandScanJob` output — trending categories | Initiate new product validation |
| Kill Log | History of auto-terminated and paused SKUs with reasons | Learn from patterns |

### 7.2 Tech Stack

| Concern | Choice |
|---|---|
| Framework | React + Vite |
| UI | shadcn/ui + Tailwind |
| Charts | Recharts |
| State | React Query (TanStack) |
| API | Fetch to Spring Boot `/api/*` |

---

## 8. International Expansion Path

The `PlatformAdapter` and `CarrierRateProvider` interfaces already abstract country-specific logic. Expansion is an adapter implementation problem, not a domain problem.

| Phase | Markets | Key Additions | Trigger |
|---|---|---|---|
| 1 — Now | US only | All current integrations | Launch |
| 2 | Canada, UK, Australia | Amazon SP-API intl, Gelato local production, DHL Express | First profitable US SKU scaled |
| 3 | DE, FR, NL | EU VAT via Stripe Tax, VIES validation (free), Zonos landed cost | Consistent $X,000/mo net profit |
| 4 | Latin America, SE Asia | Mercado Libre API, Shopee API, Easyship cross-border | Validated demand signals in target market |

> Gelato is the highest-leverage Phase 1 integration even before international: 32-country local production means zero customs complexity for POD products at expansion time.

---

## 9. Build Sequence

### Milestone 1 — Foundation (Weeks 1–3)
- Docker Compose: Postgres + Redis + Spring Boot shell
- `shared` module: `Money`, `Percentage`, `SkuId`, `DomainEvent`
- `catalog` module: `CostEnvelope` sealed type, `SkuState` machine
- Flyway migrations for core schema
- Shippo, Stripe, Frankfurter adapters
- `StressTestService`: full 5-scenario simulation
- Manual CLI to run cost gate on a product idea

### Milestone 2 — Launch Capability (Weeks 4–6)
- Shopify GraphQL adapter + Shopify Tax adapter
- CJ Dropshipping + Printful/Printify adapters
- `fulfillment` module: order routing, tracking, SLA monitoring
- Basic React dashboard: SKU list + cost gate runner
- First real product listed end-to-end

### Milestone 3 — Automation (Weeks 7–9)
- All 7 `@Scheduled` jobs wired and running
- `capital` module: reserve calculator, margin snapshots, kill rules
- `pricing` module: signal processing, dynamic price updates
- `vendor` module: SLA tracking, reliability scoring, auto-pause
- Amazon SP-API, Etsy, TikTok Shop adapters
- Full dashboard with all 8 views

### Milestone 4 — Intelligence (Weeks 10–12)
- `portfolio` module: experiment tracker, scale/kill orchestration
- `DemandScanJob`: Google Trends RSS + Reddit + Amazon PA-API
- VADER sentiment analysis on review data
- Google Merchant API: competitive pricing benchmarks
- USPTO adapter: trademark screening pre-launch
- System operates with zero daily intervention

---

## 10. Open Decisions

| Decision | Impact | Options |
|---|---|---|
| Willingness-to-pay validation method | Shapes `ValidationPending` state | Typeform survey, Shopify pre-order, waitlist, manual |
| SQLite vs PostgreSQL to start | SQLite simpler; Postgres more robust | SQLite locally -> migrate to Postgres at VPS deploy |
| VPS vs local machine | Scheduled jobs only run when machine is on | Local for M1-2; Hetzner VPS ($6/mo) for always-on |
| Dropship vs POD vs both | Determines which supplier adapters to build first | Start with one; add others after first sale |
| Primary launch platform | Determines which `PlatformAdapter` to build first | Shopify — best API, most control, Tax included |
| Keepa integration timing | $54/mo but best Amazon demand data | Start with PA-API free; add Keepa for Amazon-focused research |

---

## 11. Operating Philosophy

> **You are not optimizing for revenue. You are optimizing for:**  
> Durable net profit · Capital efficiency · Low volatility · Automated resilience

**The system does the work.** Your job is to review kill logs, approve new product validations, and occasionally update thresholds. If you are manually processing orders, something is wrong.

**Protect margin before revenue.** A $0 revenue month with $0 loss is better than a $10,000 revenue month with $3,000 in losses. The stress test exists so you never learn this lesson the expensive way.

**The domain model is the moat.** Your proprietary margin benchmarks, vendor reliability data, and demand signals compound into an advantage no competitor can replicate by reading this document.

---

*Validate before scaling. Protect margin before revenue. Terminate weaknesses early. Compound strengths aggressively.*
