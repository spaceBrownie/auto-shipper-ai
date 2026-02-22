# FR-005: Catalog Stress Test — Implementation Plan

## Technical Design

`StressTestService` applies all 5 stress multipliers to a `CostEnvelope.Verified` and calculates stressed gross and net margins. If both thresholds pass, it constructs a `LaunchReadySku`. The `StressTestedMargin` value class makes the threshold invariant self-enforcing at construction time.

```
catalog/src/main/kotlin/com/autoshipper/catalog/
├── domain/
│   ├── StressTestedMargin.kt    (@JvmInline value class)
│   ├── LaunchReadySku.kt        (data class — proof of passing)
│   └── StressTestResult.kt      (intermediate calculation record)
├── domain/service/
│   └── StressTestService.kt     (applies multipliers, constructs LaunchReadySku or terminates)
└── handler/
    └── StressTestController.kt  (POST /api/skus/{id}/stress-test)
```

## Architecture Decisions

- **`StressTestedMargin` value class with `init` guard**: The 30% floor is encoded in the type. It is impossible to construct a `LaunchReadySku` without passing the threshold — no boolean flag or convention needed.
- **`LaunchReadySku` is the domain's launch token**: The `launch()` use case (which transitions SKU to `Listed`) accepts only `LaunchReadySku`. This makes bypassing the stress test a compile-time error.
- **Stress parameters in `application.yml`**: The 2×, 15%, 10%, 5%, 2% multipliers are configurable via `@ConfigurationProperties`. Changing them doesn't require code changes.
- **Fail path terminates immediately**: No retry, no recalculation. Termination is written to DB and event published before the response is returned.

## Layer-by-Layer Implementation

### Domain Layer
- `StressTestedMargin`: `@JvmInline value class(val value: Percentage)` with `init { require(value >= Percentage(30)) }`.
- `LaunchReadySku`: `data class(val sku: Sku, val envelope: CostEnvelope.Verified, val stressTestedMargin: StressTestedMargin)`.
- `StressTestResult`: intermediate record storing all stressed cost values and calculated margins (for persistence/audit).

### Domain Service
- `StressTestService.run(skuId): LaunchReadySku` (pass) or throws `StressTestFailedException` and terminates SKU.
- Applies multipliers to `CostEnvelope.Verified`, calculates stressed gross margin and net margin.
- Constructs `StressTestedMargin` — if below 30%, construction throws, which `StressTestService` catches to emit termination.
- On pass: constructs `LaunchReadySku`, transitions SKU → `Listed`, publishes `SkuLaunched`.
- On fail: transitions SKU → `Terminated(MARGIN_BELOW_THRESHOLD)`, publishes `SkuTerminated`.

### Handler Layer
- `POST /api/skus/{id}/stress-test`: triggers test, returns pass/fail with margin details.

## Task Breakdown

### Domain Layer
- [ ] Implement `StressTestedMargin` `@JvmInline value class` with 30% floor enforcement in `init`
- [ ] Implement `LaunchReadySku` data class requiring `CostEnvelope.Verified` + `StressTestedMargin`
- [ ] Implement `StressTestResult` record (all stressed components, gross margin, net margin, passed, multipliers used)
- [ ] Define `StressTestFailedException`
- [ ] Define `StressTestConfig` `@ConfigurationProperties` class (shippingMultiplier, cacIncrease, supplierIncrease, refundRate, chargebackRate, grossMarginFloor, netMarginFloor)

### Domain Service
- [ ] Implement `StressTestService.run(skuId)` applying all 5 multipliers to verified envelope
- [ ] Calculate stressed gross margin: `(price - stressedCost) / price`
- [ ] Calculate stressed net margin: after all stress variables applied
- [ ] On pass: construct `StressTestedMargin`, construct `LaunchReadySku`, transition SKU to `Listed`
- [ ] On pass: publish `SkuLaunched` event
- [ ] On fail: transition SKU to `Terminated(MARGIN_BELOW_THRESHOLD)`
- [ ] On fail: publish `SkuTerminated` event
- [ ] Persist `StressTestResult` to `sku_stress_test_results` table

### Handler Layer
- [ ] Implement `StressTestController` with `POST /api/skus/{id}/stress-test`
- [ ] Return `StressTestResponse` with passed boolean, gross margin, net margin, stressed cost breakdown

### Persistence (Common Layer)
- [ ] Write `V4__stress_test_results.sql` migration
- [ ] Implement `StressTestResultRepository`

## Testing Strategy

- Unit test: `StressTestedMargin` with exactly 30% passes, 29.99% throws
- Unit test: `StressTestService` with all 5 multipliers applied to known input produces expected margins
- Unit test: pass path → `LaunchReadySku` constructed with correct values
- Unit test: fail path → `SkuTerminated` event published, no `LaunchReadySku` constructed
- Integration test (Testcontainers): full flow `CostGating` → cost verified → stress test pass → SKU in `Listed` state
- Integration test: stress test fail → SKU in `Terminated` state with `MARGIN_BELOW_THRESHOLD`

## Rollout Plan

1. Write `V4__stress_test_results.sql`
2. Implement `StressTestedMargin` and `LaunchReadySku` (type-level gate first)
3. Implement `StressTestService` with configurable parameters
4. Add REST handler
5. Wire to SKU lifecycle — `launch()` use case accepts `LaunchReadySku` only
