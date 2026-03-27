# FR-025: CJ Supplier Order Placement — Test Manifest

## Test File Inventory

| # | File | Module | Tests | Category |
|---|------|--------|-------|----------|
| 1 | `modules/shared/src/test/kotlin/com/autoshipper/shared/events/OrderConfirmedTest.kt` | shared | 4 | Domain Event |
| 2 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/OrderFailedStatusTest.kt` | fulfillment | 7 | Boundary Condition |
| 3 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/ShippingAddressTest.kt` | fulfillment | 3 | Data Type |
| 4 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelShippingAddressTest.kt` | fulfillment | 3 | Data Type |
| 5 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/channel/ShopifyOrderAdapterShippingTest.kt` | fulfillment | 7 | Data Lineage / Shipping |
| 6 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/OrderConfirmedEventTest.kt` | fulfillment | 2 | End-to-End Flow |
| 7 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/OrderServiceSupplierTest.kt` | fulfillment | 4 | Domain Service |
| 8 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/QuantityFlowThroughTest.kt` | fulfillment | 5 | Data Lineage / Quantity |
| 9 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/ShippingAddressFlowThroughTest.kt` | fulfillment | 6 | Data Lineage / Shipping |
| 10 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/SupplierOrderPlacementListenerTest.kt` | fulfillment | 6 | End-to-End Flow |
| 11 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjOrderAdapterWireMockTest.kt` | fulfillment | 9 | WireMock Contract |
| 12 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjOrderAdapterResilienceTest.kt` | fulfillment | 4 | Resilience |
| 13 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/supplier/SupplierOrderAdapterContractTest.kt` | fulfillment | 5 | Interface Contract |

**Total: 13 files, 65 tests**

## End-to-End Flow Tests

- `OrderConfirmedEventTest` — routeToVendor publishes OrderConfirmed event (SC-1, SC-7)
- `SupplierOrderPlacementListenerTest` — OrderConfirmed -> listener -> CJ adapter -> supplierOrderId stored (SC-1, SC-2, SC-7)

## Boundary Condition Tests

- `OrderFailedStatusTest` — CONFIRMED -> FAILED valid, PENDING -> FAILED invalid, FAILED is terminal (BR-5, SC-4)
- `SupplierOrderPlacementListenerTest` — missing mapping -> FAILED, CJ error -> FAILED, idempotency skip (BR-5, BR-7, NFR-2)

## Dependency Contract Tests

### WireMock Contract Tests (CJ Dropshipping API)
- `CjOrderAdapterWireMockTest` — CJ API request body format, CJ-Access-Token header, error responses, HTTP 500/429 (SC-3, SC-6)

### Supplier Adapter Interface Contract
- `SupplierOrderAdapterContractTest` — interface is generic, request/result data types correct, orderNumber deduplication (BR-6, NFR-2)

## Data Lineage Tests
- `QuantityFlowThroughTest` — quantity=5 at Shopify webhook -> 5 at CJ request (4 pipeline stages) (BR-3, NFR-3, SC-8)
- `ShippingAddressFlowThroughTest` — all 8 CJ-required fields survive 3 pipeline stages (BR-2, NFR-4)
- `ShopifyOrderAdapterShippingTest` — shipping address extraction, first_name+last_name combination (BR-2)

## Resilience Tests
- `CjOrderAdapterResilienceTest` — @Retry present, @CircuitBreaker present, RestClientException propagates (NFR-1, SC-9)

## Domain Type Tests
- `OrderConfirmedTest` — implements DomainEvent, carries orderId/skuId, follows OrderFulfilled pattern
- `ShippingAddressTest` — construction, nullable defaults, partial construction
- `ChannelShippingAddressTest` — construction, all-nullable, data class equality/copy

## Domain Service Tests
- `OrderServiceSupplierTest` — markFailed() and setSupplierOrderId() methods (BR-4, BR-5)
- `SupplierOrderAdapterContractTest` — interface is generic, request/result data types correct

## Spec Traceability

