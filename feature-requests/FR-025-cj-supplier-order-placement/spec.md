# FR-025: CJ Supplier Order Placement

**Feature:** `cj-supplier-order-placement`
**Linear ticket:** [RAT-27](https://linear.app/ratrace/issue/RAT-27/cj-dropshipping-order-placement-auto-fulfill-via-supplier-api)
**Prior attempt:** PR #37 (FR-025 v1) — abandoned due to 2 critical bugs (see Prior Attempt Learnings below)

---

## Problem Statement

`OrderService.routeToVendor()` transitions orders from PENDING to CONFIRMED but does not contact any supplier. In the zero-capital model, the customer's payment funds the supplier order — so confirmed orders that sit idle represent unfulfilled customer purchases with no supplier action taken. The system currently creates orders from Shopify webhooks (FR-023) but has no mechanism to forward them to CJ Dropshipping (or any supplier) for actual fulfillment.

Additionally, the Order entity lacks several fields required for supplier order placement: shipping address (nowhere to send the product), quantity (how many units to order), and supplier cross-reference IDs (no way to track the supplier-side order). The Shopify webhook adapter extracts line item data but discards the shipping address from the webhook payload entirely.

---

## Prior Attempt Learnings

PR #37 attempted this feature but introduced two critical bugs that were caught in review:

1. **Hardcoded quantity=1** — The Order entity had no `quantity` field. The CJ order adapter hardcoded `quantity: 1` in every API request regardless of actual order quantity. A customer ordering 3 units would receive 1. This is a silent data loss bug that unit tests cannot catch because the hardcoded value happens to match most test fixtures.

2. **Resilience4j bypass (CLAUDE.md #18 violation)** — `CjOrderAdapter.placeOrder()` wrapped the REST call in a try-catch that swallowed `RestClientException`. Because Resilience4j AOP operates at the method boundary, `@Retry` and `@CircuitBreaker` never saw failures and retries never fired. Transient CJ API failures would be silently swallowed instead of retried.

This redo must incorporate these fixes from the start, not as afterthoughts.

---

## Business Requirements

### BR-1: Automatic supplier order placement on confirmation

When an internal order transitions to CONFIRMED status, the system must automatically place a corresponding purchase order with CJ Dropshipping via their `createOrderV2` API. No manual intervention required. This is where the zero-capital model becomes operational — customer payment funds the supplier order.

### BR-2: Shipping address capture and flow-through

The customer's shipping address must be captured from the Shopify webhook payload, stored on the internal Order entity, and mapped correctly to CJ's API format. The address must flow through the system without data loss — every field from Shopify's `shipping_address` object that CJ requires must be preserved.

CJ's required shipping fields: `shippingCustomerName`, `shippingAddress`, `shippingCity`, `shippingProvince`, `shippingZip`, `shippingCountry`, `shippingCountryCode`, `shippingPhone`.

### BR-3: Order quantity flow-through

The quantity ordered by the customer (from the Shopify line item) must flow through to the CJ order without transformation or default values. The `quantity` field must be required (no default) at every stage of the pipeline: `ChannelLineItem.quantity` -> `CreateOrderCommand.quantity` -> `Order.quantity` -> CJ API `products[].quantity`. Compiler enforcement (no default value on required parameters) prevents the hardcoded-quantity bug from recurring.

### BR-4: CJ order ID cross-reference

When CJ accepts an order, the system must store CJ's order ID on the internal Order entity. This enables cross-referencing between internal orders and supplier orders for tracking, reconciliation, and support inquiries.

### BR-5: Graceful error handling with FAILED state

If CJ rejects an order (out of stock, invalid address, API error), the system must:
- Transition the order to a new FAILED terminal status
- Record the failure reason on the order
- Not silently lose the error — the failure must be visible in the order record

The FAILED state must be a valid transition from CONFIRMED in the order state machine.

### BR-6: Supplier abstraction for future suppliers

The CJ integration must be built behind a `SupplierOrderAdapter` interface so that future suppliers (Printful, Printify, Gelato, etc.) can be swapped in without touching domain logic. This follows the project's established adapter interface pattern (see solo-operator-spec.md Section 4.2).

### BR-7: Product-to-supplier variant mapping

The system needs a mapping between internal SKU IDs and CJ's product variant IDs (`vid`). CJ's order API requires the `vid`, not the `pid`. This mapping is populated from demand scan candidate data (the CJ Dropshipping demand signal adapter already captures `cj_pid` but not `vid` — the mapping entity must support this). Seed data is acceptable for Phase 1; the demand scan pipeline enhancement to auto-populate `vid` is a separate concern.

---

## Success Criteria

These correspond to [RAT-27 acceptance criteria](https://linear.app/ratrace/issue/RAT-27/cj-dropshipping-order-placement-auto-fulfill-via-supplier-api):

| # | Criterion | Verification |
|---|---|---|
| SC-1 | Order confirmed -> CJ order placed automatically via API | Integration test: CONFIRMED order triggers listener that calls CJ API |
| SC-2 | CJ order ID stored on internal Order entity | Assert `supplierOrderId` is non-null after successful placement |
| SC-3 | Shipping address correctly mapped to CJ format | WireMock contract test validates CJ request body field mapping against API docs |
| SC-4 | Out-of-stock / API error handled gracefully (order marked FAILED, not silently lost) | Test: CJ returns error -> order status is FAILED, failureReason is populated |
| SC-5 | `SupplierOrderAdapter` interface defined for future suppliers | Interface exists with documented contract; CJ adapter implements it |
| SC-6 | WireMock contract test against CJ API docs | Tests validate request format matches CJ `createOrderV2` documentation |
| SC-7 | Integration test verifying full confirmed -> CJ order chain | End-to-end test: order creation -> route to vendor -> event published -> CJ adapter called -> supplier order ID stored |
| SC-8 | Quantity flows through correctly from Shopify line item to CJ order | Test: line item with quantity=3 produces CJ request with quantity=3, not quantity=1 |
| SC-9 | Resilience4j retry/circuit breaker fires on transient failures | Test: RestClientException propagates out of adapter method, @Retry triggers re-execution |

---

## Non-Functional Requirements

### NFR-1: Resilience (retry and circuit breaker)

The CJ order adapter must use `@Retry` and `@CircuitBreaker` annotations (Resilience4j). Per CLAUDE.md constraint #18, the adapter method must NOT catch `RestClientException` or any retryable exception internally. Let retryable exceptions propagate out of the method so Resilience4j AOP can intercept them. Catch exceptions only in the caller (the event listener) after retries are exhausted.

### NFR-2: Idempotency

Supplier order placement must be idempotent. If the listener fires twice for the same order (e.g., due to event replay), it must not place a duplicate CJ order. The `supplierOrderId` field on the Order entity serves as the guard — if already populated, skip placement. CJ's API also supports `orderNumber` as a deduplication key on their side.

### NFR-3: Data lineage — quantity

Quantity must flow through the system without transformation, default values, or hardcoding. The `CreateOrderCommand.quantity` parameter must have no default value, ensuring a compile error if any caller omits it. This prevents the hardcoded-quantity bug structurally.

### NFR-4: Data lineage — shipping address

The shipping address must flow from Shopify webhook JSON -> `ChannelOrder` (new shipping address data class) -> `CreateOrderCommand` -> `Order` (embedded) -> CJ API request body. No field may be silently dropped. The `ChannelOrder` shipping address type must carry all fields that CJ requires.

### NFR-5: Event-driven transaction safety

The supplier order placement must follow CLAUDE.md constraint #6: `@TransactionalEventListener(phase = AFTER_COMMIT)` with `@Transactional(propagation = Propagation.REQUIRES_NEW)`. The listener fires only after the order's CONFIRMED status is committed, and writes to the database (supplierOrderId, failureReason, FAILED status) happen in a new transaction.

### NFR-6: Configuration over hardcoding

CJ API-specific values that may change across environments must be configurable via `application.yml`:
- `cj-dropshipping.api.base-url` (API base URL)
- `cj-dropshipping.api.access-token` (auth token)
- `cj-dropshipping.order.logistic-name` (shipping method, e.g., "YunExpress")
- `cj-dropshipping.order.from-country-code` (warehouse country, e.g., "CN")

Per CLAUDE.md constraint #13, all `@Value` annotations on adapter constructor parameters must include empty defaults (`${key:}`) so beans can instantiate under any Spring profile.

### NFR-7: Observability

Failed supplier order placements must be logged at ERROR level with the CJ error response body. Successful placements must be logged at INFO level with the internal order ID and CJ order ID for cross-reference.

---

## Dependencies

| Dependency | Description | Status |
|---|---|---|
| CJ Dropshipping API access token | Free registration at developers.cjdropshipping.com | Required before production |
| Product-to-CJ-variant mapping (`vid`) | Links internal SKU to CJ product variant | Seed data for Phase 1; demand scan pipeline enhancement later |
| Shopify webhook integration (FR-023, RAT-26) | Orders must exist before they can be placed with suppliers | Implemented |

---

## Out of Scope

- **CJ order status tracking / polling** — checking CJ order fulfillment status and updating internal order status (separate feature)
- **CJ shipping tracking number retrieval** — getting tracking numbers from CJ and updating ShipmentDetails (separate feature)
- **Demand scan pipeline enhancement** — auto-populating `vid` from CJ product search results during demand scanning
- **Multi-supplier routing logic** — deciding which supplier to use for a given SKU (Phase 1 assumes CJ is the only supplier)
- **Payment capture timing** — when to capture Shopify payment relative to CJ order placement (separate concern)
- **CJ auth token refresh** — CJ tokens are long-lived; refresh logic is a future concern
