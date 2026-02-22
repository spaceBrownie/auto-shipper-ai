# FR-009: Capital Protection

## Problem Statement

The commerce engine must protect capital automatically. Without systematic margin monitoring and automated shutdown rules, a degrading SKU can silently erode net profit over weeks. A rolling reserve must be maintained to absorb refund and chargeback shocks, and every shutdown trigger must fire without any human intervention.

## Business Requirements

- A rolling reserve of 10–15% of revenue must be maintained at all times (logical ledger in Phase 1)
- 90-day rolling margin must be tracked per SKU with real-time visibility
- SKU-level P&L dashboards must reflect current state: revenue, cost breakdown, margin, reserve contribution
- The following automated shutdown rules must fire without manual intervention:
  - Net margin below 30% sustained for 7+ consecutive days → pause → review → terminate
  - Refund rate > 5% on a rolling 30-day window → pause
  - Chargeback rate > 2% on a rolling 30-day window → pause + compliance review flag
  - CAC instability > 15% variance over 14 days → trigger pricing engine re-run
- No SKU may continue operating at sustained negative net margin

## Success Criteria

- `ReserveAccount` entity tracks rolling reserve balance (10–15% of revenue, configurable)
- `SkuMarginMonitor` scheduled job checks 90-day rolling margin per SKU daily
- `ShutdownRuleEngine` evaluates all 4 shutdown conditions and emits appropriate events
- `SkuPnlReport` aggregates revenue, cost components, margin, reserve for any SKU and period
- `GET /api/capital/reserve` returns current reserve balance and contribution rate
- `GET /api/skus/{id}/pnl` returns SKU-level P&L for a given period
- Integration test: SKU with margin degrading below 30% for 7 days triggers auto-pause
- Integration test: refund rate breach triggers pause within one monitoring cycle

## Non-Functional Requirements

- Margin monitoring runs daily at midnight via `@Scheduled`
- All rule firings logged to `capital_rule_audit` table with timestamp, condition, and action taken
- Reserve balance updated in real time on every order (not batch)
- P&L data retained for minimum 2 years

## Dependencies

- FR-001 (shared-domain-primitives) — `Money`, `Percentage`, `SkuId`, `SkuTerminated`
- FR-002 (project-bootstrap) — Spring Boot, `@Scheduled`
- FR-003 (catalog-sku-lifecycle) — SKU state transitions (pause, terminate)
- FR-008 (fulfillment-orchestration) — order data feeds refund/chargeback rate calculations
