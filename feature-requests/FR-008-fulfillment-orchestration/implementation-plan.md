> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/fulfillment/src/...` instead of `fulfillment/src/...`.

> **FR-007 dependency note:** The vendor module (FR-007) needs fulfillment data to compute SLA breach
> rates and vendor reliability scores. Since FR-008 wasn't implemented yet, FR-007 introduced a
> `VendorFulfillmentDataProvider` interface with a `StubFulfillmentDataProvider` that returns 0s.
> This FR must provide a real implementation of that interface, backed by order fulfillment records.
> See `modules/vendor/src/main/kotlin/.../service/VendorFulfillmentDataProvider.kt` for the contract.
> Both `VendorSlaMonitor` and `VendorReliabilityScorer` depend on it for 30-day rolling window
> violation counts. See `docs/postmortems/PM-002-circular-sla-breach-detection.md` for full context.

# FR-008: Fulfillment Orchestration — Implementation Plan

## Technical Design

The `fulfillment` module manages the `Order` aggregate from creation through delivery. `ShipmentTracker` polls carrier APIs on schedule. `DelayAlertService` detects delays and triggers notifications. A `VendorSlaBreachRefunder` listens for `VendorSlaBreached` events correlated to active orders and auto-refunds via Stripe.

```
fulfillment/src/main/kotlin/com/autoshipper/fulfillment/
├── domain/
│   ├── Order.kt                   (aggregate root)
│   ├── OrderStatus.kt             (enum)
│   ├── ShipmentDetails.kt
│   └── ReturnRecord.kt
├── domain/service/
│   ├── OrderService.kt            (create, route, confirm)
│   ├── ShipmentTracker.kt         (@Scheduled carrier polling)
│   ├── DelayAlertService.kt       (delay detection + notification)
│   └── VendorSlaBreachRefunder.kt (@EventListener VendorSlaBreached)
├── proxy/
│   ├── InventoryCheckAdapter.kt   (Shopify inventory API)
│   ├── StripeRefundAdapter.kt
│   └── NotificationAdapter.kt    (email/SMS stub)
└── handler/
    └── OrderController.kt