| Spec Requirement | Test(s) |
|---|---|
| BR-1: Automatic supplier order on confirmation | `OrderConfirmedEventTest`, `SupplierOrderPlacementListenerTest::onOrderConfirmed places CJ order` |
| BR-2: Shipping address capture and flow-through | `ShopifyOrderAdapterShippingTest`, `ShippingAddressFlowThroughTest`, `ChannelShippingAddressTest` |
| BR-3: Order quantity flow-through | `QuantityFlowThroughTest` (5 tests), `CjOrderAdapterWireMockTest::quantity 3/5` |
| BR-4: CJ order ID cross-reference | `SupplierOrderPlacementListenerTest::stores supplier order ID`, `SupplierOrderAdapterContractTest::SupplierOrderResult` |
| BR-5: Graceful error handling with FAILED state | `OrderFailedStatusTest` (7 tests), `SupplierOrderPlacementListenerTest::FAILED on error` |
| BR-6: Supplier abstraction | `SupplierOrderAdapterContractTest::is an interface`, `CjOrderAdapterResilienceTest::interface is generic` |
| BR-7: Product-to-supplier variant mapping | `SupplierOrderPlacementListenerTest::missing mapping -> FAILED` |
| SC-1: OrderConfirmed triggers CJ placement | `OrderConfirmedEventTest`, `SupplierOrderPlacementListenerTest` |
| SC-2: CJ order ID stored | `SupplierOrderPlacementListenerTest::stores supplier order ID` |
| SC-3: Shipping mapped to CJ format | `CjOrderAdapterWireMockTest::request body contains correct fields` |
| SC-4: Error handling (FAILED, not lost) | `OrderFailedStatusTest`, `CjOrderAdapterWireMockTest::error responses` |
| SC-5: SupplierOrderAdapter interface | `SupplierOrderAdapterContractTest` |
| SC-6: WireMock contract test | `CjOrderAdapterWireMockTest` (9 tests) |
| SC-7: Full confirmed -> CJ chain | `OrderConfirmedEventTest`, `SupplierOrderPlacementListenerTest::happy path` |
| SC-8: Quantity flows through correctly | `QuantityFlowThroughTest` (end-to-end), `CjOrderAdapterWireMockTest::quantity 3/5` |
| SC-9: Resilience4j retry/circuit breaker | `CjOrderAdapterResilienceTest` (4 tests) |
| NFR-1: Resilience (retry, circuit breaker) | `CjOrderAdapterResilienceTest` |
| NFR-2: Idempotency | `SupplierOrderPlacementListenerTest::skips when supplierOrderId set`, `SupplierOrderAdapterContractTest::orderNumber deduplication` |
| NFR-3: Data lineage — quantity | `QuantityFlowThroughTest` |
| NFR-4: Data lineage — shipping address | `ShippingAddressFlowThroughTest` |
| NFR-5: Event-driven transaction safety | `OrderConfirmedEventTest` (event publishing verification) |
| NFR-6: Configuration over hardcoding | `CjOrderAdapterWireMockTest::blank credentials` |

## Data Lineage Traceability

### Quantity Pipeline (PR #37 prevention)

| Stage | Source | Test |
|---|---|---|
| Shopify JSON -> ChannelLineItem.quantity | `orders-create-webhook-quantity-5.json` | `QuantityFlowThroughTest::stage 1` |
| ChannelLineItem.quantity -> CreateOrderCommand.quantity | LineItemOrderCreator | `QuantityFlowThroughTest::stage 2` |
| CreateOrderCommand.quantity -> Order.quantity | OrderService.create() | `QuantityFlowThroughTest::stage 3` |
| Order.quantity -> SupplierOrderRequest.products[].quantity | Listener | `QuantityFlowThroughTest::stage 4` |
| End-to-end: 5 at input, 5 at output | Full pipeline | `QuantityFlowThroughTest::end-to-end` |

### Shipping Address Pipeline

| Stage | Source | Test |
|---|---|---|
| Shopify JSON -> ChannelShippingAddress | ShopifyOrderAdapter.parse() | `ShopifyOrderAdapterShippingTest` |
| first_name + last_name -> customerName | Shopify name combination | `ShippingAddressFlowThroughTest::customerName combines` |
| ChannelShippingAddress -> ShippingAddress | toShippingAddress() mapping | `ShippingAddressFlowThroughTest::stage 2` |
| ShippingAddress -> SupplierOrderRequest | Listener | `ShippingAddressFlowThroughTest::stage 3` |
| All 8 CJ fields present | End-to-end | `ShippingAddressFlowThroughTest::end-to-end all 8` |

