# FR-008: Fulfillment Orchestration — Summary

## Feature Summary

Implemented the fulfillment module for the Auto Shipper AI commerce engine. The module manages the full order lifecycle from creation through delivery, with automated shipment tracking, proactive delay alerts, and event-driven auto-refunds on vendor SLA breaches. It also replaces the stub `VendorFulfillmentDataProvider` with a real implementation backed by order fulfillment records.

## Changes Made

### Domain Layer
- `Order` aggregate root (JPA entity) with idempotency key, status lifecycle, embedded shipment details, and optimistic locking
- `OrderStatus` enum: PENDING, CONFIRMED, SHIPPED, DELIVERED, REFUNDED, RETURNED
- `ShipmentDetails` embeddable: trackingNumber, carrier, estimatedDelivery, lastKnownLocation, delayDetected
- `ReturnRecord` entity with `returnHandlingCost` (Money type, corrected from stale `reverseLogisticsCost`)
- `CustomerNotification` entity for logging outbound notifications
- `CreateOrderCommand` data class

### Domain Services
- `OrderService`: create (with idempotency + inventory pre-check), routeToVendor, markShipped, markDelivered (publishes `OrderFulfilled`)
- `ShipmentTracker`: `@Scheduled` polling every 30 minutes, updates tracking from carrier APIs, detects delays, marks deliveries
- `DelayAlertService`: sends customer notifications on delay detection
- `VendorSlaBreachRefunder`: `@EventListener(VendorSlaBreached)` — finds active orders, triggers Stripe refunds, updates status

### Proxy Layer
- `CarrierTrackingProvider` interface + `TrackingStatus` (separate from `CarrierRateProvider` — corrected stale reference)
- `StubCarrierTrackingConfiguration` (`@Profile("local")`) with UPS/FedEx/USPS stubs
- `InventoryChecker` interface + `ShopifyInventoryCheckAdapter` (`@CircuitBreaker`, `@Retry`)
- `RefundProvider` interface + `StripeRefundAdapter` (`@CircuitBreaker`, `@Retry`)
- `NotificationSender` interface + `LoggingNotificationAdapter` (Phase 1 stub, persists to DB)

### Handler Layer
- `OrderController`: POST /api/orders, GET /api/orders/{id}, GET /api/orders/{id}/tracking
- DTOs: `CreateOrderRequest`, `OrderResponse`, `TrackingResponse`

### Persistence
- `V10__orders.sql` migration: orders, return_records, customer_notifications tables with indexes
- `OrderRepository`, `ReturnRecordRepository`, `CustomerNotificationRepository`
- `FulfillmentDataProviderImpl` (`@Primary`): real implementation of `VendorFulfillmentDataProvider`, replaces stub

### Stale References Corrected
- `CarrierRateProvider` reuse for tracking → separate `CarrierTrackingProvider` interface
- `reverseLogisticsCost` → `returnHandlingCost`

## Files Modified

