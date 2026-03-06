# Incident Post-Mortem: Pricing 404 for Listed SKUs

**Date:** 2026-03-04
**Severity:** High
**Affected component:** Pricing module (`GET /api/skus/{id}/pricing`)
**Status:** Resolved

---

## Summary

After a SKU successfully passed stress testing and transitioned to `LISTED` state, requests to `GET /api/skus/{id}/pricing` returned HTTP 404. The pricing record was never created because `PricingEngine.setInitialPrice()` was never called during the SKU lifecycle. No bridge existed between the catalog module's state transition and the pricing module's initialization logic.

---

## Timeline

1. SKU progressed through the lifecycle: `Ideation -> ValidationPending -> CostGating -> StressTesting`.
2. `StressTestService.run()` executed successfully, verifying margins above the 50% gross / 30% net floors.
3. `StressTestService` called `skuService.transition(skuId, SkuState.Listed)`, which published a `SkuStateChanged` domain event with `toState = "LISTED"`.
4. The SKU was now in `LISTED` state with a persisted `StressTestResultEntity` (containing `estimatedPriceAmount` and `stressedTotalCostAmount`) and a `CostEnvelopeEntity` (containing the full cost breakdown).
5. No component listened for the `SkuStateChanged` event to initialize pricing. `PricingEngine.setInitialPrice()` existed but was dead code -- nothing called it.
6. `GET /api/skus/{id}/pricing` queried `SkuPriceRepository.findBySkuId()`, found no record, and returned 404.

---

## Root Cause

A missing integration point between bounded contexts. The catalog module (`:catalog`) owns the SKU lifecycle and publishes `SkuStateChanged` events. The pricing module (`:pricing`) owns `PricingEngine.setInitialPrice()`, which creates the `SkuPriceEntity` record that the pricing API reads. However, no event listener existed to bridge these two modules. The `setInitialPrice()` method was implemented but never wired into any call path.

This is a classic cross-module integration gap in a modular monolith: each module's internal logic was correct, but the event-driven contract between them was never established.

---

## Impact

- Any SKU that reached `LISTED` state had no pricing data. The pricing endpoint returned 404, making the SKU effectively unlisted from an operational standpoint.
- The `PricingEngine.onPricingSignal()` listener (which handles dynamic price adjustments via `PricingSignal` events) also silently ignored signals for these SKUs because no `SkuPriceEntity` existed, logging a warning and returning early.
- Downstream consumers of pricing data (future storefront sync, margin dashboards) would have had no data to work with.

---

## Fix

Created `PricingInitializer`, a new `@Component` in the `:pricing` module.

**File:** `/Users/dennyjoy/git/auto-shipper-ai/modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt`

Key design decisions in the fix:

### 1. Event listener binding

```kotlin
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun onSkuStateChanged(event: SkuStateChanged) {
    if (event.toState != "LISTED") return
    // ...
}
```

The listener uses `@TransactionalEventListener(phase = AFTER_COMMIT)` to ensure it only fires after the catalog module's transaction (which transitions the SKU to LISTED) has committed. This prevents the pricing initialization from seeing uncommitted or rolled-back state.

### 2. Transaction propagation subtlety

The method is annotated with `@Transactional(propagation = Propagation.REQUIRES_NEW)`. This was necessary because `AFTER_COMMIT` listeners execute outside the original transaction boundary. Without `REQUIRES_NEW`, the pricing writes (to `SkuPriceEntity` and `SkuPricingHistoryEntity`) had no active transaction and were silently not persisted. This was a secondary bug discovered during the fix.

### 3. Idempotency guard

```kotlin
if (skuPriceRepository.findBySkuId(skuId.value) != null) {
    log.info("Price record already exists for SKU {}; skipping initialization", skuId)
    return
}
```

The listener checks whether a `SkuPriceEntity` already exists before creating one. This makes the handler safe against duplicate event delivery or manual re-triggers.

### 4. Data sourcing

The initializer reads `StressTestResultEntity` (for `estimatedPriceAmount` and `stressedTotalCostAmount`) and `CostEnvelopeEntity` from the catalog module's repositories. It computes margin as `(price - cost) / price * 100` and delegates to `PricingEngine.setInitialPrice()`, which persists the `SkuPriceEntity` and an initial `SkuPricingHistoryEntity` with signal type `"INITIAL"`.

---

## Verification

After the fix, the full lifecycle flow was re-tested:

1. SKU transitioned through `CostGating -> StressTesting -> Listed`.
2. `PricingInitializer.onSkuStateChanged()` fired on the `SkuStateChanged` event.
3. `GET /api/skus/{id}/pricing` returned HTTP 200 with `price=$199.99, margin=72.96%`.

---

## Lessons Learned

1. **Event-driven integration requires explicit wiring.** In a modular monolith using Spring's `ApplicationEventPublisher`, publishing an event is only half the contract. A consumer must exist and be tested end-to-end. The `SkuStateChanged` event was published correctly by `SkuService.transition()`, but no integration test verified that a `LISTED` transition resulted in a pricing record.

2. **`@TransactionalEventListener(AFTER_COMMIT)` runs outside a transaction.** This is a well-known Spring subtlety, but it is easy to miss. Any writes performed inside an `AFTER_COMMIT` listener require their own transaction, typically via `Propagation.REQUIRES_NEW`. Without it, JPA operations appear to succeed (no exceptions) but are never flushed to the database.

3. **Dead code is a signal.** `PricingEngine.setInitialPrice()` was fully implemented but had zero callers. This should have been caught in code review or by a static analysis rule flagging unused public methods.

4. **Cross-module integration tests are essential.** Unit tests for `StressTestService` verified the state transition. Unit tests for `PricingEngine` verified `setInitialPrice()` persisted correctly. But no test verified the full chain: stress test pass -> state transition -> event published -> pricing initialized -> API returns 200.

---

## Action Items

- [ ] Add an integration test that exercises the full `StressTesting -> Listed -> GET /pricing` flow and asserts HTTP 200 with valid pricing data.
- [ ] Add an integration test that verifies idempotency: calling the listener twice for the same SKU does not create duplicate `SkuPriceEntity` records.
- [ ] Consider adding a startup health check or scheduled audit that flags `LISTED` SKUs with no corresponding `SkuPriceEntity`.
- [ ] Evaluate static analysis tooling (e.g., detekt unused-code rules) to flag public methods with zero callers within the codebase.
