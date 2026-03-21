# FR-022: Integration Test Coverage Gaps — Summary

## Feature Summary

Closed all integration test coverage gaps identified across 7 postmortems (PM-001, PM-002, PM-005, PM-006, PM-009, PM-011). Added 13 new integration tests across 5 modules, 3 REST endpoints for order state transitions, and updated the E2E test playbook with compliance re-check and order lifecycle scenarios.

## Changes Made

### Production Code
- **3 new REST endpoints** on `OrderController` for order state transitions (`POST /confirm`, `/ship`, `/deliver`), enabling full E2E testing of the `OrderFulfilled` → `OrderEventListener` AFTER_COMMIT chain via HTTP
- **1 new DTO** (`ShipOrderRequest`) for the ship endpoint

### Integration Tests (modules/app/)
- **PricingInitializerIntegrationTest** (2 tests) — validates SKU→LISTED triggers `PricingInitializer` AFTER_COMMIT listener and persists `SkuPriceEntity` with exact price/margin/cost values; includes idempotency test (PM-001)
- **MarginSweepFailureIntegrationTest** (2 tests) — validates multi-SKU sweep with REQUIRES_NEW isolation: breaching SKU gets auto-paused while healthy SKU is unaffected; both get snapshots (PM-005)
- **DemandScanJobIntegrationTest** (3 tests) — validates failure status persists after mid-pipeline exception (PM-011); validates JSONB round-trip for `DemandCandidate.demandSignals` and `CandidateRejection.metadata` columns (PM-011)
- **VendorSlaMonitorIntegrationTest** (2 tests) — validates breach detection with real DB records end-to-end: vendor suspended, breach logged, linked SKU auto-paused via event chain; healthy vendor remains active (PM-002)
- **OrderTransitionIntegrationTest** (4 tests) — validates full order lifecycle via REST (create→confirm→ship→deliver) with `OrderFulfilled` event chain verification; validates invalid transition error handling (PM-006)

### Unit Tests
- **ReserveCalcJobTest** (1 test) — validates reserve percentage uses only non-refunded revenue as denominator: 9×$100 non-refunded + 1×$100 refunded at 10% rate = exactly $90.00 (PM-006)

### E2E Playbook Updates
- **Phase 2.3** replaced direct SQL order advancement with REST calls (`/confirm`, `/ship`, `/deliver`)
- **Phase 3.1** updated to remove manual `capital_order_records` insert (now handled by `OrderEventListener`)
- **Section 1.2d** added: compliance re-check scenario (fail→new SKU→pass→verify no cross-SKU contamination) (PM-009)
- **Known Gaps** table: removed "No REST endpoints for order state transitions" row; added 3 new endpoints to Quick Reference

### CI Configuration
- Added 5 new test count gates to `.github/workflows/ci.yml` for the new integration test classes

## Files Modified

| File | Change |
|---|---|
| `modules/fulfillment/src/main/kotlin/.../handler/OrderController.kt` | Added 3 POST endpoints (confirm, ship, deliver) |
| `modules/fulfillment/src/main/kotlin/.../handler/dto/ShipOrderRequest.kt` | New DTO for ship endpoint |
| `modules/fulfillment/src/test/kotlin/.../handler/OrderControllerTest.kt` | Added 5 controller unit tests |
| `modules/capital/src/test/kotlin/.../service/ReserveCalcJobTest.kt` | New unit test for refund exclusion |
| `modules/app/src/test/kotlin/.../pricing/PricingInitializerIntegrationTest.kt` | New integration test (2 tests) |
| `modules/app/src/test/kotlin/.../capital/MarginSweepFailureIntegrationTest.kt` | New integration test (2 tests) |
| `modules/app/src/test/kotlin/.../portfolio/DemandScanJobIntegrationTest.kt` | New integration test (3 tests) |
| `modules/app/src/test/kotlin/.../vendor/VendorSlaMonitorIntegrationTest.kt` | New integration test (2 tests) |
| `modules/app/src/test/kotlin/.../fulfillment/OrderTransitionIntegrationTest.kt` | New integration test (4 tests) |
| `modules/app/build.gradle.kts` | Added mockito-kotlin test dependency |
| `docs/e2e-test-playbook.md` | Updated order transitions, added compliance re-check |
| `.github/workflows/ci.yml` | Added 5 new test count gates |
| `feature-requests/FR-022-integration-test-coverage-gaps/implementation-plan.md` | All checkboxes marked complete |

## Testing Completed

- **19 new tests total**: 13 integration tests + 5 controller unit tests + 1 unit test
- `./gradlew build` passes with all new and existing tests
- No existing tests modified or broken (additive only)
- All integration tests use `@AfterEach` cleanup (not `@Transactional`) to prevent AFTER_COMMIT false positives
- JSONB tests use `TransactionTemplate` for flush operations, read back outside tx to verify persistence
- OrderTransition tests mock `InventoryChecker` to avoid Shopify API calls in test profile

## Deployment Notes

- No database migrations required — all tests use existing tables
- Three new public REST endpoints are additive (no existing endpoint behavior changed)
- The `mockito-kotlin` dependency was added to the app module for integration test mocking support
- CI pipeline will enforce new integration test count gates on the next PR

## Postmortem Coverage Map

| PM | Test Gap | Test File | Status |
|---|---|---|---|
| PM-001 | PricingInitializer silent persistence failure | `PricingInitializerIntegrationTest` | Covered |
| PM-002 | VendorSlaMonitor circular data dependencies | `VendorSlaMonitorIntegrationTest` | Covered |
| PM-005 | Multi-SKU margin sweep failure isolation | `MarginSweepFailureIntegrationTest` | Covered |
| PM-006 | ReserveCalcJob refund denominator | `ReserveCalcJobTest` | Covered |
| PM-006 | Order state transition REST endpoints | `OrderTransitionIntegrationTest` | Covered |
| PM-009 | Compliance re-check contamination | E2E playbook section 1.2d | Covered |
| PM-011 | DemandScanJob FAILED status persistence | `DemandScanJobIntegrationTest` | Covered |
| PM-011 | JSONB persistence round-trip | `DemandScanJobIntegrationTest` | Covered |
