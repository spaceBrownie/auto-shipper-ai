# FR-025: CJ Supplier Order Placement

## Problem Statement

The zero-capital business model requires that the customer's payment funds the supplier order — no upfront inventory spend. Currently, `OrderService.routeToVendor()` transitions an order to `CONFIRMED` but does not contact any supplier. The order sits in `CONFIRMED` status with no purchase order placed, no supplier tracking, and no feedback loop if the supplier cannot fulfill. Until the system can automatically place a purchase order with a supplier when an order is confirmed, the fulfillment pipeline is a stub and no real product can ship.

CJ Dropshipping is the first supplier integration. CJ offers a free API (no subscription, no MOQ) where each order is paid at product cost + shipping — a direct fit for the zero-capital model. The solo-operator spec (Section 2.1) explicitly names CJ Dropshipping as a supplier integration target, and the spec's `SupplierAdapter` interface design (Section 3.4) defines `placeOrder(order: Order): SupplierOrderId` as a required capability.

This feature closes the gap between "order confirmed" and "purchase order placed with supplier," making the fulfillment pipeline real.

## Business Requirements

### BR-1: Automatic Supplier Order Placement on Confirmation

When an internal order transitions to `CONFIRMED`, the system must automatically place a purchase order with the appropriate supplier (CJ Dropshipping for Phase 1) using the customer's shipping address. No manual intervention is permitted. The trigger must be event-driven: an `OrderConfirmed` domain event published after the `CONFIRMED` transition commits, consumed by a listener that places the supplier order in a new transaction.

### BR-2: Shipping Address Capture and Flow-Through

The customer's shipping address must be captured at order creation time (from the Shopify webhook payload) and persisted on the `Order` entity. The address must flow through unchanged to the CJ API request. All 10 CJ shipping fields must be populated:
- `shippingCustomerName` (first + last name)
- `shippingAddress` (street address line 1 + line 2)
- `shippingCity`
- `shippingProvince`
- `shippingCountryCode` (ISO 2-letter)
- `shippingCountry` (full country name)
- `shippingZip`
- `shippingPhone`
- `fromCountryCode` (source country, typically "CN")

### BR-3: Supplier Order ID Linkage

When CJ accepts an order, its order ID must be stored on the internal `Order` entity for cross-reference. This enables:
- Tracking the CJ order status downstream
- Customer support cross-referencing between internal and supplier order IDs
- Reconciliation between payments received and supplier orders placed

### BR-4: Product-to-Supplier Variant Mapping

Each internal SKU must be linked to the CJ product variant ID (`vid`) required by the CJ `createOrderV2` endpoint. The current demand scan stores `cj_pid` (product ID) in `demand_signals` JSONB but does **not** store the `vid` (variant ID). The `vid` is required because CJ products can have multiple variants (size, color, etc.) and the API rejects orders without a valid `vid`. A persistent mapping between internal SKU IDs and CJ variant IDs must exist, populated either at demand scan time (by also fetching and storing the `vid`) or via a dedicated product mapping table.

### BR-5: Order Quantity Flow-Through

