# FR-022: Integration Test Coverage Gaps — Implementation Plan

## Technical Design

### Architecture Overview

All integration tests live in `modules/app/src/test/kotlin/` because they require the full Spring context with all modules wired together. This matches the existing pattern used by `CapitalIntegrationTest`, `MarginSweepIntegrationTest`, `VendorBreachIntegrationTest`, and `VendorEndpointIntegrationTest`.

```
modules/app/src/test/kotlin/com/autoshipper/
    pricing/
        PricingInitializerIntegrationTest.kt    (BR-1.1)
    capital/
        CapitalIntegrationTest.kt               (existing — 6 tests)
        MarginSweepIntegrationTest.kt           (existing — 4 tests)
        MarginSweepFailureIntegrationTest.kt    (BR-1.2)
        ReserveCalcJobTest.kt                   (BR-3.2 — unit test, lives in capital module)
    portfolio/
        DemandScanJobIntegrationTest.kt         (BR-2.1, BR-4)
    vendor/
        VendorSlaMonitorIntegrationTest.kt      (BR-3.1)
        VendorBreachIntegrationTest.kt          (existing — 4 tests)
        VendorEndpointIntegrationTest.kt        (existing — 8 tests)
    fulfillment/
        OrderTransitionIntegrationTest.kt       (BR-5.1, new endpoint tests)
```

New unit tests that do not require a database stay in their respective module's `src/test/kotlin/` directory:
- `modules/capital/src/test/kotlin/.../ReserveCalcJobTest.kt` (BR-3.2)

New production code (REST endpoints) lives in the fulfillment module:
- `modules/fulfillment/src/main/kotlin/.../handler/OrderController.kt` (extended with 3 new endpoints)
- `modules/fulfillment/src/main/kotlin/.../handler/dto/ShipOrderRequest.kt` (new DTO)

### Test Infrastructure

- **Database:** Running PostgreSQL instance via `application-test.yml` (no Testcontainers — banned by ArchUnit Rule 2, per RAT-18)
- **Spring context:** `@SpringBootTest` + `@ActiveProfiles("test")` for full context
- **Cleanup:** `@AfterEach` with `TRUNCATE TABLE ... CASCADE` (no `@Transactional` on test class — would prevent `AFTER_COMMIT` listeners from firing)
- **Event publication:** `TransactionTemplate.execute {}` for tests that need `AFTER_COMMIT` listeners to fire
- **Assertions:** Exact `BigDecimal` values via `assertEquals` with compareTo, never `any<Money>()` matchers

### Cross-Module Testing Strategy

Integration tests that exercise cross-module event chains (e.g., `SkuStateChanged` -> `PricingInitializer`) must:
1. Use `@SpringBootTest` so all modules are wired
2. Set up prerequisite data in the database directly (e.g., create a SKU, cost envelope, stress test result)
3. Trigger the event by calling the service method that publishes it (which commits the transaction)
4. Assert on database state in the consuming module (e.g., check `sku_prices` table)

This is the same pattern used by `CapitalIntegrationTest` (evaluates shutdown rules -> checks SKU state) and `VendorBreachIntegrationTest` (publishes `VendorSlaBreached` -> checks SKU state).

## Architecture Decisions

### Decision 1: Integration tests in `modules/app/` only

**Choice:** All `@SpringBootTest` integration tests live in `modules/app/src/test/kotlin/`.

**Why:** The app module has `@SpringBootApplication`, depends on all other modules, and has the `application-test.yml` configuration. Individual modules (pricing, capital, etc.) cannot boot a Spring context on their own — they lack the application class, Flyway migrations, and datasource configuration.

**Alternative rejected:** Adding `@SpringBootTest` infrastructure to each module. This would require duplicating `application-test.yml`, adding the PostgreSQL driver dependency, and creating a test application class per module. It adds complexity without benefit since all integration tests need the full context anyway.

### Decision 2: `@AfterEach` cleanup instead of `@Transactional` rollback

**Choice:** Every integration test class uses `@AfterEach` with `TRUNCATE TABLE ... CASCADE`.

**Why:** `@Transactional` on the test class prevents `AFTER_COMMIT` listeners from firing (the transaction never commits). This was the exact bug in `VendorBreachIntegrationTest` caught by PM-006 and fixed in RAT-18. Tests that rely on event-driven state changes would produce false positives.

**Trade-off:** Slightly slower tests due to explicit truncation. Acceptable since integration tests run against a dedicated test database.

### Decision 3: REST endpoints for order transitions

