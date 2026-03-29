# PM-018: FR-025 v2 Workflow Process Gaps — Third Attempt Success with Structural Findings

**Date:** 2026-03-29
**Severity:** Medium
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

FR-025 (CJ supplier order placement, RAT-27) succeeded on its third attempt using the feature-request-v2 v3.0.0 workflow, producing 46 files changed and 42+ tests — all green including app-level integration tests against PostgreSQL. However, the run exposed 4 structural gaps in the v2 skill: Phase 5 agents ignored test-spec.md as a binding contract, validate-phase.py doesn't gate on test-spec deliverables, Phase 6 has no comment polling, and the meta-controller's chunking recommendation was overridden without documentation. Additionally, the Unblocked PR review bot caught a state machine bug (FAILED order retry bypasses idempotency guard) that all 42 tests missed — validating external review as a critical safety net.

## Timeline

| Time | Event |
|------|-------|
| Session start | Linear issue RAT-27 pulled, Phase 1 (Discovery) subagent launched |
| ~5 min | Phase 1 complete — `cj-supplier-order-placement` name validated, 9 data model gaps identified, prior PR #37/#39 failures documented |
| ~10 min | Phase 2 (Specification) complete — spec.md with 7 BRs, 9 success criteria, 8 NFRs |
| ~15 min | Phase 3 (Planning) complete — 28 tasks, 6 key design decisions, 24-task breakdown |
| ~25 min | Phase 4 (Test Specification) complete — test-spec.md with acceptance criteria, fixtures, boundary cases, quality checklist |
| ~25 min | Branch `feat/RAT-27-cj-supplier-order-placement` created, Phase 1-4 artifacts committed, draft PR #41 opened |
| ~26 min | Meta-controller preflight: recommends 3 parallel agents, deliberative mode, 7-task chunks |
| ~27 min | User approves Phase 5. Orchestrator overrides chunk strategy — uses file-ownership partitioning instead |
| ~30 min | Round 1: 3 agents create 9 new files (shared event, migration, domain, proxy, handler) |
| ~35 min | Round 2: 3 agents modify existing files (domain model, ShopifyOrderAdapter, service layer) |
| ~35 min | Compile check passes — also discovers OrderConfirmed.kt had wrong import (`shared.domain` → `shared.identity`) |
| ~45 min | Round 3: 3 agents write tests (domain, service, contract/integration) — 34 new tests |
| ~46 min | `./gradlew test` — `:app:test` fails with 36 failures. Dismissed as "pre-existing" |
| ~47 min | E2E playbook runs in background, passes 142 tests |
| ~48 min | Implementation committed and pushed. Phase 5 deliverables validated |
| ~48 min | **User identifies two issues**: (1) database needed for app tests, (2) test-spec.md not followed |
| ~50 min | Test-spec gap agent fills 8 missing test methods, creates 8 fixture files |
| ~52 min | Database rebuilt (old V21 from prior attempts had wrong column names). Full suite: 40/40 pass, then 2 fail |
| ~53 min | Root cause: `routeToVendor()` now publishes `OrderConfirmed` → listener fires → no mapping → FAILED. `OrderTransitionIntegrationTest` can't ship a FAILED order |
| ~54 min | Fix: `@MockBean SupplierOrderPlacementService` in integration test. Full suite: BUILD SUCCESSFUL |
| ~55 min | PR marked ready for review |
| ~56 min | Unblocked review: FAILED order retry bypasses idempotency guard — status check missing |
| ~57 min | Fix: add `order.status != OrderStatus.CONFIRMED` guard + 2 regression tests |
| ~58 min | Second Unblocked comment: confirms fix, asks for code/test evidence |
| ~59 min | Reply with line numbers and test names. Quality checklist checked off |
| ~60 min | PR approved and merged |

## Symptom

No single production bug — this post-mortem covers process gaps discovered during an otherwise successful feature implementation. The symptoms were:

1. **Test-spec drift**: 8 test methods, 8 fixture files, and 1 entire test class specified in test-spec.md were not implemented by Phase 5 agents
2. **App test failures**: `./gradlew test` returned 36 failures in `:app:test` — initially misdiagnosed as "pre-existing"
3. **State machine bug**: Unblocked review identified that a FAILED order retried through `placeSupplierOrder()` would either crash (`FAILED→FAILED` invalid transition) or corrupt state (set `supplierOrderId` on a FAILED order)

