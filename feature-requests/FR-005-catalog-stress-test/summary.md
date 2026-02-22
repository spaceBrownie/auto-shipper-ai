# FR-005: Catalog Stress Test — Summary

## Feature Summary

Implemented the stress test gate that must pass before any SKU can reach `Listed` state. `StressTestService.run()` applies 5 configurable stress multipliers to a `CostEnvelope.Verified`, calculates stressed gross and net margins, and either constructs a `LaunchReadySku` (pass) or terminates the SKU (fail). The 30% net margin floor is enforced structurally by `StressTestedMargin` — it is a compile-time invariant that `LaunchReadySku` cannot exist without passing the threshold.

## Changes Made

- **Domain Layer**: `StressTestedMargin` (`@JvmInline value class`) with 30% floor in `init`; `LaunchReadySku` (launch token); `StressTestResult` (audit record); `StressTestFailedException`; `StressTestConfig` (`@ConfigurationProperties`).
- **Domain Service**: `StressTestService.run(skuId, estimatedPrice)` applies 5 multipliers (2× shipping, +15% CAC, +10% supplier, 5% refund, 2% chargeback), computes stressed total cost, checks both gross (50%) and net (30%) floors, transitions SKU `StressTesting → Listed` or `→ Terminated(STRESS_TEST_FAILED)`, publishes `SkuTerminated` on fail.
- **Persistence**: `StressTestResultEntity` JPA entity, `StressTestResultRepository`.
- **Handler**: `StressTestController` — `POST /api/skus/{id}/stress-test`, returns 200 on pass / 422 on fail.
- **Config**: `StressTestConfigProperties` registers `@ConfigurationProperties`.
- **Migration**: `V4__stress_test_results.sql`.
- **application.yml**: `stress-test:` config block with all 7 parameters.

## Files Modified

| File | Description |
|---|---|
| `catalog/…/domain/StressTestedMargin.kt` | @JvmInline value class with 30% floor |
| `catalog/…/domain/LaunchReadySku.kt` | Domain launch token |
| `catalog/…/domain/StressTestResult.kt` | Audit data class |
| `catalog/…/domain/StressTestFailedException.kt` | Fail exception |
| `catalog/…/domain/StressTestConfig.kt` | @ConfigurationProperties |
| `catalog/…/domain/service/StressTestService.kt` | Orchestration service |
| `catalog/…/persistence/StressTestResultEntity.kt` | JPA entity |
| `catalog/…/persistence/StressTestResultRepository.kt` | JPA repository |
| `catalog/…/handler/StressTestController.kt` | REST controller |
| `catalog/…/handler/dto/StressTestRequest.kt` | Request DTO |
| `catalog/…/handler/dto/StressTestResponse.kt` | Response DTO |
| `catalog/…/config/StressTestConfigProperties.kt` | Config registration |
| `app/…/db/migration/V4__stress_test_results.sql` | DB migration |
| `app/…/application.yml` | stress-test config block |

## Testing Completed

- `StressTestedMarginTest`: exactly 30% passes; 29.99% throws; value preserved.
- `StressTestServiceTest`: happy path (73.65% margin → LaunchReadySku); fail path (low price → StressTestFailedException + SkuTerminated event); missing envelope → IllegalStateException.
- **14 catalog tests pass** — `BUILD SUCCESSFUL`.

## Deployment Notes

- All stress multipliers are configurable via `application.yml` — no code change required for threshold adjustments.
- Flyway runs `V4__stress_test_results.sql` automatically on startup.
- `TerminationReason.STRESS_TEST_FAILED` is the reason code for stress-test-induced terminations.
