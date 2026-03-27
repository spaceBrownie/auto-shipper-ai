# FR-025: CJ Dropshipping Supplier Order Placement

## Problem Statement

When a customer places an order through Shopify, the system creates an internal Order entity and transitions it to `CONFIRMED` via `OrderService.routeToVendor()`. However, this transition is purely internal -- no supplier is contacted, and no purchase order is placed. The order sits in `CONFIRMED` status indefinitely with no mechanism to trigger fulfillment.

This breaks the zero-capital business model. The core promise is that the customer's payment funds the supplier order, with the margin retained as profit. Without automated supplier order placement, the system cannot operate autonomously -- a human must manually log into CJ Dropshipping and place every order, defeating the purpose of the commerce engine.

Three structural gaps prevent automated supplier fulfillment:

1. **No shipping address on the Order entity.** The Shopify webhook payload contains `shipping_address` with street, city, province, country, and zip, but `ShopifyOrderAdapter` does not extract it, `ChannelOrder` does not carry it, and the `orders` table has no columns for it. Without a shipping address, no supplier order can be placed.

2. **No product-to-supplier-variant mapping.** CJ Dropshipping requires a `vid` (variant ID) to identify which product variant to fulfill. The demand scan pipeline (`DemandScanJob`) already discovers CJ products and stores the `cj_pid` in `demand_signals`, but there is no persistent mapping from an internal SKU ID to the CJ variant ID needed for order placement.

3. **No `FAILED` order status.** When a supplier rejects an order (out of stock, invalid address, API error), there is no status to represent the failure. The current `OrderStatus` enum has `PENDING`, `CONFIRMED`, `SHIPPED`, `DELIVERED`, `REFUNDED`, and `RETURNED` -- but no terminal failure state. Without `FAILED`, errors are either silently swallowed or force the order into an incorrect status.

## Business Requirements

### BR-1: Automated Supplier Order Placement on Confirmation

When an order transitions to `CONFIRMED`, the system must automatically place a corresponding purchase order with CJ Dropshipping using the CJ API. No human intervention is required. The trigger must be event-driven -- `routeToVendor()` publishes a domain event, and a listener handles supplier order placement asynchronously after the transaction commits.

### BR-2: Zero-Capital Flow Integrity

The supplier order is funded entirely by the customer's payment. The system must not place a supplier order unless the corresponding customer order has been confirmed (payment captured). The supplier order amount is the product cost from the cost envelope, not the customer-facing price. The difference is the system's gross margin.

### BR-3: Shipping Address Capture and Mapping

The customer's shipping address must be captured from the sales channel (Shopify webhook payload) at order creation time and persisted on the Order entity. When placing a supplier order, the shipping address must be mapped to CJ's expected format: `shippingCustomerName`, `shippingAddress`, `shippingCity`, `shippingProvince`, `shippingCountry`, `shippingCountryCode`, `shippingZip`, `shippingPhone`.

### BR-4: Supplier Product Variant Mapping

Each SKU must have a mapping to its CJ Dropshipping variant ID (`vid`). This mapping originates from the demand scan pipeline where CJ is a sourcing source. The mapping must be queryable at order placement time to include the correct `vid` in the CJ API request.

### BR-5: Supplier Order Cross-Reference

The CJ order ID returned by the API must be stored on the internal Order entity. This enables cross-referencing between the internal order, the Shopify order, and the CJ supplier order for tracking, reconciliation, and dispute resolution.

### BR-6: Graceful Error Handling with FAILED Status

If the supplier rejects the order (out of stock, invalid address, API authentication failure, network error), the order must transition to a `FAILED` status with a human-readable failure reason. Failed orders must never be silently lost. The failure reason must be persisted for operational visibility and debugging.

### BR-7: Supplier Adapter Abstraction

The supplier order placement interface must be abstract -- not CJ-specific. CJ Dropshipping is the first supplier, but the system is designed to support multiple suppliers (Printful, Gelato, etc.). The interface defines `placeOrder()` and returns a supplier-specific order ID. CJ is one implementation; future suppliers implement the same interface.

### BR-8: Idempotent Supplier Order Placement

If the event listener fires more than once for the same order (due to retries or redelivery), the system must not place duplicate supplier orders. Idempotency can be enforced via the internal order ID used as the CJ `orderNumber`, combined with checking whether a supplier order ID is already recorded on the Order entity.

