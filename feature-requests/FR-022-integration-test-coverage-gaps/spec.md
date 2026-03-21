# FR-022: Integration Test Coverage Gaps

## Problem Statement

Across seven postmortems (PM-001, PM-002, PM-005, PM-006, PM-009, PM-011), the most repeated lesson is that **unit tests with mocked repositories cannot catch transaction boundary, data-flow, or persistence bugs**. Each postmortem identified specific integration tests that would have caught the bug before it reached E2E testing or code review. RAT-17 codified the testing conventions into CLAUDE.md, but the specific tests recommended by each PM prevention section remain unwritten.

The project currently has three categories of testing gaps:

1. **Transaction and event listener tests** -- The `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` pattern is the most critical cross-module wiring in the system. Silent persistence failures (PM-001), publisher transaction poisoning (PM-005), and scheduled job rollback bugs (PM-011) were all discovered late because no integration test exercises the full transaction lifecycle. Existing unit tests mock repositories, so they pass even when the listener would silently discard writes at runtime.

2. **Data-flow correctness tests** -- Mocked dependencies hide circular data dependencies (PM-002) and incorrect denominator logic (PM-006). The `VendorSlaMonitor` relies on fulfillment data that crosses module boundaries; the `ReserveCalcJob` must correctly exclude refunded orders from its reserve percentage calculation. Both need tests that exercise the actual data path.

3. **E2E scenario gaps** -- The E2E test playbook cannot test the full `OrderFulfilled` event chain because no REST endpoints exist for order state transitions (PM-006). The compliance re-check scenario (fail, correct, re-check, verify CLEARED) is not documented, leaving a gap where historical FAILED records could contaminate the re-check result (PM-009).

Until these tests exist, the project relies on manual E2E testing and code review to catch the exact class of bugs that postmortems have shown are invisible to unit tests.

## Business Requirements

### BR-1: Transaction boundary safety for event listeners (PM-001, PM-005)

The system must have automated tests that verify the `AFTER_COMMIT` + `REQUIRES_NEW` persistence chain works end-to-end for critical cross-module event listeners. Specifically:

- **BR-1.1:** When a SKU transitions to `LISTED`, a `SkuPriceEntity` must exist in the database afterward. This is the PM-001 scenario: the `PricingInitializer` listener fires on `SkuStateChanged`, reads the stress test result and cost envelope, computes margin, and delegates to `PricingEngine.setInitialPrice()`. A test must assert on database state, not method invocation.

- **BR-1.2:** When `MarginSweepJob` processes multiple SKUs and one SKU's transition throws an exception, the `MarginSnapshot` for the failing SKU must still be persisted, and all remaining SKUs must still be processed. This is the PM-005 scenario: `MarginSweepSkuProcessor` uses `REQUIRES_NEW` to isolate each SKU's processing, and `ShutdownRuleListener` uses `AFTER_COMMIT` + `REQUIRES_NEW` to avoid poisoning the processor's transaction.

### BR-2: Scheduled job failure-path persistence (PM-011)

Scheduled jobs that write status to the database must have tests verifying that failure states persist after exceptions. Specifically:

- **BR-2.1:** When `DemandScanJob` fails mid-pipeline (e.g., scoring service throws), the `DemandScanRun` row must have `status = 'FAILED'` in the database. The existing `DemandScanJobTest` has a unit test for this scenario using mocks, but the transaction boundary test pattern (verifying persistence via real database) must be added. This is the template for all scheduled job failure-path tests.

### BR-3: Data-flow correctness across module boundaries (PM-002, PM-006)

Tests must exercise real data paths to catch bugs that mocked dependencies hide. Specifically:

- **BR-3.1:** The `VendorSlaMonitor` must be tested with real database records -- insert fulfillment records, run the monitor, and assert breach detection works end-to-end. The current unit test mocks `VendorFulfillmentDataProvider`, which hides any circular data dependencies or query bugs in the real implementation.

- **BR-3.2:** The `ReserveCalcJob` must be tested with a mix of refunded and non-refunded orders to verify that the reserve percentage calculation uses only non-refunded revenue as the denominator. Example: 9 non-refunded orders + 1 refunded order at 10% reserve rate must produce exactly 10% reserve (not 9% from incorrectly including refunded revenue in the denominator).

### BR-4: JSONB persistence verification (PM-011)

The full persistence path for JSONB columns (annotated with `@JdbcTypeCode(SqlTypes.JSON)`) must be exercised in integration tests. The `DemandCandidate.demandSignals` and `CandidateRejection.metadata` columns use JSONB and the `@JdbcTypeCode` annotation. Without a test that performs a full save-and-read cycle through JPA to a real PostgreSQL database, the Hibernate/PostgreSQL impedance mismatch (PM-011 Bug 1) could recur silently.

### BR-5: E2E testability for order lifecycle (PM-006)

The E2E test playbook currently advances orders through state transitions via direct SQL updates, bypassing the `OrderService` methods and therefore the `OrderFulfilled` domain event. This means the `OrderEventListener` `AFTER_COMMIT` chain (which credits the reserve account) is never tested end-to-end via HTTP.

- **BR-5.1:** REST endpoints must exist for order state transitions (`POST /api/orders/{id}/confirm`, `POST /api/orders/{id}/ship`, `POST /api/orders/{id}/deliver`) so the E2E playbook can exercise the full event chain via HTTP requests.

