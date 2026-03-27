# FR-025: CJ Supplier Order Placement -- Implementation Summary

## Feature Summary

When an order is confirmed via `OrderService.routeToVendor()`, an `OrderConfirmed` domain event is published. A `@TransactionalEventListener(AFTER_COMMIT)` listener (`SupplierOrderPlacementListener`) picks up the event in a `REQUIRES_NEW` transaction, resolves the SKU's CJ variant ID via `SupplierProductMappingRepository`, and delegates to a `SupplierOrderAdapter` implementation (`CjOrderAdapter`) to place the purchase order via CJ's `createOrderV2` API. On success, the CJ order ID is stored on the `Order` entity. On failure, the order transitions to `FAILED` with a categorized failure reason.

## Changes Made

### Shared Module
- **`OrderConfirmed`** domain event -- carries `orderId` and `skuId`, implements `DomainEvent` sealed interface

### Database Migration
- **`V21__supplier_order_placement.sql`** -- adds shipping address columns, `supplier_order_id`, `failure_reason` to `orders` table; creates `supplier_product_mappings` table with unique index on `(sku_id, supplier)`

### Fulfillment Domain
- **`ShippingAddress`** -- `@Embeddable` value type with 8 nullable fields (customerName, address, city, province, country, countryCode, zip, phone)
- **`OrderStatus.FAILED`** -- new terminal state added to enum
- **`Order` entity** -- added `shippingAddress` (embedded), `supplierOrderId`, `failureReason` fields; updated `VALID_TRANSITIONS` to allow `CONFIRMED -> FAILED` and define `FAILED -> emptySet()`
- **`SupplierProductMapping`** -- JPA entity implementing `Persistable<UUID>` (CLAUDE.md #16) mapping SKU to supplier variant ID
- **`SupplierOrderAdapter`** interface, `SupplierOrderRequest`, `SupplierOrderResult` (sealed class), `FailureReason` enum -- supplier-agnostic order placement contract
- **`ChannelShippingAddress`** data class added to `ChannelOrder.kt`; `ChannelOrder` gains optional `shippingAddress` field

### Fulfillment Domain Services
- **`ShopifyOrderAdapter`** -- extracts `shipping_address` from webhook JSON using `get()` (CLAUDE.md #15)
- **`CreateOrderCommand`** -- added optional `shippingAddress: ShippingAddress?` field
- **`LineItemOrderCreator`** -- maps `ChannelShippingAddress` to `ShippingAddress`, combining `address1`/`address2` with comma separator
- **`OrderService.create()`** -- sets shipping address on order from command
- **`OrderService.routeToVendor()`** -- publishes `OrderConfirmed` event after save
- **`SupplierOrderPlacementListener`** -- event-driven supplier order placement with idempotency, address validation, adapter resolution, success/failure handling, Micrometer metrics

### Fulfillment Proxy
- **`CjOrderAdapter`** -- `@Profile("!local")`, `@CircuitBreaker`, `@Retry`, CJ-Access-Token header, `@Value` with empty defaults (CLAUDE.md #13), JSON `code` field inspection (CJ returns HTTP 200 for errors)
- **`StubSupplierOrderConfiguration`** -- `@Profile("local")` stub that always returns success

### Fulfillment Persistence
- **`SupplierProductMappingRepository`** -- JPA repository with `findBySkuId` and `findBySkuIdAndSupplier`

## Files Modified

### New Files (12)
| File | Purpose |
|---|---|
| `modules/shared/src/main/kotlin/.../events/OrderConfirmed.kt` | Domain event |
| `modules/app/src/main/resources/db/migration/V21__supplier_order_placement.sql` | Schema migration |
| `modules/fulfillment/src/main/kotlin/.../domain/ShippingAddress.kt` | Embeddable value type |
| `modules/fulfillment/src/main/kotlin/.../domain/SupplierProductMapping.kt` | JPA entity |
| `modules/fulfillment/src/main/kotlin/.../domain/supplier/SupplierOrderAdapter.kt` | Interface + types |
| `modules/fulfillment/src/main/kotlin/.../persistence/SupplierProductMappingRepository.kt` | JPA repository |
| `modules/fulfillment/src/main/kotlin/.../domain/service/SupplierOrderPlacementListener.kt` | Event listener |
| `modules/fulfillment/src/main/kotlin/.../proxy/supplier/CjOrderAdapter.kt` | CJ API client |
| `modules/fulfillment/src/main/kotlin/.../proxy/supplier/StubSupplierOrderConfiguration.kt` | Local stub |

### Modified Files (9)
| File | Change |
|---|---|
| `modules/fulfillment/src/main/kotlin/.../domain/OrderStatus.kt` | Added `FAILED` |
| `modules/fulfillment/src/main/kotlin/.../domain/Order.kt` | Added fields + transitions |
| `modules/fulfillment/src/main/kotlin/.../domain/channel/ChannelOrder.kt` | Added `ChannelShippingAddress` + field |
| `modules/fulfillment/src/main/kotlin/.../domain/channel/ShopifyOrderAdapter.kt` | Shipping address extraction |
| `modules/fulfillment/src/main/kotlin/.../domain/service/CreateOrderCommand.kt` | Added `shippingAddress` |
| `modules/fulfillment/src/main/kotlin/.../domain/service/LineItemOrderCreator.kt` | Address mapping |
| `modules/fulfillment/src/main/kotlin/.../domain/service/OrderService.kt` | Event publishing + address |
| `modules/fulfillment/build.gradle.kts` | Added WireMock test dependency |
| `modules/app/src/test/kotlin/.../OrderTransitionIntegrationTest.kt` | MockBean for listener |

### Deleted Files (4)
- `modules/fulfillment/src/test/kotlin/.../stub/OrderConfirmedStub.kt`
- `modules/fulfillment/src/test/kotlin/.../stub/SupplierOrderTypes.kt`
- `modules/fulfillment/src/test/kotlin/.../stub/ShippingAddressStub.kt`
- `modules/fulfillment/src/test/kotlin/.../stub/SupplierProductMappingStub.kt`

### Updated Test Files (5)
| File | Tests |
|---|---|
| `SupplierOrderPlacementFlowTest.kt` | 3 tests -- happy path, address mapping, full flow |
| `SupplierOrderBoundaryTest.kt` | 8 tests -- all error paths, idempotency, state transitions |
| `CjOrderAdapterContractTest.kt` | 9 tests -- fixture parsing, blank credentials, supplier name |
| `ShopifyShippingAddressExtractionTest.kt` | 5 tests -- address extraction, null handling, fixture compat |
| `OrderLifecycleTest.kt` | Updated to verify both OrderConfirmed and OrderFulfilled events |

## Testing Completed

- **All 25 FR-025 tests pass** (SupplierOrderPlacementFlowTest: 3, SupplierOrderBoundaryTest: 8, CjOrderAdapterContractTest: 9, ShopifyShippingAddressExtractionTest: 5)
- **All existing tests pass** -- full project `./gradlew test` is green (no regressions)
- **ArchUnit rules pass** -- `SupplierProductMapping` correctly implements `Persistable<UUID>` (Rule 3)

## CLAUDE.md Constraints Verified

| # | Constraint | Applied |
|---|---|---|
| 6 | `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` | `SupplierOrderPlacementListener.onOrderConfirmed()` |
| 7 | Explicit transition maps | `CONFIRMED -> FAILED` added, `FAILED -> emptySet()` |
| 13 | `@Value` with empty defaults | `CjOrderAdapter` uses `${key:}` syntax |
| 14 | No `internal` constructor on `@Component` | All `@Component` classes use public constructors |
| 15 | Jackson `get()` not `path()` | `CjOrderAdapter` and `ShopifyOrderAdapter` use `get()` |
| 16 | `Persistable<T>` for assigned IDs | `SupplierProductMapping` with `@Transient isNew` |

## Deployment Notes

- **Migration**: `V21__supplier_order_placement.sql` must run before application starts -- all new columns are nullable, no backfill needed
- **Configuration**: `cj-dropshipping.api.base-url` and `cj-dropshipping.api.access-token` must be set for CJ API access (already in `application.yml` with `${CJ_ACCESS_TOKEN:}` defaults)
- **Resilience4j**: `cj-order` circuit breaker and retry instances should be configured in `application.yml` for production tuning
- **Local profile**: `StubSupplierOrderConfiguration` provides a stub adapter for `@Profile("local")` that always returns success
- **Prerequisite**: `SupplierProductMapping` records must be populated for each SKU before orders can be placed with CJ -- this is a manual/pipeline step that connects the demand scan pipeline to the order placement pipeline
