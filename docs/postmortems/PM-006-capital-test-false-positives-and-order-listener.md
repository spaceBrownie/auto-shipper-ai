# PM-006: CapitalIntegrationTest False Positives and OrderEventListener Transaction Safety

**Date:** 2026-03-12
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

Three bugs were found via automated PR review on PR #12 (follow-up to PM-005). `OrderEventListener` used synchronous `@EventListener` instead of the `AFTER_COMMIT` + `REQUIRES_NEW` pattern, meaning a failure in capital recording could roll back the fulfillment transaction and lose order DELIVERED status. Separately, `CapitalIntegrationTest` had two compounding issues: (1) `@Transactional` on the test class prevented `AFTER_COMMIT` listeners from ever firing, and (2) tests 5–6 published events outside any transaction, so the `AFTER_COMMIT` listener silently discarded them. All six tests appeared to pass only because Testcontainers was silently skipped (PM-004). Fixed by applying the PM-005 double-annotation pattern, removing `@Transactional` from the test, and wrapping direct `publishEvent` calls in `TransactionTemplate.execute {}`.

## Timeline

| Time | Event |
|------|-------|
| 2026-03-11 05:05 | `unblocked-bot` posts 2 review comments on PR #12 commit `61305d9` |
| Comment #1 | `OrderEventListener` identified as cross-module listener (capital → fulfillment) using synchronous `@EventListener` — same class of bug as PM-005 |
| Comment #2 | `CapitalIntegrationTest` flagged: `@Transactional` on test class prevents `AFTER_COMMIT` listeners from firing; tests pass only because Testcontainers is silently skipped |
| First fix applied | Applied `AFTER_COMMIT` + `REQUIRES_NEW` to `OrderEventListener`; removed `@Transactional` and Testcontainers from test; added `@AfterEach` cleanup. Committed as `c63dde3` |
| 2026-03-11 05:20 | `unblocked-bot` posts follow-up review on commit `c63dde3` |
| Comment #3 | Tests 5–6 publish `ShutdownRuleTriggered` directly via `eventPublisher.publishEvent()` with no surrounding transaction. `@TransactionalEventListener(AFTER_COMMIT)` with default `fallbackExecution=false` silently discards events published outside a transaction. Test 5 is a false positive; test 6 will fail. |
| Unblocked consultation | Confirmed `TransactionTemplate` approach is correct; `fallbackExecution=true` would change production behavior |
| Second fix applied | Added `TransactionTemplate` injection; wrapped both `publishEvent` calls in `transactionTemplate.execute {}`. Committed as `4ee31bf` |
| Unit tests verified | All 6 `CapitalIntegrationTest` tests pass (0 skipped) with `./gradlew :app:test` |
| E2E verified | Full application E2E: SKU lifecycle → LISTED → margin breach → auto-pause confirmed via live endpoints and DB audit trail |

## Symptom

### Bug 1: OrderEventListener joins fulfillment transaction

`OrderEventListener` in the `capital` module listens for `OrderFulfilled` events published by `OrderService.markDelivered()` in the `fulfillment` module. Using synchronous `@EventListener`, the listener joined the fulfillment transaction. If `reserveAccountService.creditFromOrder()` or `orderRecordRepository.save()` threw (e.g., DB constraint violation, currency mismatch in `ReserveAccount.credit()`), the fulfillment transaction was marked rollback-only — **losing the order's DELIVERED status**.

### Bug 2: CapitalIntegrationTest @Transactional prevents listeners from firing

The test class was annotated `@Transactional` (Spring auto-rollback per test). Tests 1–3 relied on `ShutdownRuleEngine.evaluate()` publishing `ShutdownRuleTriggered`, consumed by `ShutdownRuleListener` which uses `@TransactionalEventListener(phase = AFTER_COMMIT)`. In a `@Transactional` test, the transaction never commits — so the listener never fires, the SKU is never paused, and `assertEquals("PAUSED", ...)` should fail. Tests passed only because `@Testcontainers(disabledWithoutDocker = true)` silently skipped them when Docker was unavailable (PM-004).

