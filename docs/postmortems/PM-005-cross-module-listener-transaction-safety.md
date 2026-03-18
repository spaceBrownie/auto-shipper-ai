# PM-005: Cross-Module Event Listeners Poison Publisher Transactions or Silently Fail to Persist

**Date:** 2026-03-11
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

Three cross-module event listeners (`ShutdownRuleListener`, `VendorBreachListener`, `PricingDecisionListener`) had transaction boundary bugs that either poisoned the publisher's transaction (losing MarginSnapshot and audit data) or silently failed to persist SKU state changes. Discovered via automated PR review on PR #12. Fixed by applying the established double-annotation pattern: `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`.

## Timeline

| Time | Event |
|------|-------|
| PR #12 submitted | `unblocked-bot` flagged 3 issues on the MarginSweep pipeline in automated review |
| Comment #1 | Duplicate snapshot `DataIntegrityViolationException` on 2nd daily sweep — already fixed in prior commit |
| Comment #2 | Single `@Transactional` across all SKUs corrupts Hibernate session — already fixed via `REQUIRES_NEW` on `MarginSweepSkuProcessor` |
| Comment #3 | `ShutdownRuleListener` runs synchronously inside processor's `REQUIRES_NEW` tx; exception marks tx rollback-only, losing snapshot + audit data |
| Investigation | Audited all event listeners in the codebase; found 3 bugs across 3 files |
| PM-001 surfaced | Unblocked context engine returned PM-001 postmortem, revealing the `AFTER_COMMIT` + `REQUIRES_NEW` convention and a known gotcha with stale `TransactionSynchronizationManager` state |
| Third bug found | `PricingDecisionListener` already used `AFTER_COMMIT` but lacked `REQUIRES_NEW` — PM-001 Action Item #2 (open audit) confirmed it was a known risk |
| Fix applied | All three listeners updated to double-annotation pattern |
| Verified | `./gradlew build` passes; all 4 `MarginSweepIntegrationTest` tests pass including refund-breach → auto-pause E2E |

## Symptom

### Bug 1 & 2: ShutdownRuleListener and VendorBreachListener

When `ShutdownRuleEngine.evaluate()` detected a kill-rule breach (e.g., refund rate > 5%), it published a `ShutdownRuleTriggered` event. `ShutdownRuleListener` handled it synchronously via `@EventListener`, running inside the `MarginSweepSkuProcessor`'s `REQUIRES_NEW` transaction. If `skuService.transition()` threw (optimistic lock conflict, invalid state transition), Spring marked the transaction **rollback-only**. The `catch (e: Exception)` block caught the exception but could not undo the rollback-only flag. When the processor's transaction attempted to commit, it rolled back — **losing the MarginSnapshot, all CapitalRuleAudit records, and the MarginSnapshotTaken event** for that SKU.

`VendorBreachListener` had the same bug compounded by a loop: when pausing multiple SKUs for a vendor SLA breach, one failed `transition()` call poisoned the shared transaction, preventing all remaining SKUs from being paused.

### Bug 3: PricingDecisionListener

`PricingDecisionListener` used `@TransactionalEventListener(phase = AFTER_COMMIT)` but lacked `@Transactional(propagation = REQUIRES_NEW)`. Per PM-001, `AFTER_COMMIT` listeners run in Spring's post-commit synchronization state where `TransactionSynchronizationManager` has residual state from the just-committed transaction. The `@Transactional(REQUIRED)` proxy on `SkuService` mistakenly joined this stale context instead of opening a new transaction. Result: `skuService.transition()` calls (pause/terminate from pricing signals) executed without an active database transaction — JPA entities were created in memory but **never flushed**. No exception was thrown.

## Root Cause

Three layers of the "5 whys":

1. **Why did snapshots get lost?** → `ShutdownRuleListener` ran inside the processor's transaction and marked it rollback-only on failure.
2. **Why did it run inside the processor's transaction?** → `@EventListener` is synchronous — it joins whatever transaction the publisher is in. The processor used `REQUIRES_NEW`, and the listener joined that.
3. **Why wasn't it decoupled from the publisher's transaction?** → The listener predated PM-001, which established the `AFTER_COMMIT` + `REQUIRES_NEW` convention. No project-wide enforcement of this pattern existed.
4. **Why did `PricingDecisionListener` silently fail despite using `AFTER_COMMIT`?** → `AFTER_COMMIT` alone is insufficient. Spring's `TransactionSynchronizationManager` retains stale synchronization state in the `afterCompletion()` callback. A `REQUIRED` propagation call sees this state and does not open a new transaction. Only `REQUIRES_NEW` forces a completely independent transaction.
5. **Why wasn't this caught earlier?** → Unit tests mock the event publisher, so transaction boundaries aren't exercised. The integration test (`MarginSweepIntegrationTest`) happened to pass because `skuService.transition()` didn't throw in the happy path — the bug only manifests when a transition fails.

