# PM-003: Phantom Refunds, Inflated Violations, Missing Payment Intent, and Unguarded Order Transitions

**Date:** 2026-03-06
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

Five bugs in the FR-008 fulfillment orchestration module were caught across three rounds of automated PR review on PR #10 before merge to `main`. Round 1 found (1) $0 refunds with hardcoded USD currency and (2) inflated vendor violation counts from double-counting. Round 2 found (3) the Stripe refund API call missing the required `payment_intent` parameter and (4) no state transition guards on Order status changes. Round 3 found (5) a parameter injection vulnerability in the Stripe refund request body. All five were fixed, verified with 44 passing tests, and pushed.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-008 fulfillment orchestration implemented across domain, service, proxy, handler, and test layers (23 source files, 7 test files, 41 tests passing) |
| Implementation complete | Full build passes, PR #10 pushed to `feat/FR-008-fulfillment-orchestration` |
| PR review round 1 | Automated reviewer (unblocked[bot]) flagged two issues on PR #10 |
| Bug 1 identified | `VendorSlaBreachRefunder.kt:44` — `Money.of(BigDecimal.ZERO, Currency.USD)` used as refund amount |
| Bug 2 identified | `FulfillmentDataProviderImpl.kt:41` — separate delay + refund count queries cause double-counting |
| Round 1 fix applied | Added `totalAmount`/`totalCurrency` to `Order` entity; replaced two violation count queries with single OR query |
| Round 1 verified | All 41 tests updated and passing, full project build green |
| Round 1 pushed | Bugfix commit `322e0ff` pushed to branch |
| PR review round 2 | Automated reviewer flagged two additional issues on PR #10 |
| Bug 3 identified | `StripeRefundAdapter.kt:41` — Stripe `POST /v1/refunds` missing required `payment_intent` parameter |
| Bug 4 identified | `OrderService.kt:67-74` — `routeToVendor`, `markShipped`, `markDelivered` accept any order status without guards |
| Round 2 fix applied | Added `paymentIntentId` to Order and refund flow; added `VALID_TRANSITIONS` map and `require()` guards |
| Round 2 verified | All 44 tests passing (3 new), full project build green |
| Round 2 pushed | Bugfix commit `f56c79e` pushed to branch |
| PR review round 3 | Automated reviewer flagged one additional issue on PR #10 |
| Bug 5 identified | `StripeRefundAdapter.kt:41` — `paymentIntentId` and `orderId` interpolated into form-encoded body without URL-encoding, enabling parameter injection |
| Round 3 fix applied | URL-encode all user-supplied values before interpolation into form body |

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

### Bug 3: Missing Payment Intent in Stripe Refund
`StripeRefundAdapter.refund()` sent a request to Stripe's `POST /v1/refunds` endpoint without the required `payment_intent` (or `charge`) parameter:

```kotlin
.body("amount=$amountInCents&currency=${amount.currency.name.lowercase()}&metadata[order_id]=$orderId")
```

The Stripe Refunds API requires either a `charge` or `payment_intent` parameter to identify what is being refunded. Without it, every refund call would return a 400 error in production, silently failing inside the `try/catch` in `VendorSlaBreachRefunder` and leaving affected orders un-refunded despite the SLA breach.

### Bug 4: Unguarded Order State Transitions
`OrderService.routeToVendor()`, `markShipped()`, `markDelivered()`, and `ShipmentTracker.pollAllShipments()` all documented expected precondition statuses but never verified them:

```kotlin
// Documented as "Routes a PENDING order" but accepts any status
fun routeToVendor(orderId: UUID): Order {
    val order = orderRepository.findById(orderId).orElseThrow { ... }
    order.updateStatus(OrderStatus.CONFIRMED) // No guard — works on DELIVERED, REFUNDED, etc.
    ...
}
```

