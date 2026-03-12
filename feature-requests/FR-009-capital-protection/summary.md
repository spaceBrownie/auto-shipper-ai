# FR-009: Capital Protection — Implementation Summary

## Feature Summary

Implemented the capital protection module: a logical rolling reserve, per-SKU margin monitoring, 4 automated shutdown rules (kill rules), and SKU-level P&L reporting. The system runs on two scheduled jobs — a 6-hour margin sweep and a nightly reserve reconciliation — and fires domain events that automatically pause or terminate degrading SKUs without human intervention.

## Changes Made

### Shared Events (2 files)
- `ShutdownRuleTriggered` — emitted when a kill rule fires (margin breach, refund rate, chargeback rate); consumed by `ShutdownRuleListener` in catalog to pause/terminate SKUs
- `MarginSnapshotTaken` — emitted after each margin snapshot is recorded; available for pricing module consumption

### Domain Entities (5 files)
- `ReserveAccount` — logical ledger for the rolling reserve (10–15% of revenue)
- `MarginSnapshot` — daily per-SKU margin observation with gross/net margin, refund rate, chargeback rate, CAC variance
- `KillRule` — sealed class with 4 variants: `MarginBreach`, `RefundRateBreach`, `ChargebackRateBreach`, `CacInstability`
- `CapitalRuleAudit` — append-only audit log of every rule firing
- `CapitalOrderRecord` — capital's local view of order data (bounded context read model)

### Domain Services (7 files)
- `ShutdownRuleEngine` — evaluates all 4 kill rules against snapshots and order data; publishes `ShutdownRuleTriggered` or `PricingSignal.CacChanged`
- `MarginSweepJob` — `@Scheduled(fixedRate = 21_600_000)` every 6h; computes snapshots for active SKUs, triggers rule evaluation
- `ReserveCalcJob` — `@Scheduled(cron = "0 0 2 * * *")` nightly at 02:00; reconciles reserve balance, flags if below minimum
- `ReserveAccountService` — credits reserve on order events, provides balance/health status
- `SkuPnlReporter` — aggregates snapshots into P&L reports for any SKU and date range
- `ActiveSkuProvider` / `JpaActiveSkuProvider` — cross-module boundary interface; queries `skus` table for LISTED/SCALED SKUs
- `OrderAmountProvider` / `JpaOrderAmountProvider` — cross-module boundary; reads order amounts from `orders` table

### Listeners (2 files)
- `OrderEventListener` (capital) — listens for `OrderFulfilled`, creates `CapitalOrderRecord`, credits reserve (idempotent)
- `ShutdownRuleListener` (catalog) — listens for `ShutdownRuleTriggered`, pauses/terminates LISTED/SCALED SKUs only

### Handler Layer (3 files)
- `CapitalController` — `GET /api/capital/reserve` (balance + health), `GET /api/capital/skus/{id}/pnl` (P&L report)
- `ReserveResponse`, `SkuPnlResponse` DTOs

### Persistence (4 files)
- `ReserveAccountRepository`, `MarginSnapshotRepository`, `CapitalRuleAuditRepository`, `CapitalOrderRecordRepository`

### Configuration (2 files)
- `CapitalConfig` — `@ConfigurationProperties(prefix = "capital")` with all 8 thresholds
- `application.yml` — capital config defaults added

### Database Migration (1 file)
- `V13__capital.sql` — 4 tables: `reserve_accounts`, `margin_snapshots`, `capital_rule_audit`, `capital_order_records` with indexes and constraints

## Files Modified

