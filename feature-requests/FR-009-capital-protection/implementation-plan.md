> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/capital/src/...` instead of `capital/src/...`.

# FR-009: Capital Protection — Implementation Plan

## Technical Design

The `capital` module maintains a logical rolling reserve, computes SKU-level P&L from order and cost data, and runs a `ShutdownRuleEngine` that evaluates the 4 automated shutdown conditions on a schedule. All rule firings are immutably logged.

```
capital/src/main/kotlin/com/autoshipper/capital/
├── domain/
│   ├── ReserveAccount.kt          (logical ledger entity)
│   └── SkuMarginSnapshot.kt       (daily margin observation)
├── domain/service/
│   ├── ReserveAccountService.kt   (maintains 10–15% reserve)
│   ├── SkuMarginMonitor.kt        (@Scheduled daily)
│   ├── ShutdownRuleEngine.kt      (evaluates 4 shutdown conditions)
│   └── SkuPnlReporter.kt          (P&L query builder)
├── handler/
│   └── CapitalController.kt
└── config/
    └── CapitalConfig.kt           (@ConfigurationProperties — thresholds)
```

## Architecture Decisions

- **Logical ledger in Phase 1**: The rolling reserve is a `ReserveAccount` entity tracking a balance. No external bank escrow in Phase 1. Real escrow integration is a Phase 2 decision.
- **`SkuMarginSnapshot` as time-series**: Daily margin observations enable 90-day rolling calculations without reprocessing raw order data on every query.
- **`ShutdownRuleEngine` is stateless and event-emitting**: It reads snapshots, evaluates conditions, and emits `SkuStateChanged` events. It does not directly mutate SKU state — that's the catalog module's responsibility.
- **All thresholds in `@ConfigurationProperties`**: The 30%, 7-day, 5%, 2%, 15%, 14-day values are configurable — not hardcoded.
- **Rule audit log is append-only**: `capital_rule_audit` is never updated, only inserted. This provides a tamper-evident record of every automated decision.

## Layer-by-Layer Implementation

### Domain Layer
- `ReserveAccount`: current balance, target rate (10–15%), last updated
- `SkuMarginSnapshot`: skuId, date, grossMargin, netMargin, revenue, totalCost, refundRate, chargebackRate, cacVariance

### Domain Service
- `ReserveAccountService.credit(orderId, revenue)`: adds `revenue × reserveRate` to balance on order
- `SkuMarginMonitor.takeSnapshot()`: scheduled daily — queries orders for yesterday, computes margins, writes `SkuMarginSnapshot`
- `ShutdownRuleEngine.evaluate()`: for each SKU, checks all 4 conditions against the last N snapshots. Emits appropriate events.
- `SkuPnlReporter.report(skuId, fromDate, toDate)`: aggregates snapshots into a P&L report

### Handler Layer
- `GET /api/capital/reserve` — current balance and rate
- `GET /api/skus/{id}/pnl?from=&to=` — P&L for period

## Task Breakdown

### Domain Layer
- [ ] Implement `ReserveAccount` JPA entity (balance, targetRateMin, targetRateMax, lastUpdated)
- [ ] Implement `SkuMarginSnapshot` JPA entity (skuId, date, all margin components)
- [ ] Implement `CapitalConfig` `@ConfigurationProperties` (all thresholds: netMarginFloor=30, sustainedDays=7, refundRateMax=5, chargebackRateMax=2, cacVarianceMax=15, cacVarianceDays=14)

### Domain Service
- [ ] Implement `ReserveAccountService.credit(revenue)` — adds revenue × rate to reserve balance
- [ ] Implement `ReserveAccountService.getBalance()` — returns current balance and health status
- [ ] Implement `SkuMarginMonitor` `@Scheduled(cron = "0 0 0 * * *")` (midnight daily)
- [ ] Implement snapshot computation: aggregate yesterday's orders per SKU → compute gross/net margin, refund rate, chargeback rate
- [ ] Implement `ShutdownRuleEngine.evaluate()` checking all 4 conditions per SKU
- [ ] Rule 1: net margin < 30% for 7+ consecutive days → emit SKU pause event
- [ ] Rule 2: 30-day rolling refund rate > 5% → emit SKU pause event
- [ ] Rule 3: 30-day rolling chargeback rate > 2% → emit SKU pause + compliance flag event
- [ ] Rule 4: CAC variance > 15% over 14 days → publish `PricingSignal.CacChanged`
- [ ] Log every rule firing to `capital_rule_audit` table (skuId, rule, condition value, action, timestamp)
- [ ] Implement `SkuPnlReporter.report(skuId, from, to)` aggregating snapshots

### Handler Layer
- [ ] Implement `GET /api/capital/reserve` returning balance and reserve rate
- [ ] Implement `GET /api/skus/{id}/pnl` with `from`/`to` query params
- [ ] Add `ReserveResponse`, `SkuPnlResponse` DTOs

### Persistence (Common Layer)
- [ ] Write `V8__capital.sql` migration (reserve_account, sku_margin_snapshots, capital_rule_audit tables)
- [ ] Implement `ReserveAccountRepository`, `SkuMarginSnapshotRepository`, `CapitalRuleAuditRepository`

## Testing Strategy

- Unit test `ShutdownRuleEngine`: each rule independently — pass (no action) and fail (event emitted)
- Unit test: 6 consecutive below-30% days → no action; 7 days → pause event
- Unit test `SkuPnlReporter`: known snapshots aggregate to correct P&L totals
- Unit test `ReserveAccountService`: credit adds correct percentage to balance
- Integration test (Testcontainers): snapshot job runs → rule engine evaluates → SKU auto-paused

## Rollout Plan

1. Write `V8__capital.sql`
2. Implement `ReserveAccount` and `SkuMarginSnapshot` entities
3. Implement `SkuMarginMonitor` snapshot job
4. Implement `ShutdownRuleEngine` with all 4 rules
5. Implement `SkuPnlReporter`
6. Add REST handler
