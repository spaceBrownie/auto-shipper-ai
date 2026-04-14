# FR-029: Live E2E Test Infrastructure — Test Specification

**Linear ticket:** RAT-42
**Phase:** 4 (Test Specification)
**Created:** 2026-04-14

---

## Scope of Automated Testing

This feature has a single production code change: adding a `@PostConstruct` warning and an early-return guard to `ShipmentTracker`. Everything else is configuration (`.env.example`), new files (`Dockerfile`, `.dockerignore`, `docs/live-e2e-runbook.md`), and modifications to `docker-compose.yml`. Configuration and documentation changes cannot be validated through automated tests — they are covered by the manual verification checklist below.

The automated test scope is therefore narrow and focused:

1. `ShipmentTracker` with an empty `carrierProviders` list (the new behavior)
2. `ShipmentTracker` with a populated `carrierProviders` list (regression — existing tests cover this)

---

## Acceptance Criteria

### AC-1: ShipmentTracker accepts an empty carrier provider list without error

**What:** Constructing `ShipmentTracker` with `carrierProviders = emptyList()` must not throw.

**Test:** Unit test creates `ShipmentTracker` with empty list, calls `pollAllShipments()`, and verifies no exception is thrown and no repository save is attempted.

**Maps to:** SC-1 (App starts without `local` profile), BR-1, NFR-5

### AC-2: pollAllShipments() returns immediately when no providers are registered

**What:** When `carrierProviders` is empty, `pollAllShipments()` must return without querying the order repository.

**Test:** Unit test creates `ShipmentTracker` with empty list, calls `pollAllShipments()`, verifies `orderRepository.findByStatus()` is never called.

**Maps to:** BR-1, NFR-5, AD-1

### AC-3: warnIfNoProviders() executes without error for empty list

**What:** The `@PostConstruct` method must execute cleanly when `carrierProviders` is empty (logging a warning).

**Test:** Unit test creates `ShipmentTracker` with empty list, calls `warnIfNoProviders()`, verifies no exception.

**Maps to:** SC-1

### AC-4: warnIfNoProviders() executes without error for populated list

**What:** The `@PostConstruct` method must execute cleanly when `carrierProviders` is non-empty (logging the provider list).

**Test:** Unit test creates `ShipmentTracker` with mock providers, calls `warnIfNoProviders()`, verifies no exception.

**Maps to:** SC-6 (Existing tests remain green)

### AC-5: Existing ShipmentTracker behavior is unchanged

**What:** All 7 existing `ShipmentTrackerTest` tests must continue to pass. The early-return guard must not interfere with the normal polling path when providers are present.

**Test:** Run existing `ShipmentTrackerTest` suite — all must pass.

**Maps to:** SC-6, NFR-3

---

## Test Class Design

### New Test: `ShipmentTrackerEmptyProvidersTest`

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/ShipmentTrackerEmptyProvidersTest.kt`

This test class covers the new empty-provider-list behavior introduced by FR-029. It is separate from the existing `ShipmentTrackerTest` to make the boundary clear: existing tests validate normal operation with providers; this class validates graceful degradation without providers.

```kotlin
@ExtendWith(MockitoExtension::class)
class ShipmentTrackerEmptyProvidersTest {

    @Mock lateinit var orderRepository: OrderRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher
    @Mock lateinit var delayAlertService: DelayAlertService

    private lateinit var tracker: ShipmentTracker

    @BeforeEach
    fun setUp() {
        tracker = ShipmentTracker(
            orderRepository = orderRepository,
            carrierProviders = emptyList(),
            eventPublisher = eventPublisher,
            delayAlertService = delayAlertService
        )
    }

    // Test 1: AC-1 + AC-2
    @Test
    fun `pollAllShipments with no providers skips polling entirely`() {
        tracker.pollAllShipments()

        verifyNoInteractions(orderRepository)
        verifyNoInteractions(eventPublisher)
        verifyNoInteractions(delayAlertService)
    }

    // Test 2: AC-3
    @Test
    fun `warnIfNoProviders does not throw when provider list is empty`() {
        // Should log a warning but not throw
        tracker.warnIfNoProviders()
    }