### New Source Files (23)
| File | Description |
|---|---|
| `modules/fulfillment/src/main/kotlin/.../domain/Order.kt` | Order aggregate root |
| `modules/fulfillment/src/main/kotlin/.../domain/OrderStatus.kt` | Status enum |
| `modules/fulfillment/src/main/kotlin/.../domain/ShipmentDetails.kt` | Embeddable shipment tracking |
| `modules/fulfillment/src/main/kotlin/.../domain/ReturnRecord.kt` | Return tracking entity |
| `modules/fulfillment/src/main/kotlin/.../domain/CustomerNotification.kt` | Notification log entity |
| `modules/fulfillment/src/main/kotlin/.../domain/service/CreateOrderCommand.kt` | Command DTO |
| `modules/fulfillment/src/main/kotlin/.../domain/service/OrderService.kt` | Order lifecycle service |
| `modules/fulfillment/src/main/kotlin/.../domain/service/ShipmentTracker.kt` | Scheduled carrier polling |
| `modules/fulfillment/src/main/kotlin/.../domain/service/DelayAlertService.kt` | Delay detection + notification |
| `modules/fulfillment/src/main/kotlin/.../domain/service/VendorSlaBreachRefunder.kt` | Auto-refund on SLA breach |
| `modules/fulfillment/src/main/kotlin/.../proxy/carrier/CarrierTrackingProvider.kt` | Tracking interface + TrackingStatus |
| `modules/fulfillment/src/main/kotlin/.../proxy/carrier/StubCarrierTrackingConfiguration.kt` | Local profile stubs |
| `modules/fulfillment/src/main/kotlin/.../proxy/inventory/InventoryCheckAdapter.kt` | Shopify inventory check |
| `modules/fulfillment/src/main/kotlin/.../proxy/payment/StripeRefundAdapter.kt` | Stripe refund integration |
| `modules/fulfillment/src/main/kotlin/.../proxy/notification/NotificationAdapter.kt` | Notification stub |
| `modules/fulfillment/src/main/kotlin/.../persistence/OrderRepository.kt` | Order JPA repository |
| `modules/fulfillment/src/main/kotlin/.../persistence/ReturnRecordRepository.kt` | Return record repository |
| `modules/fulfillment/src/main/kotlin/.../persistence/CustomerNotificationRepository.kt` | Notification repository |
| `modules/fulfillment/src/main/kotlin/.../persistence/FulfillmentDataProviderImpl.kt` | Real VendorFulfillmentDataProvider |
| `modules/fulfillment/src/main/kotlin/.../handler/OrderController.kt` | REST controller |
| `modules/fulfillment/src/main/kotlin/.../handler/dto/CreateOrderRequest.kt` | Request DTO |
| `modules/fulfillment/src/main/kotlin/.../handler/dto/OrderResponse.kt` | Response DTO |
| `modules/fulfillment/src/main/kotlin/.../handler/dto/TrackingResponse.kt` | Tracking response DTO |

### New Test Files (7)
| File | Tests |
|---|---|
| `.../domain/service/OrderServiceTest.kt` | 9 tests |
| `.../domain/service/ShipmentTrackerTest.kt` | 8 tests |
| `.../domain/service/DelayAlertServiceTest.kt` | 4 tests |
| `.../domain/service/VendorSlaBreachRefunderTest.kt` | 6 tests |
| `.../persistence/FulfillmentDataProviderImplTest.kt` | 4 tests |
| `.../handler/OrderControllerTest.kt` | 6 tests |
| `.../integration/OrderLifecycleTest.kt` | 4 tests |

### Modified Files (3)
| File | Change |
|---|---|
| `modules/fulfillment/build.gradle.kts` | Added vendor dep, resilience4j, test deps, JPA plugin |
| `modules/app/src/main/resources/db/migration/V10__orders.sql` | New migration |
| `feature-requests/FR-008-fulfillment-orchestration/spec.md` | Fixed stale CarrierRateProvider reference |

## Testing Completed

- **41 tests total**, all passing
- **Unit tests (27):** OrderService (9), ShipmentTracker (8), VendorSlaBreachRefunder (6), DelayAlertService (4)
- **Persistence tests (4):** FulfillmentDataProviderImpl
- **Controller tests (6):** OrderController via MockMvc
- **Integration/lifecycle tests (4):** Full order lifecycle, SLA breach refund flow, inventory rejection, delay detection
- **Full project build:** `./gradlew build` passes (all modules)

## Deployment Notes

- Run `./gradlew flywayMigrate` to apply `V10__orders.sql` (creates orders, return_records, customer_notifications tables)
- The `FulfillmentDataProviderImpl` is annotated `@Primary` and will automatically replace the `StubFulfillmentDataProvider` from the vendor module
- External adapters (Shopify, Stripe, carrier tracking) require API credentials configured in application properties; `@Profile("local")` stubs are active for local development
- `ShipmentTracker` scheduling requires `@EnableScheduling` on the application class (already present from FR-007)
