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
6. **Cross-module event listener transaction pattern** — All `@TransactionalEventListener(phase = AFTER_COMMIT)` handlers that write to the database must also carry `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Without `REQUIRES_NEW`, writes are silently discarded because Spring's `TransactionSynchronizationManager` retains stale state in the post-commit callback. Plain `@EventListener` is acceptable only for same-module listeners that should rollback with the publisher.
7. **All domain aggregates with lifecycle states must use explicit transition maps** — following the `SkuStateMachine` and `Order.VALID_TRANSITIONS` pattern. Invalid transitions must throw domain exceptions, never silently succeed.
8. **JSONB columns require `@JdbcTypeCode(SqlTypes.JSON)`** — `columnDefinition = "jsonb"` only affects DDL generation — it does NOT tell Hibernate's JDBC binder to use the JSON SQL type. Without `@JdbcTypeCode`, PostgreSQL rejects the insert with "column is of type jsonb but expression is of type character varying."
9. **Scheduled job orchestrators should NOT be `@Transactional`** — Orchestrators that coordinate multiple independent writes should let each write commit independently. Only batch processors operating on a single logical unit of work should use method-level `@Transactional`. Pattern-copying `@Transactional` from batch processors (like `KillWindowMonitor`) onto orchestrators (like `DemandScanJob`) causes failure status to be silently rolled back.
10. **Modules with `@Entity` classes must have `kotlin("plugin.jpa")` in build.gradle.kts** — The JPA plugin generates synthetic no-arg constructors for `@Entity` classes at compile time. Without it, Hibernate falls back to reflection-based instantiation, which fails at runtime for Kotlin classes with required constructor parameters. Unit tests with mocked repos won't catch this.
11. **XML parsers must use OWASP-hardened configuration** — Never use `DocumentBuilderFactory.newInstance()` with defaults. Use `SecureXmlFactory` from the shared module, which disables external entities and DTDs.
12. **All external API adapters must URL-encode user-supplied values in form-encoded request bodies** — Raw string interpolation in HTTP body construction enables parameter injection. Use `URLEncoder.encode(value, StandardCharsets.UTF_8)` for all user-supplied values.
13. **All @Value annotations on adapter constructor parameters must include empty defaults** — Use `${key:}` syntax so beans can instantiate under any Spring profile. Spring resolves @Value during constructor injection *before* evaluating @ConditionalOnProperty, so missing properties crash even disabled beans. Adapters receiving blank values must guard in `fetch()` with early return (log warning + return empty list).
14. **Never use Kotlin internal constructor on @Component/@Service/@Repository classes** — Kotlin compiles `internal` to JVM `public` with a synthetic `DefaultConstructorMarker` parameter. Spring's constructor resolution cannot satisfy it and throws `NoSuchMethodException` at runtime. Unit tests with mocked dependencies won't catch this. For test injection, use `internal var` fields set via `.also{}` blocks.
15. **Use Jackson get() instead of path() when absence should trigger ?: null-coalescing** — `get()` returns `null` for missing fields; `path()` returns `MissingNode` whose `asText()` returns `""` (never null). Using `path()` with `?.asText() ?: fallback` silently bypasses the fallback. Use `path()` only for nested traversal with `asText("default")`.

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

## Testing Conventions

- When testing `@TransactionalEventListener(AFTER_COMMIT)` listeners directly, events must be published inside `TransactionTemplate.execute {}` — otherwise the listener never fires because there is no transaction to commit.
- Assert exact values in tests, never use `any<Money>()` matchers for financial operations — placeholder matchers hide incorrect calculations.
- Scheduled jobs/monitors that write to the database need a failure-path test verifying error state is persisted — happy-path-only tests miss transaction rollback bugs.
- When fixing a data-source bug, audit ALL consumers of that data source in the same pass — partial fixes leave other consumers broken.
- Time-window by default: any query that counts rows without a time bound on an append-only table is a code smell — unbounded counts grow monotonically and produce incorrect business metrics.

## Feature Request Workflow

This project uses a 6-phase feature request skill (`.claude/skills/feature-request-v2/`):

1. **Discovery** (read-only) — explore codebase, propose kebab-case feature name
2. **Specification** — write `spec.md` in `feature-requests/FR-{NNN}-{name}/`
3. **Planning** — write `implementation-plan.md` with task breakdown by architectural layer
4. **Test-First Gate** — generate runnable tests from spec + plan before implementation
5. **Implementation** — execute plan using dependency-ordered sub-agents, make tests pass, update checkboxes, create `summary.md`
6. **Review-Fix Loop** — PR review cycle until clean (no manual approval needed)

Manual approval is required between phases 1-5. Use the validation script before any action:
```bash
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase {N} --action {read|write|bash} --path "{file}"
```

The v1 skill (`.claude/skills/feature-request/`) is retained as a fallback. FR-001 through FR-023 were built under v1 (4-phase).

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