The order must carry the quantity of units being purchased. Currently, `Order` has no `quantity` field. The quantity originates from the Shopify webhook line item, must be persisted on the Order, and must be passed to the CJ API. Hardcoding quantity to 1 is not acceptable (this was a bug in PR #37).

### BR-6: Graceful Failure Handling

If the CJ API rejects an order (out of stock, invalid address, authentication failure, network error), the internal order must transition to a `FAILED` status with the failure reason recorded. The system must:
- Add `FAILED` to the `OrderStatus` enum with valid transitions: `CONFIRMED -> FAILED`
- Add a `failureReason` field to the `Order` entity
- Persist the failure reason from the CJ API error response
- Never silently swallow errors — a failed supplier order must be visible in the system

### BR-7: Supplier Adapter Abstraction

The integration must define a `SupplierOrderAdapter` interface that CJ implements, enabling future suppliers (Printful, Printify, Gelato) to be added without changing the event listener or orchestration logic. The interface must accept a supplier-agnostic order placement request and return a result containing the supplier's order ID or failure details.

## Success Criteria

### SC-1: Event-Driven Order Placement
An `OrderConfirmed` domain event is published when `routeToVendor()` commits. A `@TransactionalEventListener(AFTER_COMMIT)` handler with `@Transactional(REQUIRES_NEW)` consumes it and places the CJ order.

### SC-2: Shipping Address Persisted
The `Order` entity includes an embedded or associated `ShippingAddress` with fields for: customer name, address line 1, address line 2 (nullable), city, province/state, province code, country, country code, zip/postal code, phone (nullable). The Shopify webhook adapter extracts all shipping fields with the NullNode guard (`?.let { if (!it.isNull) it.asText() else null }`) on every field.

### SC-3: CJ Order ID Stored
On successful CJ order placement, the CJ order ID is stored on the internal `Order` entity in a `supplierOrderId` field.

### SC-4: Quantity Carried on Order
The `Order` entity includes a `quantity` field (integer, no default). The `CreateOrderCommand` includes quantity. The Shopify webhook adapter extracts quantity from line items and passes it through.

### SC-5: FAILED Status with Reason
`OrderStatus.FAILED` exists. `CONFIRMED -> FAILED` is a valid transition. `Order` has a `failureReason: String?` field. When the CJ API returns an error, the order transitions to `FAILED` with the reason persisted.

### SC-6: SupplierOrderAdapter Interface
A `SupplierOrderAdapter` interface exists with a method accepting a placement request (order ID, shipping address, product variant mapping, quantity) and returning a result (success with supplier order ID, or failure with reason).

### SC-7: CJ API Contract Test
A WireMock contract test verifies the CJ `createOrderV2` request format against the official CJ API documentation. Fixtures must be created from the CJ API docs, not reverse-engineered from adapter code. The test must exercise:
- Successful order placement (happy path)
- CJ error response (out of stock)
- CJ error response (invalid address)
- Authentication failure (missing/invalid token)

### SC-8: Integration Test — Full Chain
An integration test verifies the complete flow: order created with shipping address -> order confirmed -> `OrderConfirmed` event published -> listener places CJ order (via WireMock) -> CJ order ID stored on order. Also: CJ API failure -> order marked `FAILED` with reason.

### SC-9: Resilience Annotations
The CJ adapter must use `@CircuitBreaker` and `@Retry` annotations (Resilience4j), following the `StripeRefundAdapter` pattern.

## Non-Functional Requirements

### NFR-1: Transaction Safety
The `OrderConfirmed` event listener must follow CLAUDE.md constraint #6: `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`. The supplier order placement and any resulting status update must commit independently of the original order confirmation transaction.

### NFR-2: JSON Parsing Safety
All Jackson `get()` calls on external JSON payloads (Shopify webhook, CJ API response) must use:
- `get()` not `path()` for fields where absence should coalesce to null (CLAUDE.md #15)
- The NullNode guard `?.let { if (!it.isNull) it.asText() else null }` on every field (CLAUDE.md #17)
- Consistent application — if one field in a block uses the guard, all fields must

This is the specific bug that PR #39 shipped: 9 of 10 shipping address fields used bare `?.asText()` instead of the NullNode guard, causing JSON `null` values to become the string `"null"` flowing to the CJ API.

### NFR-3: Credential Guard
The CJ adapter must follow CLAUDE.md constraint #13: `@Value` annotations with empty defaults (`${key:}`), and early return with warning log if credentials are blank. Follow the `StripeRefundAdapter` and `CjDropshippingAdapter` (demand signal) patterns.

### NFR-4: URL Encoding
All user-supplied values in CJ API request bodies must be URL-encoded per CLAUDE.md constraint #12, if the request uses form-encoded format. If JSON-encoded, ensure proper escaping via Jackson serialization.

### NFR-5: Idempotency
Supplier order placement must be idempotent. If the listener fires twice for the same order (e.g., due to retry), the second call must not create a duplicate CJ order. The internal order's `supplierOrderId` field serves as the deduplication signal: if already populated, skip placement.

### NFR-6: Flyway Migration
All schema changes (new columns on `orders`, new tables for product mapping) must be delivered as Flyway migrations. The current highest migration is V20. New migrations must follow the sequential numbering convention.

### NFR-7: WireMock Fixture Accuracy
WireMock fixtures for the CJ API must be based on the official CJ API documentation at `https://developers.cjdropshipping.com/`, not reverse-engineered from adapter code. Error codes, response structures, and field names must match the real API. This is a lesson from PM-014 (WireMock Fixture Circular Validation).

### NFR-8: No Test Theater
Every test must call production code and assert on its output. Tests that assert `true`, check fixture file content with `contains()`, or verify data class constructors return what was passed in are not acceptable. This is a lesson from PM-017 where 50 of 65 tests were theater. Boundary tests must include JSON `null` values in shipping address fields.

## Prior Attempt Learnings

This is the third attempt at FR-025. The first two attempts produced PRs with specific, documented failures:

| PR | Key Failure | Root Cause |
|---|---|---|
| #37 | Hardcoded `quantity = 1` | No quantity field on Order; adapter defaulted silently |
| #39 | NullNode trap on 9/10 shipping fields | `get()?.asText()` returns `"null"` for JSON null; guard pattern existed 5 lines above but was not applied consistently |
| #39 | 50/65 tests were theater | `assert(true)`, fixture content checks, constructor round-trips — no production code exercised |

These failures are encoded in the business requirements and NFRs above. The spec explicitly requires:
- BR-5: quantity as a required field, no default
- NFR-2: NullNode guard on every JSON field extraction
- NFR-8: no test theater

## Dependencies

- **FR-008** (fulfillment-orchestration) — `Order` entity, `OrderService`, `OrderStatus`, `ShipmentDetails`, `OrderRepository`
- **FR-016** (demand-scan) — `DemandCandidate`, `demand_signals` JSONB containing `cj_pid` (but missing `vid` — see BR-4)
- **FR-020** (shopify-webhook) — `ShopifyOrderAdapter`, `ChannelOrder`, webhook event processing pipeline that creates orders from Shopify
- **Shared module** — `DomainEvent` interface, `OrderId`, `SkuId` identity types, `Money` value type
- **CJ Dropshipping API** — `POST /api2.0/v1/shopping/order/createOrderV2`, authenticated via `CJ-Access-Token` header. Free tier, no subscription required.
- **Resilience4j** — already in use (`StripeRefundAdapter`); needed for circuit breaker and retry on CJ adapter

## Data Model Gap Summary

The following gaps exist in the current data model and must be addressed by this feature:

| Gap | Current State | Required State |
|---|---|---|
| `OrderStatus.FAILED` | Does not exist | New enum value with `CONFIRMED -> FAILED` transition |
| `Order.supplierOrderId` | Does not exist | `String?` column for CJ order ID |
| `Order.failureReason` | Does not exist | `String?` column for failure details |
| `Order.quantity` | Does not exist | `Int` column, no default, from line item |
| Shipping address on Order | Does not exist | Embedded `ShippingAddress` or dedicated columns |
| Shipping address on ChannelOrder | Does not exist | `ChannelShippingAddress` data class on `ChannelOrder` |
| `OrderConfirmed` domain event | Not published by `routeToVendor()` | New event in shared module, published after CONFIRMED commit |
| `SupplierOrderAdapter` interface | Does not exist | New interface in fulfillment module |
| CJ variant ID (`vid`) mapping | Only `cj_pid` stored in demand signals | Either add `vid` to demand scan or create product mapping table |
