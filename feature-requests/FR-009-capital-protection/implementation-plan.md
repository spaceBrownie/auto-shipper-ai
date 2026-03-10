> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/capital/src/...` instead of `capital/src/...`.

# FR-009: Capital Protection — Implementation Plan

## Technical Design

The `capital` module maintains a logical rolling reserve, computes SKU-level P&L from order and cost data, and runs a `ShutdownRuleEngine` that evaluates the 4 automated shutdown conditions on a schedule. All rule firings are immutably logged. Cross-module communication is event-only — capital listens to fulfillment and pricing events and publishes its own events consumed by catalog and pricing.

```
capital/src/main/kotlin/com/autoshipper/capital/
├── domain/
│   ├── ReserveAccount.kt              (logical ledger entity)
│   ├── MarginSnapshot.kt              (per-SKU daily margin observation)
│   ├── KillRule.kt                    (sealed class — 4 shutdown conditions)
│   ├── CapitalRuleAudit.kt            (append-only audit log entity)
│   └── CapitalOrderRecord.kt          (capital's local view of order data)
├── domain/service/
│   ├── ReserveAccountService.kt       (credit on order event, nightly reconciliation)
│   ├── MarginSweepJob.kt              (@Scheduled fixedRate=6h — snapshot + rule eval)
│   ├── ReserveCalcJob.kt              (@Scheduled cron nightly 02:00 — reserve reconciliation)
│   ├── ShutdownRuleEngine.kt          (evaluates KillRules, publishes ShutdownRuleTriggered)
│   └── SkuPnlReporter.kt              (P&L aggregation from snapshots)
├── listener/
│   └── OrderEventListener.kt          (@EventListener on OrderFulfilled — credits reserve, records order)
├── handler/
│   ├── CapitalController.kt           (REST endpoints)
│   └── dto/
│       ├── ReserveResponse.kt
│       └── SkuPnlResponse.kt
├── persistence/
│   ├── ReserveAccountRepository.kt
│   ├── MarginSnapshotRepository.kt
│   ├── CapitalRuleAuditRepository.kt
│   └── CapitalOrderRecordRepository.kt
└── config/
    └── CapitalConfig.kt               (@ConfigurationProperties — all thresholds)
```

Cross-module listener in catalog:
```
catalog/src/main/kotlin/com/autoshipper/catalog/listener/
└── ShutdownRuleListener.kt            (@EventListener on ShutdownRuleTriggered → pause/terminate SKU)
```

New shared domain events:
```
shared/src/main/kotlin/com/autoshipper/shared/events/
├── ShutdownRuleTriggered.kt           (capital → catalog: skuId, rule, conditionValue, action)
└── MarginSnapshotTaken.kt             (capital → pricing: skuId, netMargin, grossMargin)
```

## Architecture Decisions

- **Logical ledger in Phase 1**: The rolling reserve is a `ReserveAccount` entity tracking a balance. No external bank escrow in Phase 1. Real escrow integration is a Phase 2 decision (per solo-operator spec open design decisions).
- **Two scheduled jobs per solo-operator spec**: `MarginSweepJob` runs every 6 hours (recalculates margins, takes snapshots, triggers kill rules). `ReserveCalcJob` runs nightly at 02:00 (reconciles reserve balance, flags if below 10% threshold). Per-order reserve crediting via `OrderEventListener` provides real-time updates between reconciliation runs.
- **`MarginSnapshot` as time-series**: Daily margin observations enable 90-day rolling calculations without reprocessing raw order data on every query. Named `MarginSnapshot` to match the solo-operator spec's key type naming (`MarginSnapshot`).
- **`KillRule` as a first-class sealed class**: The solo-operator spec lists `KillRule` as a key type for the capital module. Each variant carries its own threshold and evaluation logic.
- **`ShutdownRuleEngine` is stateless and event-emitting**: It reads snapshots, evaluates `KillRule` conditions, and publishes `ShutdownRuleTriggered` events. It does **not** directly call `SkuService` or mutate SKU state — that's the catalog module's responsibility via `ShutdownRuleListener`. This follows the established `VendorBreachListener` pattern and the architecture rule: "No module directly calls another module's service — only event-driven."
- **Capital maintains its own order view**: `OrderEventListener` listens for `OrderFulfilled` events and writes to a local `capital_order_records` table. Capital never imports or depends on the `:fulfillment` module. This is correct bounded-context design — capital owns its own read model of order data.
- **Cost data via read-only query**: Margin calculation needs `fullyBurdenedCost` from the `cost_envelopes` table. Capital reads this via a read-only repository (same database, no cross-module Kotlin dependency). It does **not** depend on `CostEnvelope.Verified` (which has `internal constructor`). Per the cross-module boundary pattern, capital accepts `fullyBurdenedCost: Money` rather than the sealed type.
- **All thresholds in `@ConfigurationProperties`**: The 30%, 7-day, 5%, 2%, 15%, 14-day, 10%, 15% reserve rate values are configurable — not hardcoded.
- **Rule audit log is append-only**: `capital_rule_audit` is never updated, only inserted. This provides a tamper-evident record of every automated decision.

## Layer-by-Layer Implementation

### Domain Layer
- `ReserveAccount`: id, balance (`Money`), targetRateMin (`Percentage`), targetRateMax (`Percentage`), lastUpdatedAt
- `MarginSnapshot`: id, skuId, snapshotDate, grossMargin (`Percentage`), netMargin (`Percentage`), revenue (`Money`), totalCost (`Money`), refundRate (`Percentage`), chargebackRate (`Percentage`), cacVariance (`Percentage`)
- `KillRule`: sealed class with 4 variants:
  - `MarginBreach(sustainedDays: Int, floorPercent: Percentage)` — net margin < floor for N+ consecutive days
  - `RefundRateBreach(windowDays: Int, maxRate: Percentage)` — 30-day rolling refund rate > threshold
  - `ChargebackRateBreach(windowDays: Int, maxRate: Percentage)` — 30-day rolling chargeback rate > threshold
  - `CacInstability(windowDays: Int, maxVariance: Percentage)` — CAC variance > threshold over N days
- `CapitalRuleAudit`: id, skuId, rule (String), conditionValue (String), action (String), firedAt
- `CapitalOrderRecord`: id, orderId, skuId, totalAmount (`BigDecimal`), currency, status, refunded (Boolean), chargebacked (Boolean), recordedAt

### Domain Service
- `ReserveAccountService.creditFromOrder(orderId, revenue)`: adds `revenue * reserveRate` to balance on order event
- `ReserveAccountService.getBalance()`: returns current balance and health status (healthy, warning, critical)
- `ReserveCalcJob.reconcile()`: scheduled nightly 02:00 — recalculates total reserve from all order data, flags if below 10%
- `MarginSweepJob.sweep()`: scheduled every 6h — for each active SKU, queries recent orders and cost data, computes margins, writes `MarginSnapshot`, then invokes `ShutdownRuleEngine.evaluate()`
- `ShutdownRuleEngine.evaluate(skuId, snapshots)`: checks all 4 `KillRule` conditions against snapshots:
  - Rule 1: net margin < 30% for 7+ consecutive snapshot days → publish `ShutdownRuleTriggered` with action=PAUSE
  - Rule 2: 30-day rolling refund rate > 5% → publish `ShutdownRuleTriggered` with action=PAUSE
  - Rule 3: 30-day rolling chargeback rate > 2% → publish `ShutdownRuleTriggered` with action=PAUSE_COMPLIANCE
  - Rule 4: CAC variance > 15% over 14 days → publish `PricingSignal.CacChanged`
- `SkuPnlReporter.report(skuId, fromDate, toDate)`: aggregates snapshots into a P&L report with revenue, cost breakdown, margin, reserve contribution

### Listener Layer
- `OrderEventListener`: `@EventListener` on `OrderFulfilled` — writes `CapitalOrderRecord`, calls `ReserveAccountService.creditFromOrder()`
- `ShutdownRuleListener` (in catalog module): `@EventListener` on `ShutdownRuleTriggered` — calls `SkuService.transition(skuId, SkuState.Paused)` or `SkuState.Terminated` based on action. Only pauses LISTED or SCALED SKUs (same guard as `VendorBreachListener`).

### Handler Layer
- `GET /api/capital/reserve` — current balance, target rate, health status
- `GET /api/skus/{id}/pnl?from=&to=` — P&L for period

### Shared Events (in `modules/shared`)
- `ShutdownRuleTriggered(skuId: SkuId, rule: String, conditionValue: String, action: String, occurredAt: Instant)` — implements `DomainEvent`
- `MarginSnapshotTaken(skuId: SkuId, netMargin: Percentage, grossMargin: Percentage, occurredAt: Instant)` — implements `DomainEvent`

## Task Breakdown

### Shared Events
- [ ] Add `ShutdownRuleTriggered` event to `modules/shared/src/.../events/`
- [ ] Add `MarginSnapshotTaken` event to `modules/shared/src/.../events/`

### Domain Layer
- [ ] Implement `ReserveAccount` JPA entity (balance, targetRateMin, targetRateMax, lastUpdatedAt)
- [ ] Implement `MarginSnapshot` JPA entity (skuId, snapshotDate, grossMargin, netMargin, revenue, totalCost, refundRate, chargebackRate, cacVariance)
- [ ] Implement `KillRule` sealed class with 4 variants (MarginBreach, RefundRateBreach, ChargebackRateBreach, CacInstability)
- [ ] Implement `CapitalRuleAudit` JPA entity (skuId, rule, conditionValue, action, firedAt)
- [ ] Implement `CapitalOrderRecord` JPA entity (orderId, skuId, totalAmount, currency, status, refunded, chargebacked, recordedAt)
- [ ] Implement `CapitalConfig` `@ConfigurationProperties` (netMarginFloor=30, sustainedDays=7, refundRateMax=5, chargebackRateMax=2, cacVarianceMax=15, cacVarianceDays=14, reserveRateMin=10, reserveRateMax=15)

### Domain Service
- [ ] Implement `ReserveAccountService.creditFromOrder(orderId, revenue)` — adds revenue * rate to reserve balance
- [ ] Implement `ReserveAccountService.getBalance()` — returns current balance and health status
- [ ] Implement `ReserveCalcJob` `@Scheduled(cron = "0 0 2 * * *")` (nightly 02:00) — reconcile reserve from all orders, flag if below minimum
- [ ] Implement `MarginSweepJob` `@Scheduled(fixedRate = 21_600_000)` (every 6h) — snapshot + rule evaluation
- [ ] Implement snapshot computation: aggregate recent orders per SKU → compute gross/net margin, refund rate, chargeback rate, CAC variance
- [ ] Implement `ShutdownRuleEngine.evaluate(skuId, snapshots)` checking all 4 KillRule conditions
- [ ] Rule 1: net margin < 30% for 7+ consecutive days → publish `ShutdownRuleTriggered` with action=PAUSE
- [ ] Rule 2: 30-day rolling refund rate > 5% → publish `ShutdownRuleTriggered` with action=PAUSE
- [ ] Rule 3: 30-day rolling chargeback rate > 2% → publish `ShutdownRuleTriggered` with action=PAUSE_COMPLIANCE
- [ ] Rule 4: CAC variance > 15% over 14 days → publish `PricingSignal.CacChanged`
- [ ] Log every rule firing to `capital_rule_audit` table (skuId, rule, conditionValue, action, timestamp)
- [ ] Implement `SkuPnlReporter.report(skuId, from, to)` aggregating snapshots into P&L report
- [ ] Publish `MarginSnapshotTaken` event after each snapshot write

### Listener Layer
- [ ] Implement `OrderEventListener` `@EventListener(OrderFulfilled::class)` — write `CapitalOrderRecord`, credit reserve
- [ ] Implement `ShutdownRuleListener` in catalog module `@EventListener(ShutdownRuleTriggered::class)` — pause/terminate SKU (LISTED/SCALED only)

### Handler Layer
- [ ] Implement `GET /api/capital/reserve` returning balance, target rate, health status
- [ ] Implement `GET /api/skus/{id}/pnl` with `from`/`to` query params
- [ ] Add `ReserveResponse`, `SkuPnlResponse` DTOs

### Persistence (Common Layer)
- [ ] Write `V13__capital.sql` migration (reserve_account, margin_snapshots, capital_rule_audit, capital_order_records tables)
- [ ] Implement `ReserveAccountRepository`
- [ ] Implement `MarginSnapshotRepository` with queries: findBySkuIdAndDateRange, findConsecutiveBelowThreshold
- [ ] Implement `CapitalRuleAuditRepository`
- [ ] Implement `CapitalOrderRecordRepository` with queries: findBySkuIdAndDateRange, countRefundedBySkuIdInWindow, countChargebackedBySkuIdInWindow

## Testing Strategy

- Unit test `ShutdownRuleEngine`: each KillRule independently — pass (no action) and fail (event emitted)
- Unit test: 6 consecutive below-30% days → no action; 7 days → `ShutdownRuleTriggered` with PAUSE
- Unit test: refund rate at 4.9% → no action; 5.1% → `ShutdownRuleTriggered` with PAUSE
- Unit test: chargeback rate at 1.9% → no action; 2.1% → `ShutdownRuleTriggered` with PAUSE_COMPLIANCE
- Unit test: CAC variance at 14% → no action; 16% → `PricingSignal.CacChanged` published
- Unit test `SkuPnlReporter`: known snapshots aggregate to correct P&L totals
- Unit test `ReserveAccountService`: credit adds correct percentage to balance; reconciliation corrects drift
- Unit test `OrderEventListener`: `OrderFulfilled` → `CapitalOrderRecord` created + reserve credited
- Integration test (Testcontainers): `MarginSweepJob` runs → snapshots written → rule engine evaluates → `ShutdownRuleTriggered` published → `ShutdownRuleListener` pauses SKU
- Integration test: refund rate breach triggers pause within one sweep cycle
- Integration test: `ReserveCalcJob` reconciles reserve balance from order records
- E2E test: `ShutdownRuleListener` only pauses LISTED/SCALED SKUs (ignores others)

### E2E / Integration Tests
- [ ] Integration test: margin degrading below 30% for 7 days triggers auto-pause via event chain
- [ ] Integration test: refund rate breach triggers pause within one monitoring cycle
- [ ] Integration test: chargeback rate breach triggers pause + compliance flag
- [ ] Integration test: reserve reconciliation corrects balance drift
- [ ] Unit tests for `ShutdownRuleEngine` (all 4 rules, boundary conditions)
- [ ] Unit tests for `ReserveAccountService` (credit, reconcile, health status)
- [ ] Unit tests for `SkuPnlReporter` (aggregation accuracy)
- [ ] Unit tests for `OrderEventListener` (event → record + credit)

## Rollout Plan

1. Add `ShutdownRuleTriggered` and `MarginSnapshotTaken` events to `modules/shared`
2. Write `V13__capital.sql` migration
3. Implement `ReserveAccount`, `MarginSnapshot`, `KillRule`, `CapitalOrderRecord`, `CapitalRuleAudit` entities
4. Implement `CapitalConfig` configuration properties
5. Implement `OrderEventListener` (receives fulfillment events, populates local order records)
6. Implement `ReserveAccountService` (credit + reconciliation)
7. Implement `ShutdownRuleEngine` with all 4 kill rules
8. Implement `MarginSweepJob` (6h) and `ReserveCalcJob` (nightly 02:00)
9. Implement `SkuPnlReporter`
10. Add `ShutdownRuleListener` in catalog module
11. Add REST handler + DTOs