- **BR-5.2:** The E2E playbook must be updated to use these endpoints instead of direct SQL, enabling validation of the `OrderFulfilled` -> `OrderEventListener` -> reserve credit chain.

### BR-6: Compliance re-check scenario (PM-009)

The E2E test playbook must include a compliance re-check scenario that verifies the system correctly handles the sequence: fail compliance -> correct the issue -> re-check -> verify status shows `CLEARED`. This catches any bug where historical `FAILED` audit records contaminate the re-check result (e.g., if the status query returns the most recent `FAILED` record instead of the most recent run's result).

## Success Criteria

### Transaction & event listener tests

- [ ] An integration test transitions a SKU through the full lifecycle (Ideation -> Listed) and asserts that a `SkuPriceEntity` row exists in the database with the correct price, margin, and cost values (not placeholder matchers). Events must be published inside a `TransactionTemplate.execute {}` block or through a `@Transactional` service method to ensure `AFTER_COMMIT` listeners fire.
- [ ] An integration test runs `MarginSweepJob` against multiple SKUs where one SKU's `SkuService.transition()` throws, and asserts: (a) the snapshot for the failing SKU is still persisted in the database, (b) all other SKUs are still processed, (c) the `ShutdownRuleListener` does not poison the processor's `REQUIRES_NEW` transaction.
- [ ] An integration test runs `DemandScanJob` with a scoring service that throws mid-pipeline, and asserts that the `DemandScanRun` row in the database has `status = 'FAILED'`.

### Data-flow correctness tests

- [ ] An integration test for `VendorSlaMonitor` inserts real fulfillment records into the database, runs the monitor, and asserts breach detection and vendor suspension work end-to-end without mocked data providers.
- [ ] A unit test for `ReserveCalcJob` creates a mix of refunded and non-refunded orders and asserts that the reserve percentage is calculated using only non-refunded revenue as the denominator. The test must use exact `BigDecimal` assertions (not approximate matchers).
- [ ] An integration test saves a `DemandCandidate` with a populated `demandSignals` JSON field to a real PostgreSQL database, reads it back, and asserts the JSON content is preserved.

### E2E scenario gaps

- [ ] REST endpoints `POST /api/orders/{id}/confirm`, `POST /api/orders/{id}/ship`, and `POST /api/orders/{id}/deliver` exist and correctly transition order state, triggering domain events (including `OrderFulfilled` on delivery).
- [ ] The E2E test playbook Phase 2.3 is updated to use the new REST endpoints instead of direct SQL updates, and includes verification that the `OrderEventListener` credits the reserve account.
- [ ] The E2E test playbook includes a compliance re-check scenario: create SKU with trademarked name -> fail compliance -> create new SKU with clean name -> pass compliance -> verify status shows `CLEARED` with no contamination from the prior SKU's `FAILED` records.

### Build integrity

- [ ] `./gradlew build` passes with all new tests.
- [ ] No existing tests are broken or modified (additive only).

## Non-Functional Requirements

### NFR-1: Test isolation

Each integration test must be independently runnable and must not depend on execution order or shared mutable state from other tests. Tests that require a database must use Spring's `@Transactional` test rollback or `@Sql` cleanup to avoid cross-test pollution.

### NFR-2: Test performance

Integration tests that require a running PostgreSQL instance must use Testcontainers or the project's existing test database configuration. Tests must complete within 30 seconds individually and not add more than 2 minutes to the total `./gradlew test` execution time.

### NFR-3: Exact value assertions

All financial assertions must use exact `BigDecimal` or `Money` comparisons. The use of `any<Money>()` or approximate matchers for monetary values is prohibited per project testing conventions. This applies to price amounts, margin percentages, reserve balances, and cost figures.

### NFR-4: Transaction boundary fidelity

Integration tests for `@TransactionalEventListener(AFTER_COMMIT)` listeners must use one of two patterns to ensure the listener fires: (a) publish events inside a `TransactionTemplate.execute {}` block, or (b) invoke a `@Transactional` service method that publishes the event as a side effect. Tests that publish events outside a transaction context will silently skip the listener and produce false positives.

### NFR-5: Time-bounded queries

Any test that exercises queries on append-only tables (e.g., `capital_order_records`, `margin_snapshots`) must include time-bound parameters. Unbounded counts on append-only tables are a code smell per project conventions and produce incorrect business metrics.

## Dependencies

| Dependency | Required by | Notes |
|---|---|---|
| PostgreSQL test database | All integration tests | Either Testcontainers or local `autoshipper_test` database |
| `TransactionTemplate` | BR-1.1, BR-2.1 transaction boundary tests | Available via Spring's `PlatformTransactionManager` |
| `VendorFulfillmentDataProvider` real implementation | BR-3.1 | Must use the actual provider, not a mock, to validate data-flow |
| `OrderService` methods (`confirmOrder`, `shipOrder`, `markDelivered`) | BR-5.1 | These internal methods exist; need REST endpoint wiring |
| Flyway migrations | All integration tests | Test database must have current schema |
| Existing postmortem documentation | Specification validation | PM-001, PM-002, PM-005, PM-006, PM-009, PM-011 |
