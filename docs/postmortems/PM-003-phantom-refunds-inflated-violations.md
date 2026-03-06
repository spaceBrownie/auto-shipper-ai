# PM-003: Phantom Refunds and Inflated Violation Counts

**Date:** 2026-03-06
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

Two related bugs in the FR-008 fulfillment orchestration module would have caused (1) $0 refunds with hardcoded USD currency sent to Stripe on every vendor SLA breach, silently marking orders as "refunded" without actually refunding customers, and (2) inflated vendor violation counts from double-counting orders that were both delayed and refunded, leading to premature vendor suspensions. Both were caught by automated PR review before merging.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-008 fulfillment orchestration implemented across domain, service, proxy, handler, and test layers (23 source files, 7 test files, 41 tests passing) |
| Implementation complete | Full build passes, PR #10 pushed to `feat/FR-008-fulfillment-orchestration` |
| PR review | Automated reviewer (unblocked[bot]) flagged two issues on PR #10 |
| Bug 1 identified | `VendorSlaBreachRefunder.kt:44` — `Money.of(BigDecimal.ZERO, Currency.USD)` used as refund amount |
| Bug 2 identified | `FulfillmentDataProviderImpl.kt:41` — separate delay + refund count queries cause double-counting |
| Assessment | Both bugs confirmed as spec violations via code review against FR-008 spec and solo operator spec |
| Fix applied | Added `totalAmount`/`totalCurrency` to `Order` entity; replaced two violation count queries with single OR query |
| Verified | All 41 tests updated and passing, full project build green |
| Pushed | Bugfix commit `322e0ff` pushed to branch |

## Symptom

### Bug 1: Phantom $0 Refunds
The `VendorSlaBreachRefunder` issued refunds to Stripe with `Money.of(BigDecimal.ZERO, Currency.USD)` for every SLA breach:

```kotlin
val refundAmount = Money.of(BigDecimal.ZERO, Currency.USD)
val result = refundProvider.refund(orderId = order.id, amount = refundAmount, ...)
order.updateStatus(OrderStatus.REFUNDED)
```

In production, Stripe's API requires `amount > 0` — this would either error out (breaking the refund flow) or issue a meaningless $0 refund. Meanwhile, the order was marked `REFUNDED` and the customer notified they'd been refunded, creating a silent data-correctness bug. The currency was also hardcoded to USD regardless of the order's actual currency.

### Bug 2: Inflated Violation Counts
`FulfillmentDataProviderImpl.countViolationsSince()` summed two separate queries:

```kotlin
val delayCount = orderRepository.countBy...DelayDetected(vendorId, true, since)
val refundedCount = orderRepository.countBy...Status(vendorId, OrderStatus.REFUNDED, since)
return delayCount + refundedCount
```

An order that was delayed and subsequently auto-refunded would have `delayDetected=true` AND `status=REFUNDED`, counting it in both queries. This inflated the violation rate fed to `VendorSlaMonitor.runCheck()`, which computes `violations / totalFulfillments * 100` against a 10% breach threshold.

## Root Cause

### 5 Whys — Bug 1

1. **Why** was the refund amount $0? → The `VendorSlaBreachRefunder` used a placeholder `Money.of(BigDecimal.ZERO, Currency.USD)` with a comment "in production this would come from the order's payment record."
2. **Why** was there no payment amount on the order? → The `Order` entity had no `totalAmount` field — it was omitted from the original domain model.
3. **Why** was it omitted? → The FR-008 spec and implementation plan specified order creation and routing but did not explicitly list "payment amount" as a field on the Order aggregate.
4. **Why** didn't the spec cover it? → The spec focused on fulfillment flow (routing, tracking, delivery) and treated payment as an upstream concern, but the refund path requires knowing what was paid.
5. **Why** wasn't this caught by tests? → Tests mocked `RefundProvider` and verified it was called with `any<Money>()` — they didn't assert the actual amount value.

### 5 Whys — Bug 2

1. **Why** were violations double-counted? → Two separate COUNT queries were summed: one for `delayDetected=true`, one for `status=REFUNDED`.
2. **Why** were they separate queries? → Spring Data JPA's derived query methods can only express single-condition filters; an OR condition requires a custom `@Query`.
3. **Why** wasn't a custom query used initially? → The implementation followed the convention of derived query methods used elsewhere in the codebase (e.g., `countByVendorIdAndStatusAndCreatedAtGreaterThanEqual`).
4. **Why** didn't the overlap get noticed? → The relationship between delay detection and refund status wasn't modeled as overlapping — the `ShipmentTracker` sets `delayDetected` and `VendorSlaBreachRefunder` sets `status=REFUNDED`, but no analysis traced the state where both apply simultaneously.
5. **Why** didn't tests catch it? → Unit tests mocked the repository and returned independent counts (5 delays + 3 refunds = 8), never simulating the overlap scenario.