A DELIVERED or REFUNDED order could be re-routed, re-shipped, or re-delivered without error. This contrasts with the catalog module's `SkuStateMachine` which enforces explicit transition maps. In production, concurrent events (e.g., a carrier webhook marking an order DELIVERED while the SLA breach refunder is processing the same order) could cause nonsensical state like REFUNDED → CONFIRMED.

### Bug 5: Parameter Injection in Stripe Refund Body
`StripeRefundAdapter` interpolated `paymentIntentId` (originating from user input via `CreateOrderRequest`) directly into the `application/x-www-form-urlencoded` body without URL-encoding:

```kotlin
.body("payment_intent=$paymentIntentId&amount=$amountInCents&currency=...&metadata[order_id]=$orderId")
```

A crafted value like `pi_test&amount=0&charge=ch_other` would inject extra form parameters, potentially overriding the refund amount or redirecting the refund to a different Stripe charge.

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

### 5 Whys — Bug 3

1. **Why** was `payment_intent` missing from the Stripe refund call? → The `StripeRefundAdapter` was written without it — the request body only included `amount`, `currency`, and `metadata[order_id]`.
2. **Why** didn't the Order carry a payment intent ID? → The `Order` entity was designed for the fulfillment domain (routing, tracking, delivery) and didn't model the payment relationship.
3. **Why** wasn't the payment relationship modeled? → The FR-008 spec treated payment capture as an upstream concern (handled before order creation), but the refund path requires referencing the original payment.
4. **Why** wasn't this caught when Bug 1 was fixed? → The Bug 1 fix focused on getting the correct *amount* onto the Order and threading it through the refund call, but didn't trace what other parameters Stripe's API requires.
5. **Why** didn't tests catch it? → Tests mocked `RefundProvider` at the interface level and never exercised the actual HTTP request body construction in `StripeRefundAdapter`.

### 5 Whys — Bug 4

1. **Why** were order transitions unguarded? → `Order.updateStatus()` was a simple setter with no validation — it accepted any `OrderStatus` regardless of current state.
2. **Why** was there no transition map? → The Order aggregate was modeled as a data-carrying entity rather than a state machine, unlike the catalog module's `Sku` which uses `SkuStateMachine`.
3. **Why** wasn't the catalog pattern reused? → The fulfillment module was implemented in a separate FR without referencing the catalog module's state machine as a template. No architectural guideline mandated consistent state transition enforcement across aggregates.
4. **Why** didn't the service layer guards catch it? → The `OrderService` methods documented their expected preconditions in comments (e.g., "Routes a PENDING order") but never enforced them with `require()` checks.
5. **Why** didn't tests catch it? → Tests always set up orders in the correct precondition state (e.g., PENDING before routing, CONFIRMED before shipping) — no test attempted an invalid transition.

## Fix Applied

### Bug 1: Real payment amount on Order (Round 1 — `322e0ff`)

Added `totalAmount: BigDecimal` and `totalCurrency: Currency` fields to the `Order` entity, threaded through `CreateOrderCommand`, `CreateOrderRequest` DTO, `OrderResponse` DTO, and `OrderController`. The `VendorSlaBreachRefunder` now uses `order.totalAmount()` with a guard:

```kotlin
val refundAmount = order.totalAmount()
require(refundAmount.normalizedAmount > BigDecimal.ZERO) {
    "Order ${order.id} has zero total amount — cannot issue refund"
}
```

### Bug 2: Single OR query for violations (Round 1 — `322e0ff`)

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

### Bug 3: Payment intent ID on Order and Stripe refund call (Round 2 — `f56c79e`)

Added `paymentIntentId: String` field to the `Order` entity (captured at order creation time from the upstream payment flow). Updated `RefundProvider` interface to accept `paymentIntentId`, and `StripeRefundAdapter` now includes it in the Stripe API request:

```kotlin
.body("payment_intent=$paymentIntentId&amount=$amountInCents&currency=${amount.currency.name.lowercase()}&metadata[order_id]=$orderId")
```