    // Test 3: AC-4 (regression safety)
    @Test
    fun `warnIfNoProviders does not throw when providers are present`() {
        val mockProvider = mock<CarrierTrackingProvider> {
            on { carrierName } doReturn "UPS"
        }
        val trackerWithProviders = ShipmentTracker(
            orderRepository = orderRepository,
            carrierProviders = listOf(mockProvider),
            eventPublisher = eventPublisher,
            delayAlertService = delayAlertService
        )

        trackerWithProviders.warnIfNoProviders()
    }
}
```

**Total new tests:** 3
**Total test methods in test-spec:** 3 new + 7 existing `ShipmentTrackerTest` = 10 covering `ShipmentTracker`

---

## Fixture Data

### Empty provider list

No fixture data needed beyond `emptyList<CarrierTrackingProvider>()`. The empty list is the fixture.

### Mock dependencies

All tests use Mockito `@Mock` annotations for:
- `OrderRepository` — not called when providers are empty
- `ApplicationEventPublisher` — not called when providers are empty
- `DelayAlertService` — not called when providers are empty

No shipped order fixtures are needed for the empty-provider tests because the early-return guard exits before querying orders.

---

## Boundary Cases

### BC-1: Empty provider list (primary new case)

`carrierProviders = emptyList()` is the core boundary. The early-return guard in `pollAllShipments()` must prevent any repository interaction.

**Covered by:** `ShipmentTrackerEmptyProvidersTest` test 1

### BC-2: PostConstruct with empty list

`warnIfNoProviders()` must handle the empty list without NPE or formatting errors in the log message.

**Covered by:** `ShipmentTrackerEmptyProvidersTest` test 2

### BC-3: PostConstruct with populated list

`warnIfNoProviders()` with a non-empty provider list must correctly log provider names via `carrierProviders.map { it.carrierName }`.

**Covered by:** `ShipmentTrackerEmptyProvidersTest` test 3

### BC-4: Normal polling with providers still works (regression)

Existing tests in `ShipmentTrackerTest` cover all normal-operation boundaries:
- Order with unknown carrier (skipped)
- Order with null carrier (skipped)
- Order with null tracking number (skipped)
- Empty shipped orders list (no-op)
- One order throws, next still processed (resilience)
- Delivered order publishes event
- Delayed order triggers alert

**Covered by:** Existing `ShipmentTrackerTest` (7 tests)

### Boundaries NOT applicable

- **JSON null / NullNode guards (Constraint 17):** No external JSON parsing in this change.
- **URL encoding (Constraint 12):** No HTTP request construction.
- **JSONB columns (Constraint 8):** No entity changes.
- **Transaction patterns (Constraint 6):** The `@PostConstruct` and early return are non-transactional. The existing `@Transactional` on `pollAllShipments()` is unchanged.

---

## E2E Playbook Scenarios

The existing `docs/e2e-test-playbook.md` operates under the `local` profile with stub carrier tracking providers. FR-029 does not change that playbook. The new `docs/live-e2e-runbook.md` is the live-API equivalent and is a standalone document (AD-4).

No new scenarios are added to `docs/e2e-test-playbook.md`. The live E2E runbook is documentation, not an automated test, and its verification steps are covered in the manual checklist below.

---

## Contract Test Candidates

There are no contract tests to write for this feature. The change is purely defensive (empty-list tolerance). There are no new domain types, no new external API interactions, and no new event contracts.

---

## Manual Verification Checklist

The following items cannot be validated through automated tests. They must be verified manually during implementation (Layer 5 of the implementation plan).

### MV-1: Application starts without `local` profile (SC-1)

```bash
SPRING_PROFILES_ACTIVE= ./gradlew :app:bootRun
```

**Verify:**
- No `BeanCreationException` in logs
- `ShipmentTracker` warning about empty providers appears in logs
- Health endpoint responds: `curl -s http://localhost:8080/actuator/health`

### MV-2: `.env.example` completeness (SC-2)

Cross-reference every `${VAR}` in `application.yml` and every `@Value("${key:}")` in adapter classes against entries in `.env.example`. No variable should be missing.

### MV-3: Docker build succeeds (SC-3)

```bash
docker build -t auto-shipper-ai .
```

**Verify:** Build completes without error. Image is created.

### MV-4: Docker compose starts full stack (SC-3)

```bash
docker compose up
```

**Verify:**
- PostgreSQL starts and becomes healthy
- App starts, connects to DB, runs Flyway migrations
- Health endpoint responds: `curl -s http://localhost:8080/actuator/health`

### MV-5: `.dockerignore` excludes expected paths (NFR-2)

Verify that `.git/`, `build/`, `node_modules/`, `.env`, `.idea/`, `frontend/`, `docs/`, `feature-requests/` are excluded from the Docker build context.

### MV-6: No secrets in committed files (NFR-1)

Verify that `Dockerfile`, `docker-compose.yml`, `.env.example`, and `docs/live-e2e-runbook.md` contain zero real API keys, tokens, passwords, or secrets.

### MV-7: Existing tests remain green (SC-6)

```bash
./gradlew test
```

**Verify:** Zero failures.

### MV-8: Runbook structure completeness (SC-4)

Verify `docs/live-e2e-runbook.md` contains all sections required by BR-5 through BR-10:
- [ ] Webhook tunnel setup (BR-5)
- [ ] Shopify webhook registration (BR-6)
- [ ] CJ webhook registration (BR-7)
- [ ] Supplier product mapping seeding (BR-8)
- [ ] 8-step pipeline walkthrough (BR-9)
- [ ] Observability guidance (BR-10)
- [ ] Known gaps section
- [ ] Troubleshooting section

---

## Test Execution Order

1. Run `ShipmentTrackerEmptyProvidersTest` (3 new tests) — validates the code change
2. Run `ShipmentTrackerTest` (7 existing tests) — regression validation
3. Run `OrderLifecycleTest` (4 existing tests) — broader fulfillment regression
4. Run full `./gradlew test` — project-wide regression (SC-6)
5. Execute manual verification checklist MV-1 through MV-8