### Bug 3: Tests 5–6 publish events outside any transaction

After removing `@Transactional` from the test class, tests 5–6 called `eventPublisher.publishEvent(ShutdownRuleTriggered(...))` directly from the test method with no surrounding transaction. `ShutdownRuleListener` uses `@TransactionalEventListener(phase = AFTER_COMMIT)` with `fallbackExecution = false` (the default). When an event is published outside a transaction, it is **silently discarded** — no exception, no warning.

- **Test 5** (IDEATION guard): passed trivially because the listener never executed, not because it correctly detected and skipped an IDEATION SKU. **False positive.**
- **Test 6** (SCALED → PAUSED): would fail because the SKU stays SCALED — `assertEquals("PAUSED", ...)` can never be satisfied.

## Root Cause

Five layers of "why":

1. **Why** could a capital recording failure roll back a fulfillment transaction?
   → `OrderEventListener` used `@EventListener` (synchronous), joining the publisher's transaction.

2. **Why** was the wrong annotation used?
   → `OrderEventListener` was written during FR-009 after PM-005 had already established the `AFTER_COMMIT` + `REQUIRES_NEW` convention, but PM-005's prevention audit categorized `OrderEventListener` as a "same-module listener that should rollback together" — incorrectly, since it is capital listening to fulfillment (cross-module).

3. **Why** didn't integration tests catch the `@Transactional` test problem?
   → `@Testcontainers(disabledWithoutDocker = true)` silently skipped all tests when Docker was unavailable (PM-004 Testcontainers incompatibility). The tests never actually executed.

4. **Why** did tests 5–6 silently pass after removing `@Transactional`?
   → `@TransactionalEventListener` with default `fallbackExecution=false` silently discards events published outside a transaction. No exception is thrown and no warning is logged. The test assertion `assertEquals("IDEATION", ...)` in test 5 passed trivially because nothing happened.

5. **Why** is silent event discard the default behavior?
   → Spring's design choice: `fallbackExecution=false` is the safe default because `AFTER_COMMIT` listeners often assume the originating transaction's data is committed and visible. Running without a transaction could lead to data inconsistencies. The tradeoff is that misconfigured tests silently pass.

### Problematic code

**OrderEventListener (before fix):**
```kotlin
@EventListener          // ← synchronous, joins publisher's tx
@Transactional          // ← REQUIRED propagation, no isolation
fun onOrderFulfilled(event: OrderFulfilled) {
```

**CapitalIntegrationTest tests 5–6 (before fix):**
```kotlin
// Published outside any transaction — silently discarded
eventPublisher.publishEvent(
    ShutdownRuleTriggered(skuId = SkuId(sku.id), rule = "MARGIN_BREACH", ...)
)
```

## Fix Applied

### Fix 1: OrderEventListener — AFTER_COMMIT + REQUIRES_NEW

Applied the same PM-005 double-annotation pattern:

```kotlin
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun onOrderFulfilled(event: OrderFulfilled) {
```

The listener now runs after the fulfillment transaction commits (cannot poison it) and in its own independent transaction (writes are properly flushed).

### Fix 2: Remove @Transactional and Testcontainers from test class

Removed `@Transactional` and the Testcontainers companion object. Added `@AfterEach` cleanup via `JdbcTemplate.execute("TRUNCATE ...")`, consistent with `MarginSweepIntegrationTest`.

### Fix 3: Wrap publishEvent in TransactionTemplate

Tests 5–6 now publish events inside a `TransactionTemplate.execute {}` block so the `AFTER_COMMIT` listener fires after commit:

```kotlin
@Autowired lateinit var transactionTemplate: TransactionTemplate

// in test:
transactionTemplate.execute {
    eventPublisher.publishEvent(
        ShutdownRuleTriggered(skuId = SkuId(sku.id), rule = "MARGIN_BREACH", ...)
    )
}
// assertions run after the transaction commits and listener fires
```

