# Post-Mortem: PricingInitializer Silent Persistence Failure

**Date:** 2026-03-04
**Severity:** Medium (data not persisted; no errors logged)
**Module:** `pricing` (`modules/pricing`)
**Status:** Resolved

---

## Summary

`PricingInitializer` listened for `SkuStateChanged` events to set the initial price when a SKU transitioned to `LISTED`. The listener fired successfully and no errors appeared in logs, but the `SkuPriceEntity` was never persisted to the database. The `GET /api/skus/{id}/pricing` endpoint returned 404 for newly listed SKUs.

---

## Timeline

1. `PricingInitializer` was implemented as a `@Component` with a `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` method that called `PricingEngine.setInitialPrice()` on `SkuStateChanged` events where `toState == "LISTED"`.
2. After listing a SKU, the listener method executed (confirmed via log output: "Initialized pricing for SKU ...").
3. However, querying `GET /api/skus/{id}/pricing` returned 404 -- no `SkuPriceEntity` row existed in the database.
4. No exceptions or warnings appeared in the application logs.

---

## Root Cause

The interaction between `@TransactionalEventListener(AFTER_COMMIT)` and Spring's transaction management created a silent persistence failure.

### How Spring TransactionalEventListener works

`@TransactionalEventListener(phase = AFTER_COMMIT)` defers event delivery until the originating transaction commits. Once that transaction commits, Spring invokes the listener **outside** of any active transaction context. At this point, the originating transaction's `TransactionSynchronization` callbacks have already completed.

### Why PricingEngine.setInitialPrice() did not persist

`PricingEngine` is annotated with `@Service @Transactional` at the class level. Under normal circumstances, calling `setInitialPrice()` through the Spring proxy would start a new transaction. However, when called from within a `@TransactionalEventListener(AFTER_COMMIT)` callback:

- Spring's `TransactionSynchronizationManager` is still in a post-commit synchronization state.
- The default propagation (`REQUIRED`) checks for an existing transaction. The synchronization state from the just-committed transaction interferes with new transaction creation.
- The `@Transactional` proxy on `PricingEngine` does not open a new transaction because it interprets the synchronization state as "already participating in a transaction" (even though that transaction has committed).
- As a result, `skuPriceRepository.save()` and `pricingHistoryRepository.save()` inside `setInitialPrice()` execute without an active transaction and their writes are never flushed to the database.

This is a known subtlety in Spring Framework. The `AFTER_COMMIT` phase runs inside the `TransactionSynchronization.afterCompletion()` callback, where the transaction synchronization resources have not yet been fully cleared. A `REQUIRED` propagation call sees stale synchronization state and does not start a fresh transaction.

### Why no error was logged

JPA's `EntityManager.persist()` does not throw an exception when called outside a transaction in all configurations. The entity is added to the persistence context but never flushed. Since `setInitialPrice()` completed without exception, the log message "Initialized pricing for SKU ..." was emitted, giving a false signal that everything worked.

---

## Fix

Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` directly on the `onSkuStateChanged` method in `PricingInitializer`:

```kotlin
// File: modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun onSkuStateChanged(event: SkuStateChanged) {
    // ...
}
```

`REQUIRES_NEW` forces Spring to suspend any existing transaction synchronization state and open a completely independent transaction. This ensures:

1. A fresh transaction is active when `PricingEngine.setInitialPrice()` runs.
2. The `SkuPriceEntity` and `SkuPricingHistoryEntity` are flushed and committed.
3. The new transaction commits independently of the originating transaction (which has already committed).

### Verification

After applying the fix, listing a SKU and querying `GET /api/skus/{id}/pricing` returned the expected result:

- `price = $199.99`
- `margin = 72.96%`
- `signalType = INITIAL`

---

## Impact

- **Affected functionality:** Any SKU transitioned to `LISTED` state would not have an initial price record. The pricing endpoint returned 404, and subsequent `PricingSignal` events would be silently dropped by `PricingEngine.onPricingSignal()` (which returns early with a warning if no `SkuPriceEntity` exists).
- **Data loss:** No permanent data loss. Re-listing or replaying the state transition after the fix creates the pricing record correctly.
- **Blast radius:** Limited to the `pricing` module. SKU lifecycle state transitions in `catalog` were unaffected.

---

## Lessons Learned

### 1. `@TransactionalEventListener(AFTER_COMMIT)` runs outside a usable transaction

Code executing in the `AFTER_COMMIT` phase must not assume that calling another `@Transactional` method with default propagation (`REQUIRED`) will open a new transaction. The Spring transaction synchronization state from the completed transaction lingers, and `REQUIRED` propagation does not reliably create a new transaction in this context.

**Rule:** Any `@TransactionalEventListener(AFTER_COMMIT)` handler that needs to write to the database must be annotated with `@Transactional(propagation = Propagation.REQUIRES_NEW)`.

### 2. Silent persistence failures are dangerous

JPA can silently accept `persist()` calls without an active transaction. The entity appears to be saved (no exception), but the write is never flushed. This is especially dangerous in event-driven architectures where the caller cannot easily verify the downstream effect.

**Rule:** Integration tests for event listeners should assert on database state, not just on log output or method invocation.

### 3. PricingDecisionListener has the same risk

`PricingDecisionListener` (in the same module) also uses `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` and calls `skuService.transition()` and `priceSyncAdapter.syncPrice()`. If either of those methods requires a write transaction, the same silent failure pattern applies. This should be audited.

---

## Action Items

| # | Action | Owner | Status |
|---|--------|-------|--------|
| 1 | Add `@Transactional(propagation = REQUIRES_NEW)` to `PricingInitializer.onSkuStateChanged` | -- | Done |
| 2 | Audit `PricingDecisionListener.onPricingDecision` for the same transaction issue | -- | Open |
| 3 | Add integration test that asserts `SkuPriceEntity` exists in DB after SKU transitions to LISTED | -- | Open |
| 4 | Consider a project-wide lint rule or code review checklist item: "AFTER_COMMIT listeners that write must use REQUIRES_NEW" | -- | Open |

---

## Related Files

- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt` -- the fixed listener
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingEngine.kt` -- `setInitialPrice()` and `@Transactional` class-level annotation
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingDecisionListener.kt` -- potentially affected by the same pattern
- `modules/shared/src/main/kotlin/com/autoshipper/shared/events/SkuStateChanged.kt` -- the triggering domain event
