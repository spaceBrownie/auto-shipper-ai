# PM-001: Pricing Not Persisted — TransactionalEventListener AFTER_COMMIT Runs Without Active Transaction

**Date:** 2026-03-04
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

`PricingInitializer` listened for `SkuStateChanged` events using `@TransactionalEventListener(phase = AFTER_COMMIT)` and called `PricingEngine.setInitialPrice()` when a SKU transitioned to `LISTED`. The listener executed without errors, but no `SkuPriceEntity` was ever persisted to the database. The root cause was that `AFTER_COMMIT` listeners run after the originating transaction has already committed and closed, so Spring's transaction synchronization state prevented the `@Transactional` proxy on `PricingEngine` from opening a new transaction. Adding `@Transactional(propagation = Propagation.REQUIRES_NEW)` directly on the listener method forced a fresh transaction and resolved the issue.

## Timeline

| Time | Event |
|------|-------|
| Session start | `PricingInitializer` created to listen for `SkuStateChanged` events and call `PricingEngine.setInitialPrice()` when a SKU transitions to `LISTED` |
| Shortly after | Listener annotated with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` only |
| During testing | `GET /api/skus/{id}/pricing` returned 404 — no `SkuPriceEntity` was created despite the listener firing |
| Investigation | Logs confirmed the listener method was invoked (no exceptions thrown), yet the database had no pricing row |
| Root cause identified | `AFTER_COMMIT` listeners execute outside the originating transaction. Spring's `@Transactional` proxy on `PricingEngine` did not create a new transaction because of the post-commit synchronization state |
| Fix applied | Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` to `PricingInitializer.onSkuStateChanged()` |
| Verified | `GET /api/skus/{id}/pricing` returned the expected record: price=$199.99, margin=72.96%, signalType=INITIAL |

## Symptom

After transitioning a SKU to `LISTED`, the `GET /api/skus/{id}/pricing` endpoint returned HTTP 404. No `SkuPriceEntity` row existed in the database. There were no errors, exceptions, or warnings in the application logs — the listener appeared to execute successfully but its database writes were silently discarded.

## Root Cause

The failure stemmed from a subtle interaction between Spring's `@TransactionalEventListener` and Spring's transaction proxy mechanism.

**The chain of events:**

1. A SKU state change to `LISTED` was persisted inside a transaction (the "originating transaction").
2. When that transaction committed, Spring's event infrastructure invoked `PricingInitializer.onSkuStateChanged()` because it was annotated with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.
3. At this point, the originating transaction had already committed and closed. However, Spring's `TransactionSynchronizationManager` still had residual synchronization state from the just-completed commit phase.
4. `PricingInitializer` called `PricingEngine.setInitialPrice()`. `PricingEngine` is annotated with `@Transactional` at the class level, which normally means the proxy would begin a new transaction.
5. Because the call originated from within the `AFTER_COMMIT` callback — still inside Spring's transaction synchronization lifecycle — the `@Transactional` proxy on `PricingEngine` did not open a new transaction. The default propagation (`REQUIRED`) looks for an existing transaction to join, and the synchronization state misled it into thinking it was still within a transactional context, even though the actual database transaction had already committed.
6. The JPA `save()` calls inside `setInitialPrice()` executed but were never flushed to the database because there was no real transaction to commit.

**Why this was especially insidious:** No exception was thrown. The code executed line by line as expected. The JPA entity was created in memory, `save()` was called, but nothing was committed because there was no active transaction wrapping the persistence operations.

**File:** `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt`

## Fix Applied

Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` directly on the `onSkuStateChanged()` method in `PricingInitializer`. This annotation forces Spring to open a completely new, independent transaction regardless of any existing synchronization state. The combination of annotations on the method is now:

```kotlin
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun onSkuStateChanged(event: SkuStateChanged) { ... }
```

`REQUIRES_NEW` creates a fresh transaction that is independent of the originating transaction's lifecycle, ensuring that all JPA operations within the listener are properly committed.

### Files Changed
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt` — Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` to `onSkuStateChanged()` to ensure a fresh transaction is opened when the `AFTER_COMMIT` listener fires

## Impact

Any SKU transitioning to `LISTED` would have no initial pricing record created. This means:
- The pricing API would return 404 for all newly listed SKUs
- Downstream pricing signal processing (`PricingEngine.onPricingSignal`) would also fail silently for those SKUs, since it guards on `skuPriceRepository.findBySkuId()` returning null
- No data corruption occurred — the data simply was never written. Once the fix was applied, re-triggering the transition produced the correct pricing record.

This was caught during development/integration testing before reaching production.

## Lessons Learned

### What went well
- The 404 on the pricing endpoint provided a clear signal that something was wrong
- The idempotency guard in `PricingInitializer` (checking if price already exists before creating) meant that once the fix was applied, reprocessing was safe
- Logs confirmed the listener was firing, which quickly narrowed the problem to the persistence layer rather than event delivery

### What could be improved
- The silent failure mode is dangerous. There was no indication that writes were being lost — no exception, no warning log, nothing. The code appeared to work perfectly while producing no durable side effects.
- The interaction between `@TransactionalEventListener(AFTER_COMMIT)` and `@Transactional` proxies is a well-known Spring footgun, but there was no project-level guidance or convention for handling it.

## Prevention

- [ ] Establish a project convention: all `@TransactionalEventListener(AFTER_COMMIT)` handlers that perform writes must be annotated with `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Document this in CLAUDE.md under Critical Engineering Constraints.
- [ ] Add an integration test that transitions a SKU to `LISTED` and asserts that a `SkuPriceEntity` exists in the database afterward. This would have caught the silent persistence failure immediately.
- [ ] Consider adding a custom `ArchUnit` or lint rule that flags `@TransactionalEventListener(phase = AFTER_COMMIT)` methods that lack `@Transactional(propagation = REQUIRES_NEW)`, making the constraint structural rather than conventional.
- [ ] Add a post-persist verification log in `PricingInitializer` that reads back the saved entity and confirms it exists, providing an early warning if the write was silently dropped.