## Fix Applied

### Bug 1: Real payment amount on Order

Added `totalAmount: BigDecimal` and `totalCurrency: Currency` fields to the `Order` entity, threaded through `CreateOrderCommand`, `CreateOrderRequest` DTO, `OrderResponse` DTO, and `OrderController`. The `VendorSlaBreachRefunder` now uses `order.totalAmount()` with a guard:

```kotlin
val refundAmount = order.totalAmount()
require(refundAmount.normalizedAmount > BigDecimal.ZERO) {
    "Order ${order.id} has zero total amount — cannot issue refund"
}
```

### Bug 2: Single OR query for violations

Replaced the two separate count queries with a single JPQL query:

```kotlin
@Query("""
    SELECT COUNT(o) FROM Order o
    WHERE o.vendorId = :vendorId
      AND o.createdAt >= :since
      AND (o.shipmentDetails.delayDetected = true OR o.status = 'REFUNDED')
""")
fun countViolations(@Param("vendorId") vendorId: UUID, @Param("since") since: Instant): Long
```

### Files Changed
- `modules/fulfillment/.../domain/Order.kt` — Added `totalAmount`, `totalCurrency` fields and `totalAmount(): Money` accessor
- `modules/fulfillment/.../domain/service/CreateOrderCommand.kt` — Added `totalAmount: Money` field
- `modules/fulfillment/.../domain/service/VendorSlaBreachRefunder.kt` — Uses `order.totalAmount()` with `require(amount > 0)` guard
- `modules/fulfillment/.../handler/OrderController.kt` — Maps `totalAmount`/`totalCurrency` through DTOs
- `modules/fulfillment/.../handler/dto/CreateOrderRequest.kt` — Added `totalAmount`, `totalCurrency` fields
- `modules/fulfillment/.../handler/dto/OrderResponse.kt` — Added `totalAmount`, `totalCurrency` fields
- `modules/fulfillment/.../persistence/OrderRepository.kt` — Added `countViolations` JPQL query with OR clause
- `modules/fulfillment/.../persistence/FulfillmentDataProviderImpl.kt` — `countViolationsSince` now delegates to single `countViolations` query
- `modules/app/.../db/migration/V11__orders_total_amount.sql` — Adds `total_amount` and `total_currency` columns to `orders` table
- 7 test files updated with `totalAmount`/`totalCurrency` in Order construction and updated violation count assertions

## Impact

Both bugs were caught during automated PR review before merge to `main`. No production impact.

**Potential production impact if merged:**
- **Bug 1:** Every SLA breach refund would fail at Stripe (rejecting $0 amount) or silently issue a $0 refund. Customers would be notified of a refund they never received. Order status would show REFUNDED while no actual money was returned. Trust-destroying for the "customer trust is a balance sheet asset" mandate.
- **Bug 2:** Vendor violation rates would be systematically inflated. A vendor with 5 delayed-then-refunded orders out of 100 fulfillments would show a 10% violation rate instead of the actual 5%, crossing the breach threshold and triggering cascading suspensions and refunds — which would further inflate violation counts in a feedback loop reminiscent of PM-002.

## Lessons Learned

### What went well
- Automated PR review caught both bugs before merge — the investment in code review tooling paid off
- The bugs were straightforward to fix once identified — the domain model was clean enough to extend
- The existing test infrastructure made it easy to verify the fixes (all 41 tests updated and passing)

### What could be improved
- Sub-agent code generation accepted a `TODO`-style placeholder (`Money.of(BigDecimal.ZERO, Currency.USD)`) without flagging it as incomplete — placeholder values in business-critical paths should be treated as compilation errors
- The spec-to-implementation gap on "what data does the Order need to carry for downstream operations" wasn't caught during planning — the implementation plan should trace data dependencies backward from every consumer (refund needs amount, notification needs customer details, etc.)
- Unit tests used `any<Money>()` matchers instead of asserting actual refund amounts, masking the zero-value bug
- No test scenario covered the delay-then-refund overlap path

## Prevention

- [ ] Add a lint rule or code review checklist item: no `BigDecimal.ZERO` or placeholder `Money` values in production service code — flag as `TODO` or `require()` guard
- [ ] For future FRs, trace data dependencies backward from every consumer during Phase 3 planning: if a service needs data, the aggregate must carry it
- [ ] Add a test case for the delay-then-refund overlap scenario in `FulfillmentDataProviderImplTest` to verify no double-counting (regression test)
- [ ] Consider adding a `@NonZero` or similar validation annotation for Money fields used in payment operations
- [ ] In refund-related tests, assert exact amounts rather than using `any<Money>()` matchers — verify the refund amount matches the order's payment amount
