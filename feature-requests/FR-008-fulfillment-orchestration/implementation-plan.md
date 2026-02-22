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
- [ ] Implement `OrderStatus` enum with all 6 statuses
- [ ] Implement `ShipmentDetails` embeddable (trackingNumber, carrier, estimatedDelivery, delayDetected)
- [ ] Implement `Order` JPA entity with idempotency key unique constraint
- [ ] Implement `ReturnRecord` JPA entity (orderId, reason, returnedAt, reverseLogisticsCost)

### Domain Service
- [ ] Implement `OrderService.create(request)` with inventory pre-check gate
- [ ] Implement `OrderService.routeToVendor(orderId)` assigning vendor and updating status to CONFIRMED
- [ ] Implement `ShipmentTracker` `@Scheduled(fixedRate = 1_800_000)` (every 30 min)
- [ ] Implement carrier polling via `CarrierRateProvider` tracking endpoints (or separate `CarrierTrackingProvider` interface)
- [ ] Implement delay detection logic (estimated delivery + 1 day < now)
- [ ] Implement `DelayAlertService.checkAndAlert(order)` — log to `customer_notifications`
- [ ] Implement `VendorSlaBreachRefunder` `@EventListener(VendorSlaBreached::class)` — find active orders, trigger refunds
- [ ] Publish `OrderFulfilled` event on DELIVERED status

### Proxy Layer
- [ ] Implement `InventoryCheckAdapter` calling Shopify inventory levels API
- [ ] Implement `StripeRefundAdapter` with idempotency key, retry, circuit breaker
- [ ] Implement `NotificationAdapter` stub (writes to `customer_notifications` table)

### Handler Layer
- [ ] Implement `OrderController` with `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/tracking`
- [ ] Add `CreateOrderRequest` (skuId, customerId, shippingAddress, idempotencyKey) DTO
- [ ] Add `OrderResponse`, `TrackingResponse` DTOs

### Persistence (Common Layer)
- [ ] Write `V7__orders.sql` migration (orders, shipment_details, return_records, customer_notifications tables)
- [ ] Implement `OrderRepository`, `ReturnRecordRepository`

## Testing Strategy

- Unit test `OrderService.create`: inventory available → order created; inventory unavailable → rejected
- Unit test `VendorSlaBreachRefunder`: breach event → correct orders found → refund triggered
- Unit test `DelayAlertService`: delay detected → notification logged
- WireMock test: `StripeRefundAdapter` calls Stripe refund endpoint with correct payload
- WireMock test: `InventoryCheckAdapter` handles out-of-stock response
- Integration test: full order lifecycle PENDING → CONFIRMED → SHIPPED → DELIVERED

## Rollout Plan

1. Write `V7__orders.sql`
2. Implement `Order` aggregate and `OrderService`
3. Implement `InventoryCheckAdapter`
4. Implement `ShipmentTracker`
5. Implement `VendorSlaBreachRefunder` + `StripeRefundAdapter`
6. Add REST handler
