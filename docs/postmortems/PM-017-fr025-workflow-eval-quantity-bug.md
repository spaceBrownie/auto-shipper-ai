# PM-017: FR-025 Workflow Evaluation — Hardcoded Quantity and Resilience4j Bypass Shipped Past 6-Phase Workflow

**Date:** 2026-03-27
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

The feature-request-v2 workflow's second production run (FR-025: CJ supplier order placement) shipped two bugs past all 6 phases: (1) `quantity = 1` hardcoded in the supplier order listener, causing the system to order 1 unit from CJ regardless of what the customer paid for, and (2) a `try-catch(RestClientException)` in `CjOrderAdapter` that silently swallowed network errors, preventing Resilience4j `@Retry` and `@CircuitBreaker` from ever firing. Both were caught by Unblocked's automated PR review in Phase 6 — the workflow's last line of defense. This post-mortem examines why the 6-phase workflow failed to catch these bugs earlier and identifies structural gaps in Phase 1 (Discovery), Phase 3 (Planning), and Phase 4 (Test-First Gate).

## Timeline

| Time | Event |
|------|-------|
| Session start | User requests RAT-27 pull from Linear + feature-request-v2 workflow |
| Phase 1 (~4m 38s) | Discovery agent explores codebase. Identifies 3 gaps: missing shipping address, missing FAILED status, missing CJ vid mapping. Does NOT identify that `Order` lacks a `quantity` field. |
| Phase 2 (~3m 20s) | Spec written with 8 business requirements. BR-3 mentions "shipping address capture" but no BR mentions quantity propagation. |
| Phase 3 (~4m 49s) | Implementation plan written. `SupplierOrderRequest` includes `quantity: Int` but the plan never specifies where quantity comes from. The listener pseudocode says "Build SupplierOrderRequest (address, vid, quantity, orderNumber)" without resolving the source. |
| Phase 4 (~9m 11s) | 25 tests generated. Tests use hardcoded `quantity = 2` in assertions but no test verifies that quantity flows from Shopify line item → Order → CJ request. |
| Phase 5 (~16m 3s) | Implementation agent builds all production code. Encounters the gap: `Order` has no `quantity` field. Defaults to `quantity = 1`. Also adds `try-catch(RestClientException)` in `CjOrderAdapter` — contradicting the Resilience4j annotations on the same method. |
| Phase 5 end | `./gradlew test` passes. All 25 Phase 4 tests green. |
| Phase 6 start | PR #37 created. CI green (2m43s). Unblocked auto-approves. |
| Phase 6 + user review | User requests Unblocked re-review of order logic. Unblocked identifies both bugs. |
| Fix applied | Quantity column added to Order + propagated through CreateOrderCommand + LineItemOrderCreator. try-catch moved from adapter to listener. |
| Fix verified | `./gradlew test` green. CI green. No new review comments. |

## Symptom

Two bugs in production code that passed all automated tests:

**Bug 1 — Hardcoded quantity:**
```kotlin
// SupplierOrderPlacementListener.kt line 94 (before fix)
val request = SupplierOrderRequest(
    ...
    supplierVariantId = mapping.supplierVariantId,
    quantity = 1  // Customer orders 3, CJ ships 1
)
```

Customer orders 3 units at $29.99 each → internal order has `totalAmount = $89.97` → CJ receives order for 1 unit → customer paid for 3 but receives 1. Revenue loss and customer complaint guaranteed.

**Bug 2 — Resilience4j bypass:**
```kotlin
// CjOrderAdapter.kt (before fix)
@CircuitBreaker(name = "cj-order")
@Retry(name = "cj-order")
override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
    return try {
        // ... HTTP call ...
    } catch (e: RestClientException) {
        // This catch PREVENTS @Retry and @CircuitBreaker from ever firing
        SupplierOrderResult.Failure(FailureReason.NETWORK_ERROR, e.message ?: "Network error")
    }
}
```

Resilience4j AOP operates at the method boundary — it only sees exceptions thrown *out of* the method. The internal catch returns a normal result, so every call looks successful to Resilience4j. Retries never fire. Circuit breaker never opens. The spec's NFR explicitly required: "Transient failures should trigger retries."

## Root Cause

### Bug 1: Hardcoded quantity — 5 Whys

1. **Why** was `quantity = 1` hardcoded? → The `Order` entity had no `quantity` field. The Phase 5 agent needed a value and defaulted to `1`.