## Test Fixtures

| Fixture | Location | Purpose |
|---|---|---|
| `orders-create-webhook-with-shipping.json` | `fulfillment/src/test/resources/shopify/` | Shopify webhook with full shipping address, quantity=3 |
| `orders-create-webhook-quantity-5.json` | `fulfillment/src/test/resources/shopify/` | Shopify webhook with quantity=5 for pipeline test |
| `create-order-success.json` | `fulfillment/src/test/resources/wiremock/cj/` | CJ createOrderV2 success response |
| `create-order-error-product-unavailable.json` | `fulfillment/src/test/resources/wiremock/cj/` | CJ error: product unavailable |
| `create-order-error-auth.json` | `fulfillment/src/test/resources/wiremock/cj/` | CJ error: invalid access token |

## Minimal Type Definitions Created (Main Source)

These are skeleton types created in Phase 4 so tests compile against real signatures:

| Type | File | Purpose |
|---|---|---|
| `OrderConfirmed` | `modules/shared/src/main/kotlin/.../events/OrderConfirmed.kt` | Domain event (implements DomainEvent) |
| `OrderStatus.FAILED` | `modules/fulfillment/src/main/kotlin/.../domain/OrderStatus.kt` | New terminal state (enum value added) |
| `ShippingAddress` | `modules/fulfillment/src/main/kotlin/.../domain/ShippingAddress.kt` | @Embeddable with 10 nullable fields |
| `ChannelShippingAddress` | `modules/fulfillment/src/main/kotlin/.../domain/channel/ChannelShippingAddress.kt` | Channel-agnostic shipping data class |
| `SupplierOrderAdapter` | `modules/fulfillment/src/main/kotlin/.../proxy/supplier/SupplierOrderAdapter.kt` | Interface + SupplierOrderRequest, SupplierOrderProduct, SupplierOrderResult |

## Expected Failures

All 65 tests compile. The following tests will FAIL at runtime until Phase 5 implementation:

| Test | Expected Failure Reason |
|---|---|
| `OrderFailedStatusTest::CONFIRMED can transition to FAILED` | `Order.VALID_TRANSITIONS` does not yet include `CONFIRMED -> FAILED` |
| `OrderFailedStatusTest::FAILED is terminal (4 tests)` | `Order.VALID_TRANSITIONS` does not yet include `FAILED -> emptySet()` |
| `OrderFailedStatusTest::PENDING cannot transition to FAILED` | Will actually PASS (no FAILED in transitions means the error path is "No transitions defined" or already works because PENDING doesn't include FAILED) |
| `OrderFailedStatusTest::SHIPPED cannot transition to FAILED` | May PASS depending on how VALID_TRANSITIONS handles unknown values |
| `OrderConfirmedEventTest::routeToVendor publishes OrderConfirmed` | routeToVendor() does not yet call eventPublisher.publishEvent(OrderConfirmed) |
| `OrderServiceSupplierTest::markFailed/setSupplierOrderId` | Methods do not exist yet on OrderService |
| `ShopifyOrderAdapterShippingTest::parse extracts shipping address` | ShopifyOrderAdapter.parse() does not yet extract shipping_address |
| `CjOrderAdapterWireMockTest` | CjOrderAdapter class does not exist yet |
| `CjOrderAdapterResilienceTest` | CjOrderAdapter class does not exist yet |

## Compilation Status

```
./gradlew compileTestKotlin — BUILD SUCCESSFUL
```

All 13 test files, 65 tests compile successfully against minimal type stubs.

## PM-017 Prevention Compliance

1. **quantity never hardcoded to 1** — `QuantityFlowThroughTest` uses quantity=3 and quantity=5 throughout; any hardcoded `1` would fail these tests
2. **RestClientException must propagate** — `CjOrderAdapterResilienceTest` will verify via reflection in Phase 5 that `placeOrder()` does not catch `RestClientException`