**Choice:** Add `POST /api/orders/{id}/confirm`, `/ship`, `/deliver` endpoints to `OrderController`.

**Why:** The E2E playbook currently advances orders via direct SQL, bypassing `OrderService` methods and the `OrderFulfilled` event chain. REST endpoints enable E2E testing of the full `markDelivered` -> `OrderFulfilled` -> `OrderEventListener` -> reserve credit pipeline.

**Alternative rejected:** Internal-only test endpoints (e.g., `@Profile("test")` controllers). These would not be available in the E2E manual testing scenario, which is the primary use case from PM-006.

### Decision 4: `ReserveCalcJob` test as unit test, not integration test

**Choice:** The reserve percentage calculation test (BR-3.2) is a unit test with real objects, not a `@SpringBootTest`.

**Why:** `ReserveCalcJob.reconcile()` reads from `orderRecordRepository.findAll()` and writes to `reserveAccountRepository`. The business logic under test is the calculation — whether refunded orders are excluded from the denominator. This can be tested with in-memory data objects and real repository mocks without needing a full Spring context. The existing `CapitalIntegrationTest.reserve reconciliation corrects balance drift` already covers the persistence path.

## Layer-by-Layer Implementation

### 1. Pricing Module — PricingInitializer Integration Test (BR-1.1)

**File:** `modules/app/src/test/kotlin/com/autoshipper/pricing/PricingInitializerIntegrationTest.kt`

**What it tests:** When a SKU transitions to `LISTED`, the `PricingInitializer` listener fires (via `SkuStateChanged` event), reads the stress test result and cost envelope, computes margin, and calls `PricingEngine.setInitialPrice()`, which persists a `SkuPriceEntity`.

**Setup:**
1. Create a SKU in `IDEATION` state
2. Insert a `CostEnvelopeEntity` for the SKU (with known cost values)
3. Insert a `StressTestResultEntity` for the SKU (with known price and stressed cost)
4. Transition the SKU through the state machine to `LISTED` using `SkuService.transition()`

The `SkuService.transition()` method is `@Transactional` — it commits and publishes `SkuStateChanged`. The `PricingInitializer` listener fires `AFTER_COMMIT` with `REQUIRES_NEW`.

**Assertions:**
- `skuPriceRepository.findBySkuId(skuId)` returns non-null
- `currentPriceAmount` matches the stress test result's `estimatedPriceAmount`
- `currentMarginPercent` matches the expected margin calculation: `(price - stressedCost) / price * 100`
- `currency` matches the stress test result's currency
- All assertions use exact `BigDecimal.compareTo()`, not approximate matchers

**Key constraint:** Must NOT use `@Transactional` on the test class (would prevent the `AFTER_COMMIT` listener from firing).

**Cleanup:** `@AfterEach` truncates `sku_prices`, `sku_pricing_history`, `sku_stress_test_results`, `sku_cost_envelopes`, `sku_state_history`, `skus`.

### 2. Capital Module — MarginSweep Failure-Path Integration Test (BR-1.2)

**File:** `modules/app/src/test/kotlin/com/autoshipper/capital/MarginSweepFailureIntegrationTest.kt`

**What it tests:** When `MarginSweepJob` processes multiple SKUs and one SKU triggers `ShutdownRuleTriggered` for a SKU whose transition fails (e.g., already PAUSED), the `MarginSnapshot` for that SKU is still persisted and all remaining SKUs are still processed.

**Setup:**
1. Create two LISTED SKUs (SKU-A and SKU-B)
2. Pause SKU-A (transition to PAUSED) — so when `ShutdownRuleListener` tries to pause it again, the transition will be a no-op or throw
3. Insert order records for both SKUs, with SKU-A having a refund rate > 5% (to trigger shutdown rule)
4. Insert order records for SKU-B with healthy data
5. Run `marginSweepJob.sweep(today)`

