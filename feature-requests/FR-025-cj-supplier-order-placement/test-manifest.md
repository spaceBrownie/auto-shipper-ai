# FR-025: CJ Supplier Order Placement -- Test Manifest

## Overview

Phase 4 (Test-First Gate) generated 4 test files with 25 test methods across 3 categories, plus 4 WireMock fixture files and 4 compilation stub files. All tests compile (`./gradlew compileTestKotlin` passes). Tests are expected to fail at runtime until Phase 5 implementation is complete.

## Test Files

### End-to-End Flow Tests

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/SupplierOrderPlacementFlowTest.kt`

| Test | Validates | Expected Failure Point |
|---|---|---|
| `order confirmed triggers supplier order placement and stores CJ order ID` | OrderConfirmed event -> listener -> CjOrderAdapter -> supplierOrderId stored | TODO: SupplierOrderPlacementListener not yet implemented |
| `shipping address mapped correctly from order to supplier order request` | ShippingAddress fields map 1:1 to SupplierOrderRequest fields | Passes (data mapping only) |
| `full shopify webhook to CJ order placement flow` | Shopify address1+address2 combined, full event chain | TODO: ShopifyOrderAdapter shipping_address extraction not yet implemented |

**Rationale:** These tests verify the core value proposition -- automated supplier order placement from customer order confirmation. They validate the data contract at each boundary in the chain.

### Boundary Condition Tests

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/SupplierOrderBoundaryTest.kt`

| Test | Validates | Expected Failure Point |
|---|---|---|
| `CJ API out of stock error transitions order to FAILED with OUT_OF_STOCK reason` | Failure result -> order FAILED with exact reason | TODO: OrderStatus.FAILED not yet added |
| `CJ API auth error transitions order to FAILED with API_AUTH_FAILURE reason` | Auth failure -> FAILED with API_AUTH_FAILURE | TODO: Listener failure path |
| `CJ API rate limit returns NETWORK_ERROR failure reason` | Rate limit (1600200) -> NETWORK_ERROR | TODO: CjOrderAdapter rate limit handling |
| `missing supplier product mapping transitions order to FAILED` | No mapping found -> FAILED, adapter NOT called | TODO: Listener missing-mapping path |
| `missing shipping address transitions order to FAILED with INVALID_ADDRESS` | Null/blank address -> FAILED with INVALID_ADDRESS | TODO: Listener address validation |
| `order with existing supplier order ID skips placement` | Idempotency: supplierOrderId non-null -> skip | TODO: Listener idempotency check |
| `order not in CONFIRMED status is rejected for supplier placement` | Non-CONFIRMED status -> skip with warning | TODO: Listener status guard |
| `CONFIRMED to FAILED is a valid order transition` | VALID_TRANSITIONS includes CONFIRMED -> FAILED | TODO: FAILED added to OrderStatus |
| `FAILED is a terminal order status with no valid transitions` | FAILED -> emptySet() in VALID_TRANSITIONS | TODO: FAILED added to OrderStatus |

**Rationale:** Error handling is critical for this feature. Per BR-6, failures must never be silently lost. Each error path must produce a FAILED status with a categorized reason. Idempotency (BR-8) prevents duplicate supplier orders from event redelivery.

### Dependency Contract Tests

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/CjOrderAdapterContractTest.kt`

| Test | Validates | Expected Failure Point |
|---|---|---|
| `successful CJ order creation returns orderId from response` | Parse code=200, extract data.orderId | Passes (fixture parsing) |
| `CJ out-of-stock error returns Failure with OUT_OF_STOCK reason` | code!=200, stock-related message -> OUT_OF_STOCK | Passes (fixture parsing + mapping) |
| `CJ auth error returns Failure with API_AUTH_FAILURE reason` | code=1600001 -> API_AUTH_FAILURE | Passes (fixture parsing + mapping) |
| `CJ rate limit returns Failure with NETWORK_ERROR reason` | code=1600200 -> NETWORK_ERROR | Passes (fixture parsing + mapping) |
| `network timeout produces NETWORK_ERROR failure result` | Timeout -> NETWORK_ERROR | TODO: CjOrderAdapter timeout handling |
| `request body contains all required CJ API fields` | All CJ API fields present in request map | Passes (request construction) |
| `CJ access token header is required for API authentication` | CJ-Access-Token header contract | TODO: CjOrderAdapter implementation |
| `blank credentials returns Failure without calling API` | Blank baseUrl/accessToken -> immediate Failure | Passes (guard logic) |
| `CJ adapter returns CJ_DROPSHIPPING as supplier name` | supplierName() == "CJ_DROPSHIPPING" | TODO: CjOrderAdapter.supplierName() |

**Rationale:** These tests validate the CJ API contract using fixture files from CJ documentation. CJ returns HTTP 200 for ALL responses (errors in JSON `code` field), which is unusual and easy to get wrong. The request body tests ensure all required CJ API fields are included.

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/ShopifyShippingAddressExtractionTest.kt`

