# FR-025: CJ Supplier Order Placement — Summary

## Feature Summary

Automatic supplier order placement via CJ Dropshipping API when an order is confirmed. This closes the zero-capital model loop: customer payment funds the supplier order with no inventory ownership. Event-driven architecture ensures the CJ order is placed as an after-commit side effect of order confirmation, with graceful failure handling and idempotency.

## Changes Made

### Shared Module
- **OrderConfirmed domain event** — new cross-module event published when an order transitions to CONFIRMED

### Domain Layer
- **OrderStatus.FAILED** — new terminal state for supplier rejection, with transitions from CONFIRMED and PENDING
- **ShippingAddress @Embeddable** — 10-field value object embedded on orders (follows ShipmentDetails pattern)
- **Order entity extensions** — `quantity`, `supplierOrderId`, `failureReason`, `shippingAddress` fields
- **ChannelShippingAddress** — normalized shipping address from channel webhooks (11 fields)
- **CreateOrderCommand** — extended with `quantity` (required, no default) and `shippingAddress`
- **SupplierOrderPlacementService** — orchestrates supplier order placement with idempotency guard, mapping resolution, adapter invocation, and failure handling

### Proxy Layer
- **SupplierOrderAdapter interface** — abstraction for future suppliers (SupplierOrderRequest/SupplierOrderResult sealed class)
- **CjSupplierOrderAdapter** — CJ Dropshipping API integration with @CircuitBreaker/@Retry, credential guard, NullNode guard on all response fields
- **StubSupplierOrderConfiguration** — local profile stub returning success
- **SupplierProductMappingResolver** — cross-module EntityManager query mapping SKU to CJ variant ID

### Handler/Service Layer
- **SupplierOrderPlacementListener** — @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW) per CLAUDE.md #6
- **OrderService.routeToVendor()** — now publishes OrderConfirmed event
- **OrderService.create()** — persists quantity and shippingAddress
- **LineItemOrderCreator** — maps ChannelShippingAddress to ShippingAddress, passes quantity, auto-confirms Shopify orders
- **ShopifyOrderAdapter** — extracts shipping_address with NullNode guard on all 11 fields (PR #39 fix)
- **ShopifyOrderProcessingService** — passes shippingAddress through to LineItemOrderCreator
- **OrderResponse DTO** — includes quantity, supplierOrderId, failureReason, shipping fields

### Persistence
- **V21 migration** — quantity column, supplier order tracking fields, 10 shipping address columns, supplier_product_mappings table

## Files Modified

| File | Change |
|------|--------|
| `modules/shared/src/main/kotlin/.../events/OrderConfirmed.kt` | New |
| `modules/app/src/main/resources/db/migration/V21__supplier_order_placement.sql` | New |
| `modules/fulfillment/src/main/kotlin/.../domain/OrderStatus.kt` | Added FAILED |
| `modules/fulfillment/src/main/kotlin/.../domain/Order.kt` | New fields, FAILED transitions |
| `modules/fulfillment/src/main/kotlin/.../domain/ShippingAddress.kt` | New |
| `modules/fulfillment/src/main/kotlin/.../domain/channel/ChannelOrder.kt` | ChannelShippingAddress + field |
| `modules/fulfillment/src/main/kotlin/.../domain/service/CreateOrderCommand.kt` | quantity + shippingAddress |
| `modules/fulfillment/src/main/kotlin/.../domain/service/SupplierOrderPlacementService.kt` | New |
| `modules/fulfillment/src/main/kotlin/.../domain/service/OrderService.kt` | Event publishing, create update |
| `modules/fulfillment/src/main/kotlin/.../domain/service/LineItemOrderCreator.kt` | Address mapping, quantity, auto-confirm |
| `modules/fulfillment/src/main/kotlin/.../domain/service/ShopifyOrderProcessingService.kt` | Pass shippingAddress |
| `modules/fulfillment/src/main/kotlin/.../domain/channel/ShopifyOrderAdapter.kt` | Shipping extraction + NullNode fixes |
| `modules/fulfillment/src/main/kotlin/.../proxy/supplier/SupplierOrderAdapter.kt` | New |
| `modules/fulfillment/src/main/kotlin/.../proxy/supplier/CjSupplierOrderAdapter.kt` | New |
| `modules/fulfillment/src/main/kotlin/.../proxy/supplier/StubSupplierOrderConfiguration.kt` | New |
| `modules/fulfillment/src/main/kotlin/.../proxy/supplier/SupplierProductMappingResolver.kt` | New |
| `modules/fulfillment/src/main/kotlin/.../handler/SupplierOrderPlacementListener.kt` | New |
| `modules/fulfillment/src/main/kotlin/.../handler/OrderController.kt` | toResponse update |
| `modules/fulfillment/src/main/kotlin/.../handler/dto/OrderResponse.kt` | New fields |
| `modules/fulfillment/src/main/kotlin/.../handler/dto/CreateOrderRequest.kt` | quantity field |
| `modules/fulfillment/build.gradle.kts` | WireMock test dependency |

## Testing Completed

| Category | Tests | Status |
|----------|-------|--------|
| Order state machine (FAILED transitions) | 8 | PASS |
| SupplierOrderPlacementService unit | 5 | PASS |
| ShopifyOrderAdapter shipping address | 5 | PASS |
| CjSupplierOrderAdapter WireMock contract | 8 | PASS |
| LineItemOrderCreator unit | 5 new + 4 existing | PASS |
| OrderService unit | 1 new + existing | PASS |
| Integration (full chain) | 2 | PASS |
| Existing test fixes | 3 files updated | PASS |
| **Total new tests** | **34** | **ALL PASS** |

All module tests pass (`./gradlew :fulfillment:test` + all other modules). The `:app:test` failures are pre-existing (Spring context requires database).

### PR #37/#39 Regression Coverage
- **Hardcoded quantity (PR #37):** `quantity` is required on CreateOrderCommand with no default — tests verify quantity flows from ChannelLineItem
- **NullNode trap (PR #39):** 5 dedicated tests assert JSON null returns Kotlin null, not string "null". Also fixed 5 pre-existing NullNode violations in ShopifyOrderAdapter
- **Resilience4j swallowed (PR #37):** WireMock test verifies 401 propagates as exception (not caught)
- **Test theater (PR #39):** Zero assert(true), zero fixture-only assertions — every test calls production code

## Deployment Notes

- **Flyway V21** adds columns to `orders` table and creates `supplier_product_mappings` table. The `quantity` column is backfilled with default=1 for existing rows.
- **CJ API credentials** must be configured: `cj-dropshipping.api.base-url` and `cj-dropshipping.api.access-token`. Blank credentials trigger the credential guard (returns Failure, no crash).
- **Supplier product mappings** must be populated for SKUs that should auto-fulfill via CJ. Without a mapping, the order transitions to FAILED with a clear error message.
- **Auto-confirm behavior:** Shopify webhook orders are now auto-confirmed after creation (previously required manual POST /confirm). This triggers the OrderConfirmed event and supplier order placement.