2. **Why** didn't the `Order` entity have a `quantity` field? → The existing `LineItemOrderCreator` computes `unitPrice * quantity` into `totalAmount` and discards the individual quantity. This data modeling gap predates FR-025.

3. **Why** didn't Phase 1 (Discovery) flag this? → Discovery identified missing shipping address, missing FAILED status, and missing CJ vid mapping — all structural gaps in the `Order` entity. But it did not systematically audit `Order`'s fields against the CJ API's *input requirements*. The discovery was entity-centric ("what does Order have?") rather than contract-centric ("what does the CJ API need, and can we supply it?").

4. **Why** didn't Phase 3 (Planning) catch it? → The plan defined `SupplierOrderRequest` with `quantity: Int` and wrote "Build SupplierOrderRequest (address, vid, quantity, orderNumber)" in the listener pseudocode. But it never traced where `quantity` comes from. The plan described the interface contract but not the data flow.

5. **Why** didn't Phase 4 (Test-First) catch it? → Tests validated the *shape* of data (`request.quantity == 2`) but not the *source* of data. No test asserted: "given a Shopify line item with quantity=3, the CJ order request must have quantity=3." Phase 4 tests were contract tests (does the interface look right?) not data-flow tests (does the data flow correctly through the system?).

### Bug 2: Resilience4j bypass — 5 Whys

1. **Why** was there a `try-catch(RestClientException)` inside the adapter? → The Phase 5 agent followed a common pattern: "catch exceptions, return a result type." This is a valid pattern in general, but contradicts annotation-based resilience.

2. **Why** didn't Phase 3 flag the contradiction? → The plan specified both `@CircuitBreaker`/`@Retry` annotations AND the adapter's responsibility to "handle network errors." These are contradictory requirements that went unnoticed because they were in different sections of the plan (Layer 9 vs. Layer 4).

3. **Why** didn't Phase 4 catch it? → No test verified retry behavior. The `network timeout produces NETWORK_ERROR failure result` test validated the *result type* but not the *retry mechanism*. Testing Resilience4j behavior requires either integration tests with WireMock (simulating failures) or verifying that exceptions propagate.

4. **Why** didn't the existing codebase catch this by example? → Other adapters in the codebase (`StripeRefundAdapter`, `ShopifyInventoryCheckAdapter`) use `@CircuitBreaker`/`@Retry` but do NOT have internal try-catch blocks — the pattern was available but not followed.

5. **Why** was the contradiction not caught by any gate? → The workflow validates *structure* (do annotations exist? do interfaces match?) but not *behavioral correctness* (do these annotations actually work given the implementation?).

## Fix Applied

### Bug 1: Quantity propagation

Added `quantity` to the full data flow: Shopify line item → `CreateOrderCommand` → `Order` entity → `SupplierOrderPlacementListener` → `SupplierOrderRequest` → CJ API.

### Files Changed
- `modules/app/src/main/resources/db/migration/V22__order_quantity_column.sql` — `ALTER TABLE orders ADD COLUMN quantity INT NOT NULL DEFAULT 1`
- `modules/fulfillment/.../domain/Order.kt` — added `@Column(name = "quantity") var quantity: Int = 1`
- `modules/fulfillment/.../domain/service/CreateOrderCommand.kt` — added `val quantity: Int = 1`
- `modules/fulfillment/.../domain/service/LineItemOrderCreator.kt` — passes `lineItem.quantity` to `CreateOrderCommand`
- `modules/fulfillment/.../domain/service/OrderService.kt` — sets `order.quantity = request.quantity` in `create()`
- `modules/fulfillment/.../domain/service/SupplierOrderPlacementListener.kt` — changed `quantity = 1` to `quantity = order.quantity`

### Bug 2: Resilience4j retry fix

Removed the `try-catch(RestClientException)` from `CjOrderAdapter.placeOrder()` so exceptions propagate to Resilience4j AOP interceptors. Added exception handling in `SupplierOrderPlacementListener` to catch exceptions after retries are exhausted.

### Files Changed
- `modules/fulfillment/.../proxy/supplier/CjOrderAdapter.kt` — removed `try-catch` block, let `RestClientException` propagate
- `modules/fulfillment/.../domain/service/SupplierOrderPlacementListener.kt` — wrapped `adapter.placeOrder()` in `try-catch(Exception)` to handle post-retry failures