| File | Description |
|---|---|
| `modules/shared/src/.../events/ShutdownRuleTriggered.kt` | New domain event |
| `modules/shared/src/.../events/MarginSnapshotTaken.kt` | New domain event |
| `modules/capital/src/.../domain/ReserveAccount.kt` | JPA entity |
| `modules/capital/src/.../domain/MarginSnapshot.kt` | JPA entity |
| `modules/capital/src/.../domain/KillRule.kt` | Sealed class |
| `modules/capital/src/.../domain/CapitalRuleAudit.kt` | JPA entity |
| `modules/capital/src/.../domain/CapitalOrderRecord.kt` | JPA entity |
| `modules/capital/src/.../domain/service/ShutdownRuleEngine.kt` | Core rule evaluator |
| `modules/capital/src/.../domain/service/MarginSweepJob.kt` | 6h scheduled job |
| `modules/capital/src/.../domain/service/ReserveCalcJob.kt` | Nightly reconciliation |
| `modules/capital/src/.../domain/service/ReserveAccountService.kt` | Reserve operations |
| `modules/capital/src/.../domain/service/SkuPnlReporter.kt` | P&L aggregation |
| `modules/capital/src/.../domain/service/ActiveSkuProvider.kt` | Cross-module interface |
| `modules/capital/src/.../domain/service/JpaActiveSkuProvider.kt` | Native query impl |
| `modules/capital/src/.../domain/service/OrderAmountProvider.kt` | Cross-module interface |
| `modules/capital/src/.../domain/service/JpaOrderAmountProvider.kt` | Native query impl |
| `modules/capital/src/.../listener/OrderEventListener.kt` | Event listener |
| `modules/capital/src/.../handler/CapitalController.kt` | REST controller |
| `modules/capital/src/.../handler/dto/ReserveResponse.kt` | DTO |
| `modules/capital/src/.../handler/dto/SkuPnlResponse.kt` | DTO |
| `modules/capital/src/.../config/CapitalConfig.kt` | Configuration properties |
| `modules/capital/src/.../persistence/ReserveAccountRepository.kt` | Repository |
| `modules/capital/src/.../persistence/MarginSnapshotRepository.kt` | Repository |
| `modules/capital/src/.../persistence/CapitalRuleAuditRepository.kt` | Repository |
| `modules/capital/src/.../persistence/CapitalOrderRecordRepository.kt` | Repository |
| `modules/capital/build.gradle.kts` | Added test dependencies |
| `modules/catalog/src/.../service/ShutdownRuleListener.kt` | Event listener |
| `modules/app/src/main/resources/db/migration/V13__capital.sql` | Migration |
| `modules/app/src/main/resources/application.yml` | Capital config defaults |

## Testing Completed

### Unit Tests — 23 tests, all passing
- **ShutdownRuleEngineTest** (10 tests): all 4 kill rules with boundary conditions (below/above thresholds), division-by-zero guard
- **ReserveAccountServiceTest** (6 tests): credit, balance, health status, account creation, accumulation
- **SkuPnlReporterTest** (4 tests): aggregation accuracy, empty snapshots, snapshot count
- **OrderEventListenerTest** (3 tests): event processing, idempotency, missing order handling

### Integration Tests — 6 tests (require Docker/Testcontainers)
- Margin degradation auto-pause (7-day breach)
- Refund rate breach auto-pause
- Chargeback rate breach auto-pause with compliance action
- Reserve reconciliation drift correction
- ShutdownRuleListener guards (IDEATION SKU not paused)
- ShutdownRuleListener pauses SCALED SKU

### Build Status
- `./gradlew build` — BUILD SUCCESSFUL (all modules)

## Post-Implementation Review (Unblocked)

- **Percentage validation bug fixed**: `MarginSnapshotTaken` event and `SkuPnlReport` originally used `Percentage` type (constrained to 0–100%), but net margins can go negative for degrading SKUs. Changed to raw `BigDecimal` to prevent runtime crashes.
- **Transaction safety confirmed**: All listeners use `@EventListener` (synchronous, same transaction) — safe from PM-001 `@TransactionalEventListener(AFTER_COMMIT)` silent persistence issue.
- **Cross-module boundary pattern confirmed**: `ActiveSkuProvider` and `OrderAmountProvider` use native queries — consistent with bounded-context design.

## Deployment Notes

- Run `V13__capital.sql` migration before deploying (Flyway handles automatically)
- Capital config defaults are in `application.yml`; override via environment variables if needed
- `MarginSweepJob` starts immediately on boot (fixedRate); `ReserveCalcJob` runs at 02:00 daily
- No external service dependencies — all Phase 1 capital protection is internal
