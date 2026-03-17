# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Commerce Engine ("Auto Shipper AI") — an autonomous, capital-light commerce system that discovers, validates, launches, and scales profitable physical and digital products. The system operates demand-first: no production or sourcing before validated demand. The mandate is **durable net profit**, not revenue or growth.

This is a greenfield project. The spec (`spec.md`) and requirements (`requirements.md`) define the system; code implementation follows.

## Technology Stack

- **Language:** Kotlin (null safety, sealed classes, value types for domain modeling)
- **Backend:** Spring Boot 3.x with Spring Data JPA
- **Database:** PostgreSQL with Flyway migrations
- **Internal Events:** Spring `ApplicationEventPublisher` (Phase 1); Kafka when modules need independent deployment (Phase 2+)
- **Scheduling:** Spring `@Scheduled` for SLA sweeps, margin checks, reserve calculations
- **Observability:** Micrometer + Grafana
- **Frontend:** React + Vite + shadcn/ui (portfolio dashboard, SKU management)
- **Optional:** FastAPI microservice for ML-based demand signal scoring / CAC modeling

## Architecture

Modular monolith with bounded contexts. Promote to independent services only when a module's scaling or deployment needs diverge.

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

## Critical Engineering Constraints

These are non-negotiable and must be enforced structurally (by types), not by convention:

1. **No raw `Double` or `BigDecimal` in domain models** — all monetary values use the `Money` value type carrying both amount and currency.
2. **`CostEnvelope` is a sealed type** — a SKU cannot transition to `Listed` without a `Verified` envelope. `LaunchReadySku` requires both `CostEnvelope.Verified` and `StressTestedMargin`. Structurally impossible to bypass the cost gate.
3. **No hardcoded rates** — carrier fees (UPS, FedEx, USPS), platform fees (Shopify), and processing rates (Stripe) must come from live APIs.
4. **SKU state transitions are explicit** — all transitions validated and emit domain events. Invalid transitions throw domain exceptions, never silently succeed.
5. **All shutdown triggers are automated** — no manual step required to pause/terminate a SKU once thresholds are breached.

## Domain Model Patterns

### Money Type (value class)
```kotlin
data class Money(val amount: BigDecimal, val currency: Currency) {
    operator fun plus(other: Money): Money { /* enforce same currency */ }
    fun marginAgainst(revenue: Money): Percentage { /* (revenue - cost) / revenue */ }
}
```

### SKU Lifecycle State Machine
States: `Ideation → ValidationPending → CostGating → StressTesting → Listed → Scaled` (or `Paused`, `Terminated` from any active state). Valid transitions only.

### Stress Test Gate
Before listing, every SKU must pass: 2x shipping, +15% CAC, +10% supplier cost, 5% refund rate, 2% chargeback rate. Pass requires gross margin >= 50% and protected net margin >= 30%. Fail = terminated, no override.

### Pricing
Backward-induction: validated willingness-to-pay → fit cost envelope inside price ceiling with margin buffer → confirm → launch. If costs can't fit, SKU is terminated (not discounted). Dynamic pricing reacts to `PricingSignal` events and emits `PricingDecision` (Adjusted, PauseRequired, TerminateRequired).

## Build & Test Commands

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.catalog.StressTestSpec"

# Run application
./gradlew bootRun

# Database migrations
./gradlew flywayMigrate      # run migrations
./gradlew flywayInfo         # show migration status
./gradlew flywayRepair       # repair checksum mismatches
./gradlew flywayValidate     # validate migrations

# Target test database
DB_URL=jdbc:postgresql://localhost:5432/autoshipper_test ./gradlew flywayMigrate

# Frontend (once initialized)
cd frontend && npm install && npm run dev
```

## Feature Request Workflow

This project uses a 4-phase feature request skill (`.claude/skills/feature-request/`):

1. **Discovery** (read-only) — explore codebase, propose kebab-case feature name
2. **Specification** — write `spec.md` in `feature-requests/FR-{NNN}-{name}/`
3. **Planning** — write `implementation-plan.md` with task breakdown by architectural layer
4. **Implementation** — execute plan using layer-specific sub-agents, update checkboxes, create `summary.md`

Manual approval is required between phases. Use the validation script before any action:
```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase {N} --action {read|write|bash} --path "{file}"
```

## Key Business Rules

- **Inventory ownership prohibited** unless documented risk-adjusted return analysis justifies an exception
- **No SKU listed without fully verified cost envelope** — all 13 cost components must be verified (not estimated)
- **Net margin floor: 30%** after stress testing; gross margin target: 50%+
- **Rolling reserve: 10-15% of revenue** maintained at all times
- **Auto-shutdown triggers:** margin below 30% for 7+ days, refund rate > 5%, chargeback rate > 2%, vendor SLA breach
- **Capital reallocation** is continuous toward highest risk-adjusted return opportunities

## Open Design Decisions

Resolve before implementing affected modules (see spec.md Section 15):
- How is "willingness to pay" captured? (survey, pre-order, waitlist)
- Rolling reserve: real bank escrow or logical ledger?
- Launch platforms: Shopify, Amazon, or both?
- Automation approach: LLM agents, rule-based, or hybrid?
- Multi-currency support at launch?
- Target launch channel for Phase 1?