| Test | Validates | Expected Failure Point |
|---|---|---|
| `parse extracts shipping address fields from webhook payload` | All address fields extracted | TODO: ChannelOrder.shippingAddress field |
| `parse handles shipping address without address2` | Null address2 handled, no trailing comma | TODO: ChannelOrder.shippingAddress field |
| `parse handles missing shipping address gracefully` | Missing node -> null, no exception | TODO: ChannelOrder.shippingAddress field |
| `parse handles explicit null shipping address` | Null node -> null, no exception | TODO: ChannelOrder.shippingAddress field |
| `existing webhook fixture parses correctly with shipping address` | Backward compatibility with existing fixture | TODO: ChannelOrder.shippingAddress field |

**Rationale:** The shipping address is the critical data that flows from the customer's Shopify order to the CJ supplier API. Missing or malformed addresses cause supplier order failures. Tests cover happy path, partial data, and absence cases.

## WireMock Fixture Files

| File | Based On | Purpose |
|---|---|---|
| `modules/fulfillment/src/test/resources/wiremock/cj/order-create-success.json` | CJ API docs createOrderV2 | Success response with orderId |
| `modules/fulfillment/src/test/resources/wiremock/cj/order-create-out-of-stock.json` | CJ API error response format | Stock-related error (code 1600400) |
| `modules/fulfillment/src/test/resources/wiremock/cj/order-create-auth-error.json` | CJ API docs error code 1600001 | Authentication failure |
| `modules/fulfillment/src/test/resources/wiremock/cj/order-create-rate-limit.json` | CJ API docs error code 1600200 | Rate limiting |

All fixtures follow the CJ API response format: `{code, result, message, requestId, data}`. Fixtures contain zero real credentials, tokens, or PII.

## Compilation Stub Files

| File | Mirrors | Purpose |
|---|---|---|
| `modules/fulfillment/src/test/kotlin/.../stub/OrderConfirmedStub.kt` | `shared/events/OrderConfirmed.kt` | Domain event type for test compilation |
| `modules/fulfillment/src/test/kotlin/.../stub/SupplierOrderTypes.kt` | `fulfillment/domain/supplier/SupplierOrderAdapter.kt` | Interface, request, result, failure reason types |
| `modules/fulfillment/src/test/kotlin/.../stub/ShippingAddressStub.kt` | `fulfillment/domain/ShippingAddress.kt` + `ChannelShippingAddress` | Address value types |
| `modules/fulfillment/src/test/kotlin/.../stub/SupplierProductMappingStub.kt` | `fulfillment/domain/SupplierProductMapping.kt` | Mapping entity type |

These stubs exist only for test compilation. Phase 5 will create the real types in main source, and these stubs will be removed or the test imports updated.

## Phase 5 Prerequisites

The following changes must be made in Phase 5 before these tests can pass:

1. **Add WireMock dependency** to `modules/fulfillment/build.gradle.kts`:
   ```kotlin
   testImplementation("org.wiremock:wiremock-standalone:3.4.2")
   ```
   Then convert `CjOrderAdapterContractTest` to use WireMock HTTP-level verification (currently uses Jackson fixture parsing as a workaround since Phase 4 cannot modify build.gradle.kts).

2. **Create real types in main source** (replacing test stubs):
   - `OrderConfirmed` in shared module
   - `SupplierOrderAdapter`, `SupplierOrderRequest`, `SupplierOrderResult`, `FailureReason` in fulfillment domain
   - `ShippingAddress` embeddable in fulfillment domain
   - `ChannelShippingAddress` on `ChannelOrder`
   - `SupplierProductMapping` entity
   - `SupplierProductMappingRepository`

3. **Update existing types**:
   - Add `FAILED` to `OrderStatus` enum
   - Update `Order.VALID_TRANSITIONS` (CONFIRMED -> FAILED, FAILED -> emptySet())
   - Add `shippingAddress`, `supplierOrderId`, `failureReason` fields to `Order` entity
   - Add `shippingAddress` field to `ChannelOrder`
   - Update `ShopifyOrderAdapter.parse()` to extract `shipping_address`

4. **Implement new components**:
   - `CjOrderAdapter` (fulfillment proxy)
   - `SupplierOrderPlacementListener` (fulfillment domain service)
   - `OrderService.routeToVendor()` event publishing

5. **Update test imports** from `com.autoshipper.fulfillment.stub.*` to real package paths once types exist in main source.

## CLAUDE.md Constraints Covered

| Constraint | How Tests Verify |
|---|---|
| #6: AFTER_COMMIT + REQUIRES_NEW | Flow tests document listener transaction requirements; boundary tests verify FAILED status is persisted (not rolled back) |
| #13: @Value with empty defaults | Contract test verifies blank credentials -> immediate Failure without API call |
| #15: Jackson get() not path() | Contract tests use get() for JSON field extraction; Shopify tests document get() requirement |
| #16: Persistable<T> for assigned IDs | Stub SupplierProductMapping documents Persistable<UUID> requirement |

## Build Verification

```
./gradlew compileTestKotlin  # BUILD SUCCESSFUL
```

All 25 test methods compile. Tests that reference unimplemented functionality use `TODO()` to fail clearly with descriptive messages indicating what Phase 5 must implement.