`VendorSlaBreachRefunder` passes `order.paymentIntentId` through to the refund call.

### Bug 4: Order state transition map and service-level guards (Round 2 — `f56c79e`)

Added a `VALID_TRANSITIONS` companion object to `Order`, mirroring the catalog module's `SkuStateMachine` pattern:

```kotlin
companion object {
    private val VALID_TRANSITIONS: Map<OrderStatus, Set<OrderStatus>> = mapOf(
        OrderStatus.PENDING to setOf(OrderStatus.CONFIRMED, OrderStatus.REFUNDED),
        OrderStatus.CONFIRMED to setOf(OrderStatus.SHIPPED, OrderStatus.REFUNDED),
        OrderStatus.SHIPPED to setOf(OrderStatus.DELIVERED, OrderStatus.REFUNDED),
        OrderStatus.DELIVERED to setOf(OrderStatus.RETURNED, OrderStatus.REFUNDED),
        OrderStatus.REFUNDED to emptySet(),
        OrderStatus.RETURNED to emptySet()
    )
}

fun updateStatus(newStatus: OrderStatus) {
    val allowed = VALID_TRANSITIONS[status]
        ?: error("No transitions defined from status $status")
    require(newStatus in allowed) {
        "Invalid order transition: $status → $newStatus (allowed: $allowed)"
    }
    status = newStatus
    updatedAt = Instant.now()
}
```

Added `require()` guards in `OrderService.routeToVendor` (expects PENDING), `markShipped` (expects CONFIRMED), and `markDelivered` (expects SHIPPED). `ShipmentTracker.pollAllShipments` checks `order.status == OrderStatus.SHIPPED` before transitioning to DELIVERED.

### Files Changed

**Round 1 (`322e0ff`):**
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

**Round 2 (`f56c79e`):**
- `modules/fulfillment/.../domain/Order.kt` — Added `paymentIntentId` field, `VALID_TRANSITIONS` map, and transition validation in `updateStatus()`
- `modules/fulfillment/.../domain/service/CreateOrderCommand.kt` — Added `paymentIntentId` field
- `modules/fulfillment/.../domain/service/OrderService.kt` — Added `require()` status guards in `routeToVendor`, `markShipped`, `markDelivered`
- `modules/fulfillment/.../domain/service/ShipmentTracker.kt` — Added status check before DELIVERED transition
- `modules/fulfillment/.../domain/service/VendorSlaBreachRefunder.kt` — Passes `order.paymentIntentId` to `refundProvider.refund()`
- `modules/fulfillment/.../handler/OrderController.kt` — Threads `paymentIntentId` from request to command
- `modules/fulfillment/.../handler/dto/CreateOrderRequest.kt` — Added `paymentIntentId` field
- `modules/fulfillment/.../proxy/payment/StripeRefundAdapter.kt` — Added `paymentIntentId` to `RefundProvider` interface and Stripe request body
- `modules/app/.../db/migration/V12__orders_payment_intent_id.sql` — Adds `payment_intent_id` column to `orders` table
- 6 test files updated with `paymentIntentId`; 3 new tests for invalid state transitions (44 total)

## Impact

All four bugs were caught during automated PR review before merge to `main`. No production impact.