## Impact

**Bug 1 (quantity):** If deployed, every multi-quantity order would under-ship. Customer pays for N items, receives 1. Direct revenue loss on every order where `quantity > 1`. Would trigger refunds, chargebacks, and potentially breach the 5% refund rate / 2% chargeback rate thresholds that auto-pause SKUs. **This bug would have been invisible in logs** — the system would report successful supplier order placement with no indication that the wrong quantity was ordered.

**Bug 2 (Resilience4j):** If deployed, CJ API network failures would immediately fail orders instead of retrying. The circuit breaker would never open, so a CJ outage would generate a stream of FAILED orders instead of backing off. Operational noise, unnecessary order failures on transient network issues.

Both bugs were caught before merge by Unblocked's PR review.

## Workflow Evaluation Metrics

### Token & Context Efficiency

| Metric | Value |
|--------|-------|
| Main agent context at session end | ~150k / 1,000k (15%) |
| Total agents spawned | 7 (phases 1-4 each in subagent, phase 5 in subagent, phase 6 fix in subagent, e2e playbook in subagent) |
| Context saved by isolation | ~85% — subagent isolation kept the main orchestrator clean |

### Agent Execution Times

| Agent | Phase | Duration | Tool Uses | Tokens |
|-------|-------|----------|-----------|--------|
| Discovery | Phase 1 | 4m 38s | 78 | 88k |
| Specification | Phase 2 | 3m 20s | 38 | 73k |
| Planning | Phase 3 | 4m 49s | 53 | 74k |
| Test-First Gate | Phase 4 | 9m 11s | 74 | 112k |
| Implementation | Phase 5 | 16m 03s | 90 | 124k |
| Review Fix | Phase 6 | 3m 14s | 35 | 55k |
| E2E Playbook | Post-phase | 13m 29s | 123 | 126k |
| **Total** | | **~55 min** | **491** | **652k** |

### Meta Controller Compliance

| Recommendation | Followed? | Outcome |
|---|---|---|
| Use 4-5 parallel agents for Phase 5 | No — used 1 agent | **Justified.** `Order.kt` modified by 4+ task groups; parallel agents would conflict. Single agent avoided merge issues. |
| Decompose at depth 3 | Partially | Plan had 12 layers but execution was sequential due to dependencies. |
| Deliberative cognition mode | Yes | Appropriate given novelty (0.4) and blast radius (0.4). |
| Batch into chunks of 10 | No | Single agent processed all 46 tasks sequentially. Chunking would have required inter-chunk handoff. |

### Phase 4 Test Quality Assessment

| Category | Tests | Caught bugs? | Gap |
|---|---|---|---|
| End-to-end flow | 3 | No | Tested data shape, not data flow. Used `TODO()` placeholders. |
| Boundary conditions | 9 | No | Tested error paths but with hardcoded values. No data lineage assertions. |
| Contract tests | 9 | No | Fixture parsing only. No behavioral assertions on retry/circuit-breaker. |
| Shipping extraction | 5 | No | Backward compat only. Did test address extraction correctly. |
| **Total** | **25** | **0 of 2 bugs** | Phase 4 tests validated structure, not behavior. |

## Lessons Learned

### What went well

