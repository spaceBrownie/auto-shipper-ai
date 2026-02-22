# FR-005: Catalog Stress Test

## Problem Statement

A verified cost envelope alone is insufficient to list a SKU. Every SKU must survive a simulated worst-case scenario before launch. The stress test is the final gate before a SKU becomes `Listed`. Failure results in immediate, irreversible termination — no override, no appeal. The type system must encode this: a `LaunchReadySku` can only be constructed after passing the stress test.

## Business Requirements

- Before listing, every SKU must pass a stress simulation with these exact multipliers:
  - Shipping cost: 2×
  - CAC: +15%
  - Supplier cost: +10%
  - Refund rate: 5% of revenue
  - Chargeback rate: 2% of revenue
- Pass condition: gross margin ≥ 50% AND protected net margin ≥ 30%
- Fail condition: SKU is immediately terminated with reason `MARGIN_BELOW_THRESHOLD` — no override permitted
- `LaunchReadySku` is a type-level proof of passing: it can only be constructed with a `StressTestedMargin` (which itself enforces the 30% floor in its constructor)
- `StressTestedMargin` is a `@JvmInline value class` with `init { require(value >= 30%) }` — construction fails at the threshold
- The `launch()` use case accepts only `LaunchReadySku` — structurally impossible to bypass

## Success Criteria

- `StressTestedMargin` value class enforces 30% minimum at construction
- `LaunchReadySku` data class requires `CostEnvelope.Verified` + `StressTestedMargin`
- `StressTestService` applies all 5 stress multipliers and calculates stressed margins
- On pass: constructs `LaunchReadySku`, transitions SKU to `Listed`, publishes `SkuLaunched` event
- On fail: terminates SKU with `MARGIN_BELOW_THRESHOLD`, publishes `SkuTerminated` event
- `POST /api/skus/{id}/stress-test` triggers the test
- Unit tests verify: passing at exactly 30%, failing at 29.9%, all 5 stress variables applied correctly
- Integration test: full flow from `CostGating` → stress test pass → `Listed`

## Non-Functional Requirements

- Stress test results persisted to `sku_stress_test_results` table with all intermediate margin calculations
- Stress test is idempotent — re-running on an already-terminated SKU returns the existing result
- Stress parameters (2×, 15%, 10%, 5%, 2%) must be configurable via `application.yml` for future adjustment

## Dependencies

- FR-001 (shared-domain-primitives) — `Money`, `Percentage`
- FR-003 (catalog-sku-lifecycle) — SKU must be in `StressTesting` state; transitions to `Listed` or `Terminated`
- FR-004 (catalog-cost-gate) — `CostEnvelope.Verified` required as input