**Assertions:**
- SKU-A's `MarginSnapshot` exists in the database (the processor's `REQUIRES_NEW` transaction committed before the listener fired)
- SKU-B's `MarginSnapshot` exists in the database (processor isolation — one SKU's failure doesn't affect others)
- SKU-A's `CapitalRuleAudit` record exists with rule `REFUND_RATE_BREACH`
- SKU-B is still in `LISTED` state (unaffected by SKU-A's failure)

**Key constraint:** This validates the PM-005 fix — `ShutdownRuleListener` uses `AFTER_COMMIT` + `REQUIRES_NEW`, so even if `skuService.transition()` fails for SKU-A, the snapshot is already committed in the processor's independent transaction.

### 3. Capital Module — ReserveCalcJob Refund Exclusion Unit Test (BR-3.2)

**File:** `modules/capital/src/test/kotlin/com/autoshipper/capital/domain/service/ReserveCalcJobTest.kt`

**What it tests:** The reserve percentage calculation uses only non-refunded revenue as the denominator.

**Setup (with mocks):**
1. Create 10 `CapitalOrderRecord` objects: 9 non-refunded at $100 each, 1 refunded at $100
2. Create a `ReserveAccount` with an intentionally wrong balance
3. Configure `capitalConfig.reserveRateMinPercent = 10`
4. Mock `orderRecordRepository.findAll()` to return all 10 records
5. Mock `reserveAccountRepository.findAll()` to return the account

**Assertions:**
- Expected balance: 9 * $100 * 0.10 = $90.00 (not $100.00 — the refunded order is excluded)
- `reserveAccountRepository.save()` is called with a `ReserveAccount` whose `balanceAmount` equals `BigDecimal("90.0000")`
- Assert exact BigDecimal values, not approximate

**Why unit test:** The calculation logic is the bug surface area. `CapitalIntegrationTest` already tests the persistence path for reserve reconciliation.

### 4. Portfolio Module — DemandScanJob Transaction Boundary + JSONB Test (BR-2.1, BR-4)

**File:** `modules/app/src/test/kotlin/com/autoshipper/portfolio/DemandScanJobIntegrationTest.kt`

**Test 4a: Failure status persists (BR-2.1)**

**What it tests:** When `DemandScanJob.run()` fails mid-pipeline, the `DemandScanRun` row has `status = 'FAILED'` in the database. The existing unit test (`DemandScanJobTest.scan run failure status is persisted when exception occurs`) uses mocks — this integration test verifies the actual database persistence.

**Challenge:** `DemandScanJob` is NOT `@Transactional` itself (per CLAUDE.md constraint #9 — orchestrators should not be transactional). Each `scanRunRepository.save()` call commits independently. This means the failure path `scanRun.status = "FAILED"; scanRunRepository.save(scanRun)` should persist without needing a transaction wrapper. However, the test needs a way to make the scoring service throw.

**Approach:** Use `@MockBean` to replace `CandidateScoringService` with one that throws, while letting all other beans (repositories, providers) be real Spring beans. The `DemandScanJob` is `@Autowired` from the context.

**Setup:**
1. `@MockBean` on `CandidateScoringService` — configure to throw `RuntimeException`
2. `@MockBean` on `DemandSignalProvider` list — configure to return one valid `RawCandidate`
3. `@MockBean` on `CandidateDeduplicationService` — pass through
4. Run `demandScanJob.run()` — catch the expected exception

**Assertions:**
- Query `DemandScanRunRepository` for all runs
- The most recent run has `status = "FAILED"` in the database (not in memory)
- `completedAt` is non-null

**Test 4b: JSONB round-trip persistence (BR-4)**

**What it tests:** `DemandCandidate.demandSignals` (JSONB column annotated with `@JdbcTypeCode(SqlTypes.JSON)`) survives a full save-and-read cycle through JPA to PostgreSQL.

**Setup:**
1. Create a `DemandScanRun` and save it (needed for FK reference)
2. Create a `DemandCandidate` with `demandSignals = '{"youtube_views":"150000","reddit_mentions":"42","google_trend_score":"85"}'`
3. Save via `demandCandidateRepository.save()`
4. Flush and clear the persistence context (`entityManager.flush(); entityManager.clear()`)
5. Read back via `demandCandidateRepository.findById()`

**Assertions:**
- The returned entity is non-null
- `demandSignals` is not null
- Parse the JSON and assert the exact key-value pairs (`youtube_views=150000`, etc.)
- This catches the PM-011 Bug 1 pattern: `columnDefinition = "jsonb"` without `@JdbcTypeCode(SqlTypes.JSON)` would fail here

**Test 4c: CandidateRejection JSONB round-trip**

Same pattern as 4b but for `CandidateRejection.metadata` JSONB column.

### 5. Vendor Module — VendorSlaMonitor Integration Test (BR-3.1)

**File:** `modules/app/src/test/kotlin/com/autoshipper/vendor/VendorSlaMonitorIntegrationTest.kt`

**What it tests:** `VendorSlaMonitor.runCheck()` with real database records — insert fulfillment records (orders), run the monitor, assert breach detection and vendor suspension work end-to-end without mocked data providers.

**Setup:**
1. Create an `ACTIVE` vendor with completed checklist
2. Create a LISTED SKU and link it to the vendor via `VendorSkuAssignment`
3. Insert orders into the `orders` table with the vendor's ID:
   - 100 DELIVERED orders (total fulfillments)
   - 15 of those with `delayDetected = true` or `status = REFUNDED` (violations)
   - All orders have `createdAt` within the 30-day rolling window
4. Run `vendorSlaMonitor.runCheck(BigDecimal("10.00"))`

**Assertions:**
- Vendor status is `SUSPENDED` (reloaded from database)
- A `VendorBreachLog` record exists with `breachRate = 15.00` and `threshold = 10.00`
- `VendorSlaBreached` event was published (verify via SKU state change — the `VendorBreachListener` pauses linked SKUs)
- The linked SKU is now `PAUSED`

**Key validation:** This exercises the real `FulfillmentDataProviderImpl` which calls `orderRepository.countViolations()` — the custom JPQL query that uses `OR` to count distinct violations. The unit test mocks this provider, so query bugs (circular data dependency, incorrect join) would be invisible.

**Time-bound:** All inserted orders use `createdAt` within the last 30 days (matching `ROLLING_WINDOW`), satisfying the time-bounded query convention.

**Cleanup:** `@AfterEach` truncates `vendor_breach_log`, `vendor_sku_assignments`, `orders`, `vendors`, `sku_state_history`, `skus`.

### 6. Fulfillment Module — Order State Transition REST Endpoints + Tests (BR-5.1)

#### 6a. New REST Endpoints

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/OrderController.kt` (extend existing)

Add three new endpoints:

```kotlin
@PostMapping("/{id}/confirm")
fun confirmOrder(@PathVariable id: String): ResponseEntity<OrderResponse>

@PostMapping("/{id}/ship")
fun shipOrder(@PathVariable id: String, @RequestBody request: ShipOrderRequest): ResponseEntity<OrderResponse>

@PostMapping("/{id}/deliver")
fun deliverOrder(@PathVariable id: String): ResponseEntity<OrderResponse>
```

Each endpoint delegates to the existing `OrderService` methods (`routeToVendor`, `markShipped`, `markDelivered`).

**New DTO file:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/dto/ShipOrderRequest.kt`
```kotlin
data class ShipOrderRequest(
    val trackingNumber: String,
    val carrier: String
)
```

#### 6b. Controller Unit Tests

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/OrderControllerTest.kt` (extend existing)

Add tests for the three new endpoints:
- `POST /{id}/confirm returns CONFIRMED order` — mock `routeToVendor`, assert 200 + status=CONFIRMED
- `POST /{id}/ship returns SHIPPED order with tracking` — mock `markShipped`, assert 200 + tracking details
- `POST /{id}/deliver returns DELIVERED order` — mock `markDelivered`, assert 200 + status=DELIVERED
- `POST /{id}/confirm returns 400 for invalid transition` — mock throws `IllegalArgumentException`, assert 400
- `POST /{id}/deliver returns 404 for unknown order` — mock throws `IllegalArgumentException`, assert 404

#### 6c. Order Transition Integration Test

**File:** `modules/app/src/test/kotlin/com/autoshipper/fulfillment/OrderTransitionIntegrationTest.kt`

**What it tests:** Full order lifecycle via REST: create -> confirm -> ship -> deliver, verifying each transition persists and the final `markDelivered` call triggers the `OrderFulfilled` event chain.

**Setup:**
1. Create a vendor (needed for the `OrderEventListener` -> `OrderAmountProvider` chain)
2. Create an order via `POST /api/orders`
3. Walk through transitions via REST:
   - `POST /api/orders/{id}/confirm` -> assert status = CONFIRMED
   - `POST /api/orders/{id}/ship` with tracking -> assert status = SHIPPED
   - `POST /api/orders/{id}/deliver` -> assert status = DELIVERED
4. After delivery, verify the `OrderEventListener` AFTER_COMMIT chain:
   - A `CapitalOrderRecord` exists for the order
   - The reserve account balance increased

**Invalid transition test:**
- After delivery, `POST /api/orders/{id}/confirm` returns 400 (cannot confirm a DELIVERED order)

### 7. E2E Playbook Updates (BR-5.2, BR-6)

#### 7a. Phase 2.3 — Replace DB updates with REST calls

**File:** `docs/e2e-test-playbook.md` (update existing section)

Replace the current Phase 2.3 (`UPDATE orders SET ...` via psql) with:

```bash
# Confirm order
curl -s -X POST "http://localhost:8080/api/orders/$ORDER_ID/confirm" | python3 -m json.tool
# Expected: status = "CONFIRMED"

# Ship order
curl -s -X POST "http://localhost:8080/api/orders/$ORDER_ID/ship" \
  -H "Content-Type: application/json" \
  -d '{"trackingNumber":"TRK123456","carrier":"UPS"}' | python3 -m json.tool
# Expected: status = "SHIPPED", trackingNumber = "TRK123456"

# Deliver order
curl -s -X POST "http://localhost:8080/api/orders/$ORDER_ID/deliver" | python3 -m json.tool
# Expected: status = "DELIVERED"
```

Remove the "Note" about DB-only approach bypassing `OrderService`. Add a verification step:
```bash
# Verify OrderEventListener credited the reserve
curl -s "http://localhost:8080/api/capital/reserve" | python3 -m json.tool
# balanceAmount should be non-zero (10% of order total)
```

Update Phase 3.1 to remove the manual `INSERT INTO capital_order_records` — the `OrderEventListener` now handles this.

#### 7b. Compliance Re-Check Scenario (BR-6)

**File:** `docs/e2e-test-playbook.md` (add new section after Phase 1.2c)

Add a new subsection `1.2d Compliance Re-Check (Fail -> Fix -> Pass)`:

1. Create SKU with trademarked name (e.g., "Nike Air Max Clone")
2. Run compliance -> fails with `IP_INFRINGEMENT`
3. Verify SKU is TERMINATED (via `ComplianceFailed` -> `CatalogComplianceListener`)
4. Create a NEW SKU with a clean name (e.g., "Premium Wireless Speaker")
5. Run compliance -> passes all checks
6. Verify SKU transitions to `VALIDATION_PENDING`
7. Query compliance audit for the new SKU: `GET /api/compliance/skus/{newSkuId}`
8. Assert `latestResult = "CLEARED"` with no contamination from the old SKU's FAILED records
9. Verify the old SKU's audit is independent: `GET /api/compliance/skus/{failSkuId}` still shows FAILED

The key invariant: the compliance status query uses `skuId` as the partition key — one SKU's history never leaks into another's. `ComplianceAuditRepository.findBySkuIdOrderByCheckedAtDesc()` scopes by SKU ID.

#### 7c. Known Gaps Table Update

**File:** `docs/e2e-test-playbook.md` (update Known Gaps section)

Remove the first gap row ("No REST endpoints for order state transitions") since FR-022 adds them. Update the "Impact" column for any remaining gaps.

### 8. CI Configuration Update

**File:** `.github/workflows/ci.yml`

Add new integration test count gates for the new test classes:

| Test Class | Minimum Expected |
|---|---|
| `PricingInitializerIntegrationTest` | 1 |
| `MarginSweepFailureIntegrationTest` | 1 |
| `DemandScanJobIntegrationTest` | 3 |
| `VendorSlaMonitorIntegrationTest` | 1 |
| `OrderTransitionIntegrationTest` | 2 |

## Task Breakdown

### Fulfillment Module — REST Endpoints (BR-5.1)

- [x] **Task 1.1:** Add `POST /api/orders/{id}/confirm` endpoint to `OrderController` — delegates to `orderService.routeToVendor()`
- [x] **Task 1.2:** Add `POST /api/orders/{id}/ship` endpoint with `ShipOrderRequest` DTO — delegates to `orderService.markShipped()`
- [x] **Task 1.3:** Add `POST /api/orders/{id}/deliver` endpoint — delegates to `orderService.markDelivered()`
- [x] **Task 1.4:** Add controller unit tests for the 3 new endpoints (happy path + error cases) in `OrderControllerTest.kt`
- [x] **Task 1.5:** Add `OrderTransitionIntegrationTest` in `modules/app/` — full lifecycle via REST + OrderFulfilled event chain verification

### Pricing Module — PricingInitializer Integration Test (BR-1.1)

- [x] **Task 2.1:** Add `PricingInitializerIntegrationTest` — SKU transitions to LISTED, assert `SkuPriceEntity` persisted with exact price/margin/cost values

### Capital Module — Failure Path + Reserve Tests (BR-1.2, BR-3.2)

- [x] **Task 3.1:** Add `MarginSweepFailureIntegrationTest` — multi-SKU sweep with breach-level metrics, assert snapshot persisted and other SKUs processed independently
- [x] **Task 3.2:** Add `ReserveCalcJobTest` unit test — 9 non-refunded + 1 refunded order at 10% reserve rate, assert exact balance of $90.00 (not $100.00)

### Portfolio Module — Transaction Boundary + JSONB Tests (BR-2.1, BR-4)

- [x] **Task 4.1:** Add `DemandScanJobIntegrationTest` — scoring service throws, assert `DemandScanRun.status = "FAILED"` in database
- [x] **Task 4.2:** Add JSONB round-trip test for `DemandCandidate.demandSignals` — save via TransactionTemplate, read back, assert JSON content preserved
- [x] **Task 4.3:** Add JSONB round-trip test for `CandidateRejection.metadata` — same pattern as 4.2

### Vendor Module — VendorSlaMonitor Integration Test (BR-3.1)

- [x] **Task 5.1:** Add `VendorSlaMonitorIntegrationTest` — insert real orders, run monitor, assert vendor suspended + SKU paused + breach log created

### E2E Playbook Updates (BR-5.2, BR-6)

- [x] **Task 6.1:** Update Phase 2.3 to use REST endpoints instead of direct SQL for order transitions
- [x] **Task 6.2:** Update Phase 3.1 to remove manual `capital_order_records` insert (now handled by OrderEventListener)
- [x] **Task 6.3:** Add compliance re-check scenario (Section 1.2d) — fail -> new SKU -> pass -> verify no contamination
- [x] **Task 6.4:** Update Known Gaps table to remove the resolved order endpoint gap

### CI + Build Integrity

- [x] **Task 7.1:** Update `.github/workflows/ci.yml` with test count gates for new integration test classes
- [x] **Task 7.2:** Run `./gradlew build` — verify all new and existing tests pass, 0 skipped

## Testing Strategy

### How to validate the tests themselves

1. **False-positive detection:** Each integration test that exercises an `AFTER_COMMIT` listener must NOT use `@Transactional` on the test class. The ArchUnit Rule 2 in `ArchitectureTest.kt` bans `@Testcontainers`, but there is no rule banning `@Transactional` on integration test classes. If a future developer adds `@Transactional` to one of these test classes, the listener silently stops firing and the test becomes a false positive. Consider adding a code review checklist item for this.

2. **Mutation verification:** For the `ReserveCalcJobTest` (BR-3.2), temporarily change the filter from `!it.refunded` to `true` (include all orders). The test should fail, producing $100.00 instead of $90.00. This confirms the test actually catches the bug it's designed to prevent.

3. **JSONB annotation removal:** For the `DemandCandidate` JSONB test (BR-4), temporarily remove `@JdbcTypeCode(SqlTypes.JSON)` from the entity. The test should fail with a PostgreSQL type mismatch error, confirming the test catches the PM-011 pattern.

4. **Endpoint error handling:** The new order transition endpoints should return 400 for invalid transitions and 404 for unknown orders. Both error paths need explicit test coverage.

### Test isolation guarantees

- Each test class uses `@AfterEach` cleanup with `TRUNCATE TABLE ... CASCADE`
- No test depends on data created by another test
- No shared mutable state between test methods
- All time-sensitive queries use explicit `since` parameters (no unbounded counts)

## Rollout Plan

### Phase 1: Production Code (Tasks 1.1-1.3)

Add the three REST endpoints to `OrderController`. These are additive — no existing endpoints change. The `OrderService` methods they delegate to already exist and are tested.

**Risk:** Minimal. The endpoints are thin wrappers around existing, tested service methods.

### Phase 2: Unit Tests (Tasks 1.4, 3.2)

Add controller unit tests and the `ReserveCalcJob` unit test. These run without a database.

### Phase 3: Integration Tests (Tasks 1.5, 2.1, 3.1, 4.1-4.3, 5.1)

Add all `@SpringBootTest` integration tests. These require the test database.

**Dependency:** PostgreSQL must be running locally (or in CI) with the `autoshipper_test` database.

### Phase 4: E2E Playbook + CI (Tasks 6.1-6.4, 7.1-7.2)

Update the E2E playbook documentation and CI test count gates. No production code changes.

### Migration Needs

None. All new tests use existing database tables. No Flyway migrations required. The REST endpoints use existing domain objects.

### Build Verification

After all tasks complete:
1. `./gradlew build` passes with all new tests
2. No existing tests broken (additive only)
3. CI pipeline updated with new test count gates
4. E2E playbook manually validated against running application
