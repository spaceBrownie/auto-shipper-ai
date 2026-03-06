# PM-001: Pricing 404 on Listed SKUs

**Date:** 2026-03-04
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

After a SKU successfully passed stress testing and transitioned to LISTED state, `GET /api/skus/{id}/pricing` returned 404 because no pricing record was ever created. The root cause was a missing bridge between the catalog module's SKU state machine and the pricing module: `PricingEngine.setInitialPrice()` existed but nothing called it when a SKU became listed. The fix introduced a `PricingInitializer` component that listens for `SkuStateChanged` events and initializes pricing automatically.

## Timeline

| Time | Event |
|------|-------|
| Session start | During FR-015 endpoint testing, a SKU was created and advanced through the full lifecycle: Ideation -> ValidationPending -> CostGating -> StressTesting -> Listed |
| Shortly after | Stress test passed, SKU transitioned to LISTED state successfully |
| Shortly after | `GET /api/skus/{id}/pricing` returned **404 Not Found** despite the SKU being in LISTED state |
| Investigation | Traced the flow from `StressTestService.run()` through `skuService.transition()` to the `SkuStateChanged` event — discovered no listener existed to bridge the event to the pricing module |
| Root cause identified | `PricingEngine.setInitialPrice()` existed but was never invoked from any code path. No event listener or service call connected the SKU-listed transition to pricing initialization |
| Fix applied | Created `PricingInitializer` component in the `:pricing` module with `@TransactionalEventListener` for `SkuStateChanged` events |
| Second issue | Initial implementation used `@TransactionalEventListener` alone, but pricing records were not persisting |
| Second fix | Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` because `AFTER_COMMIT` listeners run outside the original transaction boundary |
| Verified | `GET /api/skus/{id}/pricing` returned **200 OK** with `price=$199.99, margin=72.96%` |

## Symptom

After a SKU successfully passed stress testing and was transitioned to LISTED state, the pricing endpoint returned a 404:

```
GET /api/skus/{id}/pricing -> 404 Not Found
```

The SKU entity itself was correctly in LISTED state, the stress test results were persisted, and the cost envelope existed — but no `SkuPriceEntity` record was ever written to the database. The pricing module had no data for the SKU.

## Root Cause

The failure was a **missing integration point** between two bounded contexts: `:catalog` and `:pricing`.

**The chain of events that should have happened:**

1. `StressTestService.run()` in `:catalog` calls `skuService.transition(skuId, SkuState.Listed)` (line 122 of `StressTestService.kt`)
2. `SkuService.transition()` publishes a `SkuStateChanged` domain event via Spring's `ApplicationEventPublisher`
3. *Something* in the `:pricing` module should listen for that event and call `PricingEngine.setInitialPrice()`

**What actually happened:** Step 3 did not exist. `PricingEngine.setInitialPrice()` was implemented (line 136 of `PricingEngine.kt`) and fully functional — it creates a `SkuPriceEntity`, persists it, and records pricing history. But no code path ever invoked it. The method was dead code.

**Why this gap existed:** The catalog and pricing modules were built in separate feature requests (FR-004/FR-005 for catalog, FR-006 for pricing). The pricing module implemented the `PricingEngine` with `setInitialPrice()` and the `onPricingSignal()` event listener for dynamic price adjustments, but the initial price bootstrapping — the bridge from "SKU just became listed" to "create the first price record" — was never wired up. The pricing module assumed something would call `setInitialPrice()`, and the catalog module assumed the pricing module would handle its own initialization. Neither side owned the integration.

**Why it went deeper:** Applying the 5 whys:
1. Why did pricing return 404? No `SkuPriceEntity` existed.
2. Why was no entity created? `setInitialPrice()` was never called.
3. Why was it never called? No event listener bridged `SkuStateChanged` to the pricing module.
4. Why was no listener written? The two modules were implemented in separate feature requests without an explicit integration contract.
5. Why was there no integration contract? The system's event-driven architecture relies on convention (listeners for domain events) rather than compile-time enforcement of cross-module reactions.

## Fix Applied

Created `PricingInitializer`, a new Spring `@Component` in the `:pricing` module that listens for `SkuStateChanged` events and bootstraps pricing when a SKU enters LISTED state.

Key design decisions in the fix:

1. **`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`** — ensures the listener only fires after the catalog transaction that listed the SKU has committed, so the stress test results and cost envelope are guaranteed to be readable.

2. **`@Transactional(propagation = Propagation.REQUIRES_NEW)`** — required because `AFTER_COMMIT` listeners run outside the original transaction. Without this, JPA operations in the listener silently fail to persist. This was a second bug discovered during the fix.

3. **Idempotency guard** — checks `skuPriceRepository.findBySkuId()` before creating, so replayed events or duplicate deliveries do not create duplicate price records.

4. **Data sourcing** — reads `StressTestResultEntity` for the estimated price and stressed total cost, then computes margin. This avoids coupling to the catalog module's internal domain types (`CostEnvelope.Verified` has an `internal` constructor).

### Files Changed
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt` — **New file.** `@Component` with `@TransactionalEventListener` for `SkuStateChanged`. Filters for `toState == "LISTED"`, reads stress test result and cost envelope from their repositories, computes margin, and delegates to `PricingEngine.setInitialPrice()`.

## Impact

- **Severity: High** — This was a core feature broken with no workaround. Any SKU that passed stress testing and became LISTED would have no pricing data, making the pricing endpoint non-functional. The dynamic pricing engine (`onPricingSignal`) would also silently discard all signals for these SKUs since it early-returns when no `SkuPriceEntity` exists.
- **Blast radius:** Every listed SKU was affected. No pricing data meant no margin tracking, no dynamic price adjustments, and no automated shutdown triggers from the pricing module.
- **Data impact:** No corruption. The fix is additive — it creates records that were previously missing.
- **Caught in:** Local development during FR-015 endpoint testing, before any production deployment.

## Lessons Learned

### What went well
- The endpoint test during FR-015 caught the issue immediately — the structured lifecycle test (create SKU -> advance through states -> verify endpoints) was effective at surfacing integration gaps
- The `setInitialPrice()` method was already well-implemented; only the wiring was missing
- The idempotency guard was included from the start, anticipating event replay scenarios
- The second issue (AFTER_COMMIT transaction boundary) was identified quickly by observing that the listener fired but no data persisted

### What could be improved
- Cross-module integration points were not explicitly tracked during feature request planning. FR-006 (pricing engine) should have included a task for "wire initial price creation to SKU lifecycle event"
- There is no automated test that verifies the full lifecycle from stress-test-pass through to pricing-record-creation. Unit tests for each module passed individually, but the integration seam was untested
- The `AFTER_COMMIT` + `REQUIRES_NEW` pattern is a known Spring pitfall but was not documented anywhere in the project as a convention to follow

## Prevention

- [ ] Add an integration test that advances a SKU through the full lifecycle (Ideation -> Listed) and asserts that a `SkuPriceEntity` exists afterward
- [ ] Create a cross-module integration checklist in the feature request workflow: when a module publishes a domain event, explicitly document which modules must react and how
- [ ] Document the `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` pattern as a project convention in `CLAUDE.md` or an architecture decision record, so future event listeners do not repeat the silent-non-persistence bug
- [ ] Consider adding a startup validation that scans for SKUs in LISTED state without a corresponding `SkuPriceEntity` and logs warnings — a safety net for this class of "missing bridge" bugs
- [ ] Audit existing domain events (`SkuStateChanged`, `PricingDecision`, etc.) for other missing listeners that should exist but do not