- **Subagent isolation worked.** Main orchestrator stayed at 15% context after a 55-minute session. Each phase got a clean context window.
- **Unblocked review caught both bugs.** Phase 6 is the safety net, and it worked. The automated reviewer identified the exact root cause and fix for each bug.
- **Auto-advance worked.** No unnecessary gates between phases. The workflow flowed without user intervention until Phase 6 review.
- **E2E playbook run caught integration issues.** The post-implementation playbook revealed that FR-025 broke the existing order lifecycle (orders failed due to missing supplier mappings). Playbook was updated.
- **CLAUDE.md constraints were followed.** All 6 referenced constraints (#6, #8, #12, #13, #15, #16) were correctly applied. No constraint violations.

### What could be improved

- **Phase 1 Discovery lacks API contract analysis.** Discovery audited the `Order` entity for structural gaps but did not systematically compare the entity's fields against the external API's input requirements. A "can we supply what the API needs?" checklist would have caught the quantity gap.

- **Phase 3 Plan had contradictory requirements.** The plan specified Resilience4j annotations AND internal exception handling. These are mutually exclusive patterns. Plans should include a "behavioral consistency check" — do the annotations, patterns, and error handling strategies work together?

- **Phase 4 tests validated shape, not flow.** All 25 tests checked that data structures looked correct but none traced data from source (Shopify webhook) to sink (CJ API). A "data lineage test" category is missing: given input X at the system boundary, assert that output Y at the other boundary contains the same value.

- **Phase 4 tests used `TODO()` as placeholders.** 15 of 25 tests contained `TODO()` calls that were replaced in Phase 5. These weren't real tests — they were requirements documents written in test syntax. The tests that actually passed in Phase 4 tested stub types, not real behavior.

- **Phase 4 stubs in test source set.** Because Phase 4 cannot write to main source, it created compilation stubs in the test source set. Tests then imported from these stubs, meaning they tested the stubs — not the real interfaces. When Phase 5 replaced stubs with real types, the import swap could mask interface mismatches.

- **No "business scenario" test category.** Phase 4 generates 3 categories: end-to-end flow, boundary conditions, and dependency contracts. None of these naturally captures "customer orders 3 of item A — does CJ receive quantity=3?" A 4th category — **business scenario tests** — would trace full business use cases from user action to external system call, asserting business-critical values at every boundary.

## Prevention

### P0 — Immediate (applied in this session)

- [x] **Remove default `= 1` from `CreateOrderCommand.quantity`.** The fix for the hardcoded quantity initially added `quantity: Int = 1` as a default on `CreateOrderCommand` — reintroducing the same class of bug via a different mechanism. Any new caller that forgets to pass quantity silently gets 1. Removing the default makes `quantity` a required parameter: the compiler now forces every callsite to provide it explicitly. Applied: 7 callsites updated (1 production, 6 tests). This is the structural enforcement pattern: **if a value is business-critical, make it required at the type level, not defaulted.**

### P1 — Structural (prevent this class of bug)

- [ ] **Add "data lineage test" category to Phase 4 skill.** For every field in an external API request, Phase 4 must generate a test that traces the field's value from its origin (webhook, user input) through all intermediate representations (ChannelOrder, CreateOrderCommand, Order entity) to the API request. Test name pattern: `{source field} propagates from {origin} to {destination}`. This would have caught the quantity bug.

- [ ] **Add "behavioral consistency check" to Phase 3 skill.** Before finalizing the implementation plan, verify that error handling patterns (try-catch, Result types) are consistent with resilience annotations (@Retry, @CircuitBreaker). Flag contradictions: "Method has @Retry but catches the exception internally — retries will never fire."

- [ ] **Add CLAUDE.md constraint #18: @Retry/@CircuitBreaker methods must not catch the exception types they should retry.** Formalize the pattern: Resilience4j AOP operates at the method boundary. If the method catches `RestClientException` internally, `@Retry` never sees a failure. Let exceptions propagate; handle in the caller.

### P2 — Process (improve the workflow)

- [ ] **Phase 1 Discovery: add API contract gap analysis.** When a feature involves calling an external API, Discovery must compare every field in the API request against the data model. For each field, answer: "Where does this value come from? Is it stored? Can we access it at call time?" Document any missing fields as gaps alongside the existing gap analysis.

- [ ] **Phase 4: require at least one test per external API field that traces from source to sink.** The test-manifest.md should have a "Data Lineage" section mapping each API field to its source and the test that validates the flow. If a field has no lineage test, Phase 4 validation should fail.

- [ ] **Phase 4: reduce `TODO()` usage.** Tests that contain `TODO()` are not tests — they're requirements. Phase 4 should maximize tests that actually execute assertions, even if they test against minimal stubs. Prefer tests that will *fail with a meaningful assertion error* over tests that throw `NotImplementedError`.

- [ ] **Evaluate a "business scenario test" skill.** The user's suggestion of a dedicated test-design skill that generates business scenario tests (customer orders N items → supplier receives N items) and integrates with Phase 4. This skill would take the spec's success criteria and generate concrete user-journey tests with exact value assertions at every system boundary.

### P3 — Observability (catch this faster next time)

- [ ] **Add `OrderResponse` DTO fields for `supplierOrderId`, `failureReason`, `quantity`.** Currently these fields are not exposed via REST, requiring DB queries to verify. Exposing them enables both E2E testing and operational dashboards.

- [ ] **Add E2E playbook step verifying quantity propagation.** The webhook payload should include `quantity > 1` and the playbook should verify the supplier order request quantity matches.