### Files Changed
- `modules/capital/src/main/kotlin/com/autoshipper/capital/listener/OrderEventListener.kt` — Changed `@EventListener` to `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`
- `modules/app/src/test/kotlin/com/autoshipper/capital/CapitalIntegrationTest.kt` — Removed `@Transactional` and Testcontainers; added `@AfterEach` cleanup, `TransactionTemplate` for event publishing in tests 5–6

## Impact

- **OrderEventListener:** In production, any exception in capital recording (constraint violation, currency mismatch) would roll back the fulfillment transaction, causing the order to lose its DELIVERED status. The customer would appear as if their order was never delivered, and the reserve would never be credited.
- **CapitalIntegrationTest:** All 6 tests were silently skipped due to PM-004 Testcontainers incompatibility. Once Docker becomes available or the tests switch to a running Postgres, tests 1–3 would fail (AFTER_COMMIT listeners don't fire in `@Transactional` tests), test 5 would be a false positive, and test 6 would fail.
- **Caught in:** Automated PR review by `unblocked-bot` on PR #12, before merge to main.

## Lessons Learned

### What went well
- Automated PR review (`unblocked-bot`) caught both the production bug and the test false positives across two review cycles — each push got new, targeted feedback
- PM-005 had already established the `AFTER_COMMIT` + `REQUIRES_NEW` convention, making the fix pattern immediately clear
- `MarginSweepIntegrationTest` served as the correct reference implementation (no `@Transactional`, `@AfterEach` cleanup)
- E2E testing via live application endpoints confirmed the full event chain works end-to-end

### What could be improved
- PM-005's prevention audit miscategorized `OrderEventListener` as same-module — the audit should have checked the event's source module, not just the listener's package
- `@Testcontainers(disabledWithoutDocker = true)` masks test failures by silently skipping tests. This allowed 6 tests to "pass" for days without ever executing
- No CI gate ensures integration tests actually execute (vs. being skipped)
- The `@TransactionalEventListener` silent-discard behavior (no warning when `fallbackExecution=false` and no transaction exists) makes test bugs invisible
- No REST endpoints exist for order state transitions (`markShipped`, `markDelivered`), making E2E testing of the `OrderEventListener` AFTER_COMMIT chain impossible via HTTP

## Prevention

- [ ] **Ban `@Testcontainers(disabledWithoutDocker = true)` in integration tests.** Use the running Postgres from `application-test.yml` instead. Tests that silently skip are worse than tests that fail — they give false confidence. Consider a detekt rule or grep-based CI check.
- [ ] **Add CI check that verifies integration test count.** Assert that `CapitalIntegrationTest` reports exactly 6 executed tests (not 0 skipped). A test count regression means something is being silently skipped.
- [ ] **Add a codebase-wide lint rule: any `@TransactionalEventListener(AFTER_COMMIT)` handler that writes to the database must have `@Transactional(propagation = REQUIRES_NEW)`.** This was PM-001 Action Item #4 and PM-005 prevention item — still not automated.
- [ ] **Audit cross-module listener categorization.** The PM-005 postmortem's prevention section listed `OrderEventListener` as same-module. Re-audit by checking: does the event originate from a different module than the listener? If yes, it must use `AFTER_COMMIT` + `REQUIRES_NEW`.
- [ ] **Add REST endpoints for order state transitions** (`POST /api/orders/{id}/confirm`, `/ship`, `/deliver`) to enable full E2E testing of the `OrderFulfilled` → `OrderEventListener` AFTER_COMMIT chain via HTTP.
- [ ] **Document the `TransactionTemplate` test pattern.** When testing `@TransactionalEventListener(AFTER_COMMIT)` listeners directly (not through a `@Transactional` service method), events must be published inside a `TransactionTemplate.execute {}` block. Add this to the project's testing conventions.

## Related Documents

- [PM-001: Pricing Not Persisted — AFTER_COMMIT Runs Without Active Transaction](PM-001-pricing-not-persisted-after-commit-listener.md)
- [PM-005: Cross-Module Event Listeners Poison Publisher Transactions](PM-005-cross-module-listener-transaction-safety.md)
- [E2E Test Playbook](../e2e-test-playbook.md) — manual test script for validating the full chain