### Problematic code — ShutdownRuleListener (before fix)

```kotlin
@EventListener  // ← joins publisher's REQUIRES_NEW transaction
fun onShutdownRuleTriggered(event: ShutdownRuleTriggered) {
    try {
        skuService.transition(event.skuId, SkuState.Paused)  // ← if this throws...
    } catch (e: Exception) {
        logger.error("Failed to process shutdown rule", e)   // ← catches exception but tx is already poisoned
    }
}
```

### Problematic code — PricingDecisionListener (before fix)

```kotlin
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)  // ← runs post-commit
// Missing: @Transactional(propagation = Propagation.REQUIRES_NEW)
fun onPricingDecision(decision: PricingDecision) {
    skuService.transition(decision.skuId, SkuState.Paused)  // ← silently doesn't persist
}
```

## Fix Applied

Applied the established double-annotation pattern (from PM-001) to all three listeners:

```kotlin
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun onShutdownRuleTriggered(event: ShutdownRuleTriggered) { ... }
```

This ensures:
- **Decoupling**: The listener runs after the publisher's transaction commits, so snapshot/audit data is never at risk
- **Fresh transaction**: `REQUIRES_NEW` forces a completely independent transaction, bypassing stale synchronization state
- **Idempotent retry**: If the listener fails, the triggering condition (bad margin, SLA breach) persists in the data, and the next scheduled sweep re-fires the rule

### Files Changed
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/ShutdownRuleListener.kt` — `@EventListener` → `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/VendorBreachListener.kt` — `@EventListener` → `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingDecisionListener.kt` — Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` (already had `@TransactionalEventListener`)

## Impact

- **ShutdownRuleListener/VendorBreachListener**: Any SKU where `skuService.transition()` threw during a kill-rule evaluation would lose its `MarginSnapshot` for the day, its `CapitalRuleAudit` records, and the `MarginSnapshotTaken` event. The auto-pause/terminate action would also fail silently. On subsequent sweeps, the snapshot would be re-created, but the historical gap could affect margin trend analysis.
- **PricingDecisionListener**: SKU pause/terminate actions triggered by pricing signals (`PricingDecision.PauseRequired`, `PricingDecision.TerminateRequired`) were silently discarded. SKUs that should have been paused or terminated by the pricing engine remained active — a capital protection failure.
- **Blast radius**: All three bugs affect the automated shutdown pipeline — the system's primary defense against margin erosion. Caught during code review before production deployment.

## Lessons Learned

### What went well
- Automated PR review (`unblocked-bot`) caught the initial transaction isolation issue
- Querying Unblocked's context engine surfaced PM-001, which revealed the `AFTER_COMMIT` + `REQUIRES_NEW` convention and upgraded a 2-file fix to a 3-file fix
- PM-001 Action Item #2 ("Audit PricingDecisionListener") directly identified the third bug
- Existing `MarginSweepIntegrationTest` validated the fix end-to-end without test changes

### What could be improved
- PM-001 established the double-annotation convention 7 days ago but it was not documented in `CLAUDE.md` or enforced structurally — the two listeners in catalog module were written without it
- No integration test exercises the failure path (transition throws) to verify transaction isolation
- The project has no automated check that `@TransactionalEventListener(AFTER_COMMIT)` methods also carry `@Transactional(REQUIRES_NEW)`

## Prevention

- [x] Document the `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` pattern in `CLAUDE.md` under Critical Engineering Constraints as constraint #6 *(RAT-17: CLAUDE.md constraint #6)*
- [x] Add an ArchUnit test that flags any `@TransactionalEventListener(phase = AFTER_COMMIT)` method missing `@Transactional(propagation = REQUIRES_NEW)` — make the constraint structural *(RAT-17: ArchitectureTest Rule 1)*
- [ ] Add a failure-path integration test: mock `SkuService.transition()` to throw for one SKU in a multi-SKU sweep, and verify that (a) the snapshot is still persisted and (b) other SKUs are still processed
- [ ] Audit remaining `@EventListener` usages (`OrderEventListener`, `PricingEngine`) to verify they don't need the same treatment — `OrderEventListener` uses explicit `@Transactional` which is acceptable for same-module listeners that should rollback together