## Root Cause

### 5 Whys: Test-Spec Ignored

1. **Why** were 8 test methods and 8 fixture files missing? → Phase 5 agents wrote tests from the implementation-plan.md task breakdown, not from test-spec.md
2. **Why** did agents use the implementation plan instead of the test spec? → Phase 5 instructions say "Read test-spec.md for what to test" — advisory language, not mandatory
3. **Why** wasn't this caught by validation? → `validate-phase.py --check-deliverables` only checks summary.md existence and implementation-plan.md checkboxes. It has no knowledge of test-spec.md
4. **Why** doesn't validate-phase.py check test-spec.md? → Phase 4 was redesigned in v3.0.0 to produce a specification document, but the validation script wasn't updated to gate on it
5. **Why** wasn't this obvious? → test-spec.md uses markdown tables for test specifications — no `- [ ]` checkboxes to track completion, unlike implementation-plan.md

### 5 Whys: App Integration Test Failures

1. **Why** did `OrderTransitionIntegrationTest` fail? → The confirm step now publishes `OrderConfirmed`, which triggers `SupplierOrderPlacementListener`, which fails (no mapping) and transitions the order to FAILED. The test then can't ship a FAILED order
2. **Why** wasn't this anticipated? → The side effect of adding `OrderConfirmed` event publishing to `routeToVendor()` was not traced to all consumers — the integration test is a consumer
3. **Why** were the initial 36 failures dismissed? → The orchestrator assumed `:app:test` failures were pre-existing (no database). This was partially correct — the database was running but had stale V21 migration from prior attempts
4. **Why** was the test database stale? → Prior PR attempts (#37, #39) left a different V21 migration applied. `flywayClean` was attempted but failed silently because Flyway 11.x disables clean by default
5. **Why** wasn't the database state checked? → No step in the workflow verifies database migration state before running tests

### 5 Whys: FAILED Order Retry Bug

1. **Why** could a FAILED order bypass the idempotency guard? → The guard only checked `supplierOrderId != null`. A FAILED order has `supplierOrderId == null` (placement never succeeded)
2. **Why** was there no status check? → The idempotency design (Decision 6 in the implementation plan) assumed `supplierOrderId` was a sufficient deduplication signal
3. **Why** didn't tests catch this? → Tests covered: happy path, failure path, idempotency (supplierOrderId set), missing mapping, order not found — but NOT the intersection of "previously failed + retried"
4. **Why** wasn't this intersection tested? → test-spec.md boundary cases (section 3.5) don't include "retry after failure." The spec focused on first-attempt scenarios
5. **Why** did Unblocked catch it? → Unblocked reasons about state machine invariants holistically — it traces all paths through the state space, not just the specified test scenarios

## Fix Applied

### Test-Spec Enforcement
- Filled 8 missing test methods across 4 test classes
- Created all 8 fixture files specified in test-spec.md
- Updated WireMock tests to load from fixture files
- Checked off quality checklist (12 items)

### App Integration Tests
- `@MockBean SupplierOrderPlacementService` added to `OrderTransitionIntegrationTest` to prevent CONFIRMED→FAILED side effect
- Rebuilt test database: `DROP DATABASE autoshipper_test; CREATE DATABASE autoshipper_test;` then fresh migration

### FAILED Order Retry Bug
- Added status guard in `SupplierOrderPlacementService.placeSupplierOrder()`:
```kotlin
if (order.status != OrderStatus.CONFIRMED) {
    logger.info("Order {} is in status {}, not CONFIRMED — skipping supplier placement", orderId, order.status)
    return
}
```
- Two regression tests: `skips placement when order is FAILED from previous attempt`, `skips placement when order is PENDING not yet CONFIRMED`

### Files Changed
- `SupplierOrderPlacementService.kt` — added status != CONFIRMED guard after idempotency check
- `SupplierOrderPlacementServiceTest.kt` — 2 new regression tests
- `OrderTransitionIntegrationTest.kt` — @MockBean SupplierOrderPlacementService
- `OrderServiceTest.kt` — 2 new tests (quantity/shippingAddress persistence)
- `LineItemOrderCreatorTest.kt` — 2 new tests (null firstName/lastName edge cases)
- `CjSupplierOrderAdapterWireMockTest.kt` — 3 new tests (HTTP 500, address line2 concatenation)
- `SupplierOrderPlacementListenerTest.kt` — new test class (delegation verification)
- 8 fixture JSON files created under `src/test/resources/`

## Impact

Medium. The FAILED order retry bug would only manifest under retry/recovery infrastructure that doesn't yet exist. The test-spec drift didn't affect code quality (tests were good, just incomplete vs. spec). The app test failure was a local dev environment issue from stale prior-attempt state.

## Lessons Learned

### What went well
- **Parallel agent strategy worked**: File-ownership partitioning (Round 1: new files, Round 2: modifications, Round 3: tests) prevented merge conflicts entirely — zero coordination issues across 9 parallel agent sessions
- **Unblocked PR review is essential**: Caught a real state machine bug that 42 human-designed tests missed. This validates external review as a non-optional step
- **Prior attempt learnings applied**: NullNode guard on all 11 shipping fields (PR #39 fix), explicit quantity with no default (PR #37 fix), Resilience4j exceptions not caught (PR #37 fix), zero test theater (PR #39 fix)
- **Pre-existing bugs fixed**: 5 NullNode guard violations in ShopifyOrderAdapter discovered and fixed during the shipping address implementation
- **Compile-time catches**: Wrong import package (`shared.domain` vs `shared.identity`) caught immediately by `compileKotlin`
- **Full test suite with database**: Once the database was properly set up, all 40 app integration tests passed — including the new OrderConfirmed event chain

### What could be improved
- **test-spec.md is a dead document**: Phase 5 agents don't treat it as a binding deliverable. It needs checkboxes and validation gating
- **validate-phase.py is incomplete**: Doesn't know about test-spec.md. The Phase 5 deliverable check is structurally weaker than intended
- **Meta-controller override undocumented**: The orchestrator used file-ownership partitioning instead of the recommended 7-task chunks. This worked better but the override wasn't documented in `decision-support/override-justification.md` as the skill requires
- **Database state not verified**: No workflow step checks migration state before running tests. Stale migrations from prior attempts caused 36 false failures
- **Phase 6 is passive**: Requires user to manually report PR comments. Should poll `gh api` for new comments/reviews
- **Side-effect tracing missing**: Adding event publishing to `routeToVendor()` wasn't traced to all downstream consumers (integration tests)

## Prevention

### Skill/Workflow Fixes (feature-request-v2)

- [ ] **Add checkboxes to test-spec.md template** — Phase 4 should produce `- [ ]` checkboxes for every test method, boundary case, and fixture file. Phase 5 checks them off as tests are written
- [ ] **validate-phase.py: gate Phase 5 on test-spec.md** — `--check-deliverables` should verify all test-spec.md checkboxes are checked before allowing summary.md creation
- [ ] **Phase 5 instructions: make test-spec mandatory** — Change "Read test-spec.md for what to test" to "Every item in test-spec.md MUST be implemented and checked off before summary.md"
- [ ] **Phase 5: enforce override documentation** — If the orchestrator overrides the meta-controller, `decision-support/override-justification.md` must be written before implementation proceeds
- [ ] **Phase 6: add comment polling** — Background `gh api` poll every 60s for new comments/reviews. Exit loop only when CI green + no unresolved comments + PR approved
- [ ] **Phase 5: add database migration verification step** — Before running tests, verify `flywayInfo` shows all migrations applied on the test database

### Codebase Fixes

- [ ] **State machine guard pattern** — Any service that operates on an entity with a state machine should guard on expected status, not just idempotency fields. Consider adding this to CLAUDE.md constraints
- [ ] **Side-effect impact analysis** — When adding event publishing to an existing method, trace all `@TransactionalEventListener` and `@EventListener` consumers to assess impact on existing tests

### Automated Checks

- [ ] **CI: verify test database migrations** — Add a CI step that runs `flywayValidate` against the test database schema before tests execute
- [ ] **ArchUnit: state machine guard rule** — Consider a rule that any `@Service` method loading an entity with a state machine must check `entity.status` before mutating
