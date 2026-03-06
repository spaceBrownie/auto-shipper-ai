# FR-008: Fulfillment Orchestration — Summary

## Feature Summary

Implemented the fulfillment module for the Auto Shipper AI commerce engine. The module manages the full order lifecycle from creation through delivery, with automated shipment tracking, proactive delay alerts, and event-driven auto-refunds on vendor SLA breaches. It also replaces the stub `VendorFulfillmentDataProvider` with a real implementation backed by order fulfillment records.

Five bugs were caught across three rounds of automated PR review (PR #10) and fixed before merge. See `docs/postmortems/PM-003-phantom-refunds-inflated-violations.md` for full details.

## Changes Made

### Domain Layer
- `Order` aggregate root (JPA entity) with idempotency key, `totalAmount`/`totalCurrency`/`paymentIntentId` for payment tracking, embedded shipment details, and optimistic locking
- `Order.VALID_TRANSITIONS` state machine enforcing valid status transitions (mirrors `SkuStateMachine` pattern from catalog module)
- `OrderStatus` enum: PENDING, CONFIRMED, SHIPPED, DELIVERED, REFUNDED, RETURNED
- `ShipmentDetails` embeddable: trackingNumber, carrier, estimatedDelivery, lastKnownLocation, delayDetected
- `ReturnRecord` entity with `returnHandlingCost` (Money type, corrected from stale `reverseLogisticsCost`)
- `CustomerNotification` entity for logging outbound notifications
- `CreateOrderCommand` data class with `totalAmount: Money` and `paymentIntentId: String`

### Domain Services
- `OrderService`: create (with idempotency + inventory pre-check), routeToVendor (requires PENDING), markShipped (requires CONFIRMED), markDelivered (requires SHIPPED, publishes `OrderFulfilled`) — all methods enforce status preconditions via `require()` guards
- `ShipmentTracker`: `@Scheduled` polling every 30 minutes, updates tracking from carrier APIs, detects delays, marks deliveries (with status guard before DELIVERED transition)
- `DelayAlertService`: sends customer notifications on delay detection
- `VendorSlaBreachRefunder`: `@EventListener(VendorSlaBreached)` — finds active orders, validates refund amount > 0, triggers Stripe refunds with `paymentIntentId`, updates status

### Proxy Layer
- `CarrierTrackingProvider` interface + `TrackingStatus` (separate from `CarrierRateProvider` — corrected stale reference)
- `StubCarrierTrackingConfiguration` (`@Profile("local")`) with UPS/FedEx/USPS stubs
- `InventoryChecker` interface + `ShopifyInventoryCheckAdapter` (`@CircuitBreaker`, `@Retry`)
- `RefundProvider` interface (with `paymentIntentId` parameter) + `StripeRefundAdapter` (`@CircuitBreaker`, `@Retry`, URL-encoded request body)
- `NotificationSender` interface + `LoggingNotificationAdapter` (Phase 1 stub, persists to DB)

### Handler Layer
- `OrderController`: POST /api/orders, GET /api/orders/{id}, GET /api/orders/{id}/tracking
- DTOs: `CreateOrderRequest` (includes `paymentIntentId`), `OrderResponse`, `TrackingResponse`

### Persistence
- `V10__orders.sql` migration: orders, return_records, customer_notifications tables with indexes
- `V11__orders_total_amount.sql` migration: adds `total_amount` and `total_currency` columns to orders
- `V12__orders_payment_intent_id.sql` migration: adds `payment_intent_id` column to orders
- `OrderRepository` with custom `countViolations` JPQL query (single OR clause to avoid double-counting)
- `ReturnRecordRepository`, `CustomerNotificationRepository`
- `FulfillmentDataProviderImpl` (`@Primary`): real implementation of `VendorFulfillmentDataProvider`, replaces stub

### Stale References Corrected
- `CarrierRateProvider` reuse for tracking → separate `CarrierTrackingProvider` interface
- `reverseLogisticsCost` → `returnHandlingCost`

### Bugs Fixed (PR #10 Review — see PM-003)

| Bug | Issue | Fix |
|---|---|---|
| 1 | `VendorSlaBreachRefunder` used `Money.of(BigDecimal.ZERO, Currency.USD)` as refund amount | Added `totalAmount`/`totalCurrency` to `Order`, use `order.totalAmount()` with `require(amount > 0)` guard |
| 2 | `FulfillmentDataProviderImpl` double-counted orders that were both delayed and refunded | Replaced two separate COUNT queries with single JPQL OR query |
| 3 | `StripeRefundAdapter` missing required `payment_intent` parameter for Stripe API | Added `paymentIntentId` to `Order` entity and threaded through to Stripe request |
| 4 | `OrderService` methods accepted any order status without guards | Added `VALID_TRANSITIONS` map to `Order` and `require()` guards in service methods |
| 5 | `StripeRefundAdapter` interpolated user input into form body without URL-encoding | URL-encode `paymentIntentId` and `orderId` before interpolation |

## Files Modified

### New Source Files (23)
| File | Description |
|---|---|
| `modules/fulfillment/src/main/kotlin/.../domain/Order.kt` | Order aggregate root with state machine |
| `modules/fulfillment/src/main/kotlin/.../domain/OrderStatus.kt` | Status enum |
| `modules/fulfillment/src/main/kotlin/.../domain/ShipmentDetails.kt` | Embeddable shipment tracking |
| `modules/fulfillment/src/main/kotlin/.../domain/ReturnRecord.kt` | Return tracking entity |
| `modules/fulfillment/src/main/kotlin/.../domain/CustomerNotification.kt` | Notification log entity |
| `modules/fulfillment/src/main/kotlin/.../domain/service/CreateOrderCommand.kt` | Command DTO |
| `modules/fulfillment/src/main/kotlin/.../domain/service/OrderService.kt` | Order lifecycle service with status guards |
| `modules/fulfillment/src/main/kotlin/.../domain/service/ShipmentTracker.kt` | Scheduled carrier polling with status guard |
| `modules/fulfillment/src/main/kotlin/.../domain/service/DelayAlertService.kt` | Delay detection + notification |
| `modules/fulfillment/src/main/kotlin/.../domain/service/VendorSlaBreachRefunder.kt` | Auto-refund on SLA breach with amount validation |
| `modules/fulfillment/src/main/kotlin/.../proxy/carrier/CarrierTrackingProvider.kt` | Tracking interface + TrackingStatus |
| `modules/fulfillment/src/main/kotlin/.../proxy/carrier/StubCarrierTrackingConfiguration.kt` | Local profile stubs |
| `modules/fulfillment/src/main/kotlin/.../proxy/inventory/InventoryCheckAdapter.kt` | Shopify inventory check |
| `modules/fulfillment/src/main/kotlin/.../proxy/payment/StripeRefundAdapter.kt` | Stripe refund with URL-encoded params |
| `modules/fulfillment/src/main/kotlin/.../proxy/notification/NotificationAdapter.kt` | Notification stub |
| `modules/fulfillment/src/main/kotlin/.../persistence/OrderRepository.kt` | Order JPA repository with OR violation query |
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
| `.../domain/service/OrderServiceTest.kt` | 12 tests (includes 3 invalid-transition tests) |
| `.../domain/service/ShipmentTrackerTest.kt` | 8 tests |
| `.../domain/service/DelayAlertServiceTest.kt` | 4 tests |
| `.../domain/service/VendorSlaBreachRefunderTest.kt` | 6 tests |
| `.../persistence/FulfillmentDataProviderImplTest.kt` | 4 tests |
| `.../handler/OrderControllerTest.kt` | 6 tests |
| `.../integration/OrderLifecycleTest.kt` | 4 tests |

### Migrations (3)
| File | Description |
|---|---|
| `modules/app/src/main/resources/db/migration/V10__orders.sql` | orders, return_records, customer_notifications tables |
| `modules/app/src/main/resources/db/migration/V11__orders_total_amount.sql` | total_amount, total_currency columns |
| `modules/app/src/main/resources/db/migration/V12__orders_payment_intent_id.sql` | payment_intent_id column |

### Other Modified Files
| File | Change |
|---|---|
| `modules/fulfillment/build.gradle.kts` | Added vendor dep, resilience4j, test deps, JPA plugin |
| `feature-requests/FR-008-fulfillment-orchestration/spec.md` | Fixed stale CarrierRateProvider reference |
| `docs/postmortems/PM-003-phantom-refunds-inflated-violations.md` | Post-mortem for 5 bugs found during PR review |

## Testing Completed

- **44 tests total**, all passing
- **Unit tests (30):** OrderService (12, including 3 invalid-transition tests), ShipmentTracker (8), VendorSlaBreachRefunder (6), DelayAlertService (4)
- **Persistence tests (4):** FulfillmentDataProviderImpl
- **Controller tests (6):** OrderController via MockMvc
- **Integration/lifecycle tests (4):** Full order lifecycle, SLA breach refund flow, inventory rejection, delay detection
- **Full project build:** `./gradlew build` passes (all modules)

## Deployment Notes

- Run `./gradlew flywayMigrate` to apply V10, V11, and V12 migrations
- The `FulfillmentDataProviderImpl` is annotated `@Primary` and will automatically replace the `StubFulfillmentDataProvider` from the vendor module
- External adapters (Shopify, Stripe, carrier tracking) require API credentials configured in application properties; `@Profile("local")` stubs are active for local development
- `ShipmentTracker` scheduling requires `@EnableScheduling` on the application class (already present from FR-007)