**Potential production impact if merged:**
- **Bug 1:** Every SLA breach refund would fail at Stripe (rejecting $0 amount) or silently issue a $0 refund. Customers would be notified of a refund they never received. Order status would show REFUNDED while no actual money was returned. Trust-destroying for the "customer trust is a balance sheet asset" mandate.
- **Bug 2:** Vendor violation rates would be systematically inflated. A vendor with 5 delayed-then-refunded orders out of 100 fulfillments would show a 10% violation rate instead of the actual 5%, crossing the breach threshold and triggering cascading suspensions and refunds — which would further inflate violation counts in a feedback loop reminiscent of PM-002.
- **Bug 3:** Every Stripe refund call would return HTTP 400 ("Missing required param: payment_intent or charge"). The `try/catch` in `VendorSlaBreachRefunder` would silently swallow the error, log it, and move on — leaving customers un-refunded while the system believes it attempted the refund. Combined with Bug 1's fix (which added the `require(amount > 0)` guard), refunds would fail at Stripe instead of at the guard, making the failure less visible.
- **Bug 4:** Concurrent operations could corrupt order state. A carrier webhook marking an order DELIVERED while the SLA breach refunder processes the same order could produce REFUNDED → CONFIRMED or DELIVERED → SHIPPED. In a system where order status drives financial operations (refunds, vendor scoring, reserve calculations), invalid state transitions would cascade into incorrect financial data.

## Lessons Learned

### What went well
- Automated PR review caught all four bugs across two review rounds before merge — the investment in code review tooling paid off twice
- The bugs were straightforward to fix once identified — the domain model was clean enough to extend
- The existing test infrastructure made it easy to verify fixes (41 → 44 tests, all passing)
- The second review round caught issues the first round's fix introduced (the `payment_intent` gap only became relevant after `StripeRefundAdapter` was actually wired to real order amounts)

### What could be improved
- Sub-agent code generation accepted a `TODO`-style placeholder (`Money.of(BigDecimal.ZERO, Currency.USD)`) without flagging it as incomplete — placeholder values in business-critical paths should be treated as compilation errors
- The spec-to-implementation gap on "what data does the Order need to carry for downstream operations" wasn't caught during planning — the implementation plan should trace data dependencies backward from every consumer (refund needs amount + payment intent, notification needs customer details, etc.)
- Unit tests used `any<Money>()` matchers instead of asserting actual refund amounts, masking the zero-value bug
- No test scenario covered the delay-then-refund overlap path
- The `StripeRefundAdapter` was never reviewed against the actual Stripe API contract — the implementation assumed `amount` + `currency` was sufficient without checking the API docs
- The Order aggregate was built without a transition map despite the catalog module already establishing the `SkuStateMachine` pattern — no architectural guideline required consistent state machine enforcement across aggregates
- No negative test existed for invalid order transitions (e.g., attempting DELIVERED → CONFIRMED)

## Prevention

- [x] Add `require(amount > 0)` guard in `VendorSlaBreachRefunder` before issuing refunds
- [x] Replace double-count violation queries with single OR query in `OrderRepository`
- [x] Add `paymentIntentId` to `Order` entity and thread through to Stripe refund call
- [x] Add `VALID_TRANSITIONS` map to `Order.updateStatus()` enforcing valid state transitions
- [x] Add `require()` status guards in `OrderService` methods (`routeToVendor`, `markShipped`, `markDelivered`)
- [x] Add 3 negative tests for invalid order state transitions
- [ ] Add a lint rule or code review checklist item: no `BigDecimal.ZERO` or placeholder `Money` values in production service code — flag as `TODO` or `require()` guard
- [ ] For future FRs, trace data dependencies backward from every consumer during Phase 3 planning: if a service needs data, the aggregate must carry it
- [ ] Review all external API adapter implementations against their actual API contracts (Stripe, carrier APIs) — verify required parameters are present
- [ ] Establish an architectural guideline: all domain aggregates with lifecycle states must use an explicit transition map (following the `SkuStateMachine` / `Order.VALID_TRANSITIONS` pattern)
- [ ] Consider adding a `@NonZero` or similar validation annotation for Money fields used in payment operations
- [ ] In refund-related tests, assert exact amounts rather than using `any<Money>()` matchers — verify the refund amount matches the order's payment amount
- [ ] All external API adapters that build form-encoded or URL-interpolated request bodies must URL-encode user-supplied values — add this as a standard for proxy-layer code and consider a shared utility or linter rule to catch raw string interpolation in HTTP body construction