```

## Architecture Decisions

- **Idempotency key on order creation**: Prevents duplicate orders from retried requests. Stored as a unique constraint on `orders.idempotency_key`.
- **`ShipmentTracker` polls at 30-minute intervals**: Carrier tracking APIs don't support webhooks universally. Polling is the reliable baseline; webhook support can be added per carrier later.
- **`VendorSlaBreachRefunder` is event-driven**: Listens for `VendorSlaBreached` and cross-references active orders. No direct call from vendor module to fulfillment.
- **`NotificationAdapter` is a stub in Phase 1**: Interface defined, stub implementation logs to `customer_notifications`. Real email/SMS wired in Phase 2.
- **Inventory check before payment capture**: Shopify inventory API called synchronously during order creation. If stock unavailable, order is rejected before payment intent is created.

## Layer-by-Layer Implementation

### Domain Layer
- `Order`: id, idempotencyKey, skuId, vendorId, customerId, status, shipmentDetails, createdAt
- `OrderStatus`: PENDING, CONFIRMED, SHIPPED, DELIVERED, REFUNDED, RETURNED
- `ShipmentDetails`: trackingNumber, carrier, estimatedDelivery, lastKnownLocation, delayDetected

### Domain Service
- `OrderService.create(request)`: inventory check → create order → route to vendor
- `ShipmentTracker.poll()`: fetches tracking updates for all SHIPPED orders, detects delays
- `DelayAlertService.checkAndAlert(order)`: if delay detected, send notification, log to `customer_notifications`
- `VendorSlaBreachRefunder`: on `VendorSlaBreached`, finds all SHIPPED/CONFIRMED orders for vendor → triggers Stripe refund

### Proxy Layer
- `InventoryCheckAdapter`: Shopify inventory level API
- `StripeRefundAdapter`: Stripe `POST /v1/refunds` with idempotency key
- `NotificationAdapter`: stub logs to `customer_notifications` table

## Task Breakdown

### Domain Layer
- [x] Implement `OrderStatus` enum with all 6 statuses
- [x] Implement `ShipmentDetails` embeddable (trackingNumber, carrier, estimatedDelivery, delayDetected)
- [x] Implement `Order` JPA entity with idempotency key unique constraint
- [x] Implement `ReturnRecord` JPA entity (orderId, reason, returnedAt, returnHandlingCost)

### Domain Service
- [x] Implement `VendorFulfillmentDataProvider` backed by order fulfillment records — replace `StubFulfillmentDataProvider` from FR-007 (counts fulfillments and violations in a rolling time window per vendor)
- [x] Implement `OrderService.create(request)` with inventory pre-check gate
- [x] Implement `OrderService.routeToVendor(orderId)` assigning vendor and updating status to CONFIRMED
- [x] Implement `ShipmentTracker` `@Scheduled(fixedRate = 1_800_000)` (every 30 min)
- [x] Implement carrier polling via separate `CarrierTrackingProvider` interface (distinct from `CarrierRateProvider` used for rate quoting)
- [x] Implement delay detection logic (estimated delivery + 1 day < now)
- [x] Implement `DelayAlertService.checkAndAlert(order)` — log to `customer_notifications`
- [x] Implement `VendorSlaBreachRefunder` `@EventListener(VendorSlaBreached::class)` — find active orders, trigger refunds
- [x] Publish `OrderFulfilled` event on DELIVERED status

### Proxy Layer
- [x] Implement `InventoryCheckAdapter` calling Shopify inventory levels API
- [x] Implement `StripeRefundAdapter` with idempotency key, retry, circuit breaker
- [x] Implement `NotificationAdapter` stub (writes to `customer_notifications` table)

### Handler Layer
- [x] Implement `OrderController` with `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/tracking`
- [x] Add `CreateOrderRequest` (skuId, customerId, shippingAddress, idempotencyKey) DTO
- [x] Add `OrderResponse`, `TrackingResponse` DTOs

### Persistence (Common Layer)
- [x] Write `V10__orders.sql` migration (orders, shipment_details, return_records, customer_notifications tables)
- [x] Implement `OrderRepository`, `ReturnRecordRepository`

## Testing Strategy

- Unit test `OrderService.create`: inventory available → order created; inventory unavailable → rejected
- Unit test `VendorSlaBreachRefunder`: breach event → correct orders found → refund triggered
- Unit test `DelayAlertService`: delay detected → notification logged
- WireMock test: `StripeRefundAdapter` calls Stripe refund endpoint with correct payload
- WireMock test: `InventoryCheckAdapter` handles out-of-stock response
- Integration test: full order lifecycle PENDING → CONFIRMED → SHIPPED → DELIVERED
- E2E test: order creation through REST API → vendor routing → shipment tracking update → delivery confirmation with `OrderFulfilled` event published
- E2E test: order creation → vendor SLA breach event → auto-refund triggered → order status REFUNDED
- E2E test: order creation with out-of-stock inventory → order rejected (no payment captured)
- E2E test: `VendorFulfillmentDataProvider` returns correct counts after order lifecycle events (replaces stub)

### E2E / Integration Tests
- [x] E2E test: full order lifecycle (create → route → ship → track → deliver) via REST + WireMock stubs
- [x] E2E test: SLA breach auto-refund flow (order created → `VendorSlaBreached` event → Stripe refund → order REFUNDED)
- [x] E2E test: inventory check rejects out-of-stock order before payment
- [x] E2E test: `VendorFulfillmentDataProvider` real implementation returns correct fulfillment/violation counts
- [x] Unit tests for `OrderService`, `DelayAlertService`, `VendorSlaBreachRefunder`
- [x] WireMock tests for `StripeRefundAdapter` and `InventoryCheckAdapter`

## Rollout Plan

1. Write `V10__orders.sql`
2. Implement `Order` aggregate and `OrderService`
3. Implement `InventoryCheckAdapter`
4. Implement `ShipmentTracker`
5. Implement `VendorSlaBreachRefunder` + `StripeRefundAdapter`
6. Add REST handler
