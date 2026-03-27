# FR-025: CJ Supplier Order Placement -- Implementation Summary

## Feature Summary

FR-025 bridges the gap between internal order confirmation and external supplier fulfillment. When an order transitions from PENDING to CONFIRMED via `OrderService.routeToVendor()`, a `SupplierOrderPlacementListener` automatically places a purchase order with CJ Dropshipping via their `createOrderV2` API. The shipping address flows from Shopify webhook through the Order entity to the CJ API request. On success, the CJ order ID is stored on the Order. On failure (after Resilience4j retries), the order transitions to FAILED with a recorded reason.

Key structural enforcement: `CreateOrderCommand.quantity` is a required `Int` with no default value, making it impossible to accidentally hardcode quantity=1 (the bug from PR #37).

## Changes Made

### Database
- Flyway migration V21: adds `quantity`, 10 shipping address columns, `supplier_order_id`, `failure_reason` to `orders` table; creates `supplier_product_mappings` table

### Shared Module
- `OrderConfirmed` domain event published by `routeToVendor()`

### Fulfillment Domain
- `Order` entity: new fields (`quantity`, `shippingAddress`, `supplierOrderId`, `failureReason`); `CONFIRMED -> FAILED` transition added
- `OrderStatus.FAILED`: terminal state for supplier failures
- `ShippingAddress` embeddable: 10 nullable fields matching CJ + Shopify address model
- `ChannelShippingAddress` data class with `toShippingAddress()` extension
- `CreateOrderCommand`: `quantity: Int` as required parameter (no default)

### Fulfillment Services
- `OrderService`: `routeToVendor()` publishes `OrderConfirmed`; new `markFailed()` and `setSupplierOrderId()` methods
- `ShopifyOrderAdapter`: extracts `shipping_address` using Jackson `get()` (CLAUDE.md #15)
- `LineItemOrderCreator`: threads `quantity` and `shippingAddress` through

### Fulfillment Proxy
- `SupplierOrderAdapter` interface + request/result data classes
- `CjOrderAdapter`: `@Retry` + `@CircuitBreaker`, does NOT catch `RestClientException` (CLAUDE.md #18)
- `StubCjOrderConfiguration`: `@Profile("local")` stub
- `SupplierProductMappingResolver`: native SQL lookup

### Fulfillment Handler
- `SupplierOrderPlacementListener`: `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` (CLAUDE.md #6); idempotency guard; resolves CJ variant; calls adapter; handles success/failure

### API Layer
- `OrderResponse`: includes `quantity`, `supplierOrderId`, `failureReason`
- `CreateOrderRequest`: includes `quantity` (default 1 for REST backward compatibility)

### Configuration
- `application.yml`: CJ order config + Resilience4j `cj-order` circuit breaker + retry

## Files Modified

### Production Code (Phase 4 created, Phase 5 finalized)
- `modules/app/src/main/resources/db/migration/V21__supplier_order_placement.sql`
- `modules/app/src/main/resources/application.yml`
- `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderConfirmed.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/Order.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/OrderStatus.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/ShippingAddress.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelOrder.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelShippingAddress.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ShopifyOrderAdapter.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/CreateOrderCommand.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/LineItemOrderCreator.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/SupplierOrderAdapter.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjOrderAdapter.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/StubCjOrderConfiguration.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/SupplierProductMappingResolver.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/SupplierOrderPlacementListener.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/OrderController.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/dto/OrderResponse.kt`
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/dto/CreateOrderRequest.kt`
- `modules/fulfillment/build.gradle.kts`

### Test Code (Phase 5 updates)
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/SupplierOrderPlacementListenerTest.kt` -- upgraded from Phase 4 stubs to real listener tests
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/OrderServiceSupplierTest.kt` -- upgraded from Phase 4 stubs to real service method tests
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/OrderFailedStatusTest.kt` -- added `quantity = 1`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/OrderConfirmedEventTest.kt` -- added `quantity = 1`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/OrderServiceTest.kt` -- added `quantity = 1`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/LineItemOrderCreatorTest.kt` -- added `quantity` to Order constructors
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/QuantityFlowThroughTest.kt` -- added `quantity = 5`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/VendorSlaBreachRefunderTest.kt` -- added `quantity = 1`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/ShipmentTrackerTest.kt` -- added `quantity = 1`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/DelayAlertServiceTest.kt` -- added `quantity = 1`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/OrderControllerTest.kt` -- added `quantity = 1`
- `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/integration/OrderLifecycleTest.kt` -- added `quantity`, fixed event verification
- `modules/app/src/test/kotlin/com/autoshipper/vendor/VendorSlaMonitorIntegrationTest.kt` -- added `quantity = 1`
- `modules/app/src/test/kotlin/com/autoshipper/fulfillment/OrderTransitionIntegrationTest.kt` -- added mocks for supplier order components

## Testing Completed

All tests pass: `./gradlew build` succeeds with 0 failures across all modules.

### Phase 4 Tests (65 tests across these test files)
- `OrderFailedStatusTest` -- CONFIRMED -> FAILED transition, FAILED is terminal
- `OrderConfirmedEventTest` -- routeToVendor publishes OrderConfirmed
- `OrderServiceSupplierTest` -- markFailed, setSupplierOrderId methods
- `SupplierOrderPlacementListenerTest` -- happy path, idempotency, error handling, quantity flow, address flow
- `CjOrderAdapterTest` -- WireMock contract test against CJ createOrderV2 API
- `CjOrderAdapterResilienceTest` -- retry/circuit breaker annotation verification
- `ShopifyOrderAdapterTest` -- shipping address extraction from webhook JSON
- `LineItemOrderCreatorTest` -- quantity and address flow-through
- `QuantityFlowThroughTest` -- end-to-end data lineage for quantity
- `OrderControllerTest` -- API response includes new fields
- `OrderLifecycleTest` -- full lifecycle with quantity and event publishing

### Existing Tests (all pass without regression)
- All catalog, pricing, vendor, capital, portfolio, compliance, shared module tests pass
- Integration tests (VendorSlaMonitor, OrderTransition) pass with added mocks

## Deployment Notes

### Database Migration
- V21 migration adds columns to `orders` table. The `quantity` column is added with `DEFAULT 1` for existing rows, then the default is dropped. All new inserts must provide a value.
- The `supplier_product_mappings` table must be seeded with CJ variant IDs for each SKU before supplier order placement will succeed. Without a mapping, orders transition to FAILED with a descriptive reason (graceful degradation).

### Configuration
- Set `CJ_ACCESS_TOKEN` environment variable for production CJ API access
- `CJ_LOGISTIC_NAME` defaults to `CJPacket`, `CJ_FROM_COUNTRY_CODE` defaults to `CN`

### Rollback Strategy
- To disable CJ order placement without a code change: set `CJ_ACCESS_TOKEN` to blank (adapter's credential guard transitions orders to FAILED, visible in API)
- Alternatively, empty the `supplier_product_mappings` table (missing mapping causes FAILED with descriptive reason)

### Profile Behavior
- `local` profile: `StubCjOrderConfiguration` provides a stub adapter returning deterministic IDs
- All other profiles: `CjOrderAdapter` with real API calls (requires credentials)