## Success Criteria

- [ ] Order confirmed triggers CJ order placed automatically via API -- no manual intervention
- [ ] CJ order ID stored on internal Order entity for cross-reference
- [ ] Shipping address correctly extracted from Shopify webhook and mapped to CJ format
- [ ] Out-of-stock, invalid address, and API errors handled gracefully -- order marked `FAILED` with reason, not silently lost
- [ ] `SupplierOrderAdapter` interface defined for future suppliers (Printful, Gelato)
- [ ] WireMock contract test against CJ API docs for the `createOrderV2` endpoint
- [ ] Integration test verifying full confirmed-to-CJ-order event chain
- [ ] Product-to-CJ-variant mapping (`supplier_product_mappings` table) queryable at order time
- [ ] `FAILED` status added to `OrderStatus` with valid transitions from `CONFIRMED`

## Non-Functional Requirements

### Resilience

- The CJ order placement adapter must use `@CircuitBreaker` and `@Retry` (Resilience4j) consistent with all other external API adapters in the codebase.
- CJ API returns HTTP 200 for all responses including errors -- the adapter must inspect the JSON `code` field to detect failures, not rely on HTTP status codes.
- Transient failures (network timeout, 5xx from CJ) should trigger retries. Permanent failures (invalid address, out of stock) should immediately transition the order to `FAILED`.

### Observability

- Supplier order placement success and failure must be logged at INFO and ERROR levels respectively.
- Micrometer metrics for supplier order placement attempts, successes, and failures.
- The failure reason stored on the Order entity must be actionable -- not raw API response dumps, but categorized reasons (e.g., `OUT_OF_STOCK`, `INVALID_ADDRESS`, `API_AUTH_FAILURE`, `NETWORK_ERROR`).

### Security

- The CJ API access token must be injected via `@Value` with empty default (`${cj-dropshipping.api.access-token:}`) per CLAUDE.md constraint #13.
- The access token must never appear in logs, error messages, or API responses.
- All user-supplied values in the request body must be properly serialized (JSON body for CJ API, not form-encoded).

### Idempotency

- Duplicate event delivery must not create duplicate supplier orders.
- The internal order ID serves as the `orderNumber` in the CJ API request, providing natural idempotency if CJ enforces unique order numbers.
- As a defense-in-depth measure, the listener must check whether `supplierOrderId` is already populated on the Order entity before calling the CJ API.

### Transaction Boundaries

- The event listener must follow the `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` pattern per CLAUDE.md constraint #6.
- Supplier order placement must run in its own transaction -- a failure must not roll back the original order confirmation.

## Dependencies

### External

- **CJ Dropshipping API access token** -- free registration at [developers.cjdropshipping.com](https://developers.cjdropshipping.com/). Required for the real adapter; stub adapter works without it.
- **CJ API endpoint** -- `POST https://developers.cjdropshipping.com/api2.0/v1/shopping/order/createOrderV2` with `CJ-Access-Token` header.

### Internal

- **Shopify webhook shipping address extraction** -- `ShopifyOrderAdapter.parse()` must be extended to extract `shipping_address` fields from the webhook payload and include them in `ChannelOrder`. The `Order` entity and `orders` table must gain shipping address columns.
- **Product-to-CJ-variant mapping** -- a `supplier_product_mappings` table linking `sku_id` to CJ `vid`. This mapping is populated during the demand scan pipeline when CJ products are discovered and promoted to SKUs.
- **`OrderConfirmed` domain event** -- a new event in the shared module, published by `OrderService.routeToVendor()` after the order transitions to `CONFIRMED`. This event triggers the supplier order placement listener.
- **`FAILED` order status** -- added to `OrderStatus` enum with `CONFIRMED -> FAILED` as a valid transition in `Order.VALID_TRANSITIONS`.
- **RAT-26 (Shopify webhook)** -- must be merged first (already merged). Orders must exist in the system before supplier placement can be triggered.

### Modules Affected

- **`fulfillment`** -- Order entity changes (shipping address, supplier order ID, failure reason), OrderStatus.FAILED, event publishing, supplier order listener, CJ adapter
- **`shared`** -- `OrderConfirmed` domain event
- **`app`** -- Flyway migration V21 for schema changes (shipping address columns, supplier_product_mappings table, supplier_order_id column, failure_reason column)
