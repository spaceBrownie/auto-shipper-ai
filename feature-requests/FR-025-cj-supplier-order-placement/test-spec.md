# FR-025: CJ Supplier Order Placement — Test Specification

## Overview

This document specifies what to test and how, without writing actual test files. Phase 5 implements these tests alongside production code in TDD style.

Every test specified here calls production code and asserts on its output. No `assert(true)`, no fixture content checks, no constructor round-trips. This is the explicit corrective for PR #39's 50/65 theater tests.

---

## Acceptance Criteria

Each success criterion from spec.md maps to concrete assertions Phase 5 will implement.

### SC-1: Event-Driven Order Placement

| Criterion | Test Class | Test Method | Assertion |
|---|---|---|---|
| `OrderConfirmed` event published on `routeToVendor()` commit | `OrderServiceTest` | `routeToVendor publishes OrderConfirmed event with correct orderId and skuId` | `verify(eventPublisher).publishEvent(argThat<OrderConfirmed> { orderId.value == order.id && skuId.value == order.skuId })` |
| Listener fires on `OrderConfirmed` with `AFTER_COMMIT` + `REQUIRES_NEW` | `ArchitectureTest` | Existing Rule 1 covers this structurally | ArchUnit verifies double-annotation pattern |
| Listener delegates to `SupplierOrderPlacementService` | `SupplierOrderPlacementListenerTest` | `onOrderConfirmed delegates to placement service with correct orderId` | `verify(supplierOrderPlacementService).placeSupplierOrder(event.orderId.value)` |

### SC-2: Shipping Address Persisted

| Criterion | Test Class | Test Method | Assertion |
|---|---|---|---|
| Shopify adapter extracts all 11 shipping address fields | `ShopifyOrderAdapterTest` | `parse extracts complete shipping address from webhook payload` | Assert each field (`firstName`, `lastName`, `address1`, `address2`, `city`, `province`, `provinceCode`, `country`, `countryCode`, `zip`, `phone`) matches expected values from fixture |
| NullNode guard on every field | `ShopifyOrderAdapterTest` | `parse returns Kotlin null not string null for JSON null shipping address fields` | `assertThat(address.lastName).isNull()` (not `"null"`) for JSON null values |
| Missing `shipping_address` node | `ShopifyOrderAdapterTest` | `parse returns null shippingAddress when shipping_address node absent` | `assertThat(order.shippingAddress).isNull()` |

### SC-3: CJ Order ID Stored

| Criterion | Test Class | Test Method | Assertion |
|---|---|---|---|
| CJ order ID stored on success | `SupplierOrderPlacementServiceTest` | `placeSupplierOrder stores supplierOrderId on order when adapter returns Success` | `assertThat(capturedOrder.supplierOrderId).isEqualTo("CJ-ORDER-123")` via ArgumentCaptor on `orderRepository.save()` |
| CJ order ID verified via WireMock | `CjSupplierOrderAdapterWireMockTest` | `successful order placement returns Success with orderId from response` | `assertThat(result).isInstanceOf(SupplierOrderResult.Success::class.java)` and `assertThat((result as Success).supplierOrderId).isEqualTo("2011152148163605")` |

### SC-4: Quantity Carried on Order

| Criterion | Test Class | Test Method | Assertion |
|---|---|---|---|
| Quantity on `CreateOrderCommand` | `LineItemOrderCreatorTest` | `processLineItem passes quantity from ChannelLineItem to CreateOrderCommand` | `verify(orderService).create(argThat<CreateOrderCommand> { quantity == 2 })` |
| Quantity sent to CJ API | `CjSupplierOrderAdapterWireMockTest` | `request body includes correct quantity in products array` | `wireMock.verify(postRequestedFor(...).withRequestBody(matchingJsonPath("$.products[0].quantity", equalTo("2"))))` |

### SC-5: FAILED Status with Reason

| Criterion | Test Class | Test Method | Assertion |
|---|---|---|---|
| `CONFIRMED -> FAILED` transition valid | `OrderStateMachineTest` | `CONFIRMED to FAILED is a valid transition` | `order.updateStatus(OrderStatus.FAILED)` succeeds; `assertThat(order.status).isEqualTo(OrderStatus.FAILED)` |
| `FAILED` is terminal | `OrderStateMachineTest` | `FAILED to any state throws IllegalArgumentException` | `assertThrows<IllegalArgumentException> { order.updateStatus(OrderStatus.CONFIRMED) }` |
| Failure reason persisted on CJ rejection | `SupplierOrderPlacementServiceTest` | `placeSupplierOrder transitions order to FAILED with reason when adapter returns Failure` | `assertThat(capturedOrder.status).isEqualTo(OrderStatus.FAILED)` and `assertThat(capturedOrder.failureReason).isEqualTo("CJ API error (code=1600501): product out of stock")` |

### SC-6: SupplierOrderAdapter Interface

| Criterion | Test Class | Test Method | Assertion |
|---|---|---|---|
| Interface returns `Success` or `Failure` | `CjSupplierOrderAdapterWireMockTest` | Happy path and error tests | `SupplierOrderResult` sealed class enforces exhaustive matching |
| Adapter follows credential guard pattern | `CjSupplierOrderAdapterBlankCredentialTest` | `placeOrder returns Failure when credentials are blank` | `assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)` and `assertThat((result as Failure).reason).contains("credentials")` |

### SC-7: CJ API Contract Test (WireMock)

Covered by `CjSupplierOrderAdapterWireMockTest` — see Section 4.

### SC-8: Integration Test — Full Chain

Covered by `SupplierOrderPlacementIntegrationTest` — see Section 6.

### SC-9: Resilience Annotations

| Criterion | Test Class | Test Method | Assertion |
|---|---|---|---|
| `@CircuitBreaker` and `@Retry` present | Visual inspection + ArchUnit candidate | N/A — annotations are declarative; behavior tested by verifying HTTP errors propagate (not caught by adapter) | `CjSupplierOrderAdapterWireMockTest`: `auth failure returns HTTP exception that propagates` — `assertThrows<HttpClientErrorException.Unauthorized>` |

---

## Fixture Data

### 2.1 CJ API Response Fixtures

All fixtures based on CJ API docs at `https://developers.cjdropshipping.com/`. The CJ API returns HTTP 200 for all responses (including errors) — error status is in the JSON `code` field. Auth failures (HTTP 401) are the exception.

**File:** `modules/fulfillment/src/test/resources/wiremock/cj/create-order-success.json`
```json
{
  "code": 200,
  "result": true,
  "message": "success",
  "data": {
    "orderId": "2011152148163605",
    "orderNum": "CJ2011152148163605"
  },
  "requestId": "a12bc34d-5678-90ef-ghij-klmnopqrstuv"
}
```

**File:** `modules/fulfillment/src/test/resources/wiremock/cj/create-order-out-of-stock.json`
```json
{
  "code": 1600501,
  "result": false,
  "message": "product out of stock",
  "data": null,
  "requestId": "e56fg78h-9012-34ij-klmn-opqrstuvwxyz"
}
```

**File:** `modules/fulfillment/src/test/resources/wiremock/cj/create-order-invalid-address.json`
```json
{
  "code": 1600502,
  "result": false,
  "message": "invalid shipping address",
  "data": null,
  "requestId": "f67gh89i-0123-45jk-lmno-pqrstuvwxyza"
}
```

**File:** `modules/fulfillment/src/test/resources/wiremock/cj/create-order-auth-failure.json`
```json
{
  "code": 1600001,
  "result": false,
  "message": "Invalid API key or access token",
  "data": null,
  "requestId": "g78hi90j-1234-56kl-mnop-qrstuvwxyzab"
}
```

**File:** `modules/fulfillment/src/test/resources/wiremock/cj/create-order-null-fields.json`
```json
{
  "code": 200,
  "result": true,
  "message": "success",
  "data": {
    "orderId": null,
    "orderNum": null
  },
  "requestId": "h89ij01k-2345-67lm-nopq-rstuvwxyzabc"
}
```

### 2.2 Shopify Webhook Fixture (Extended)

The existing fixture at `modules/fulfillment/src/test/resources/shopify/orders-create-webhook.json` needs extension to include a full `shipping_address` block with all fields. This can be an updated version of the existing fixture or a separate one.

**File:** `modules/fulfillment/src/test/resources/shopify/orders-create-webhook-with-shipping.json`
```json
{
  "id": 820982911946154500,
  "name": "#1001",
  "email": "customer@example.com",
  "currency": "USD",
  "financial_status": "paid",
  "fulfillment_status": null,
  "customer": {
    "id": 115310627314723950,
    "email": "customer@example.com",
    "first_name": "John",
    "last_name": "Doe"
  },
  "line_items": [
    {
      "id": 866550311766439000,
      "product_id": 7513594,
      "variant_id": 34505432,
      "title": "Premium Widget",
      "quantity": 2,
      "price": "29.99",
      "sku": "WIDGET-001"
    }
  ],
  "shipping_address": {
    "first_name": "John",
    "last_name": "Doe",
    "address1": "123 Main St",
    "address2": "Apt 4B",
    "city": "Los Angeles",
    "province": "California",
    "province_code": "CA",
    "country": "United States",
    "country_code": "US",
    "zip": "90001",
    "phone": "+15551234567"
  },
  "payment_gateway_names": ["shopify_payments"]
}
```

**File:** `modules/fulfillment/src/test/resources/shopify/orders-create-webhook-null-address-fields.json`
```json
{
  "id": 820982911946154501,
  "name": "#1002",
  "email": "customer2@example.com",
  "currency": "USD",
  "financial_status": "paid",
  "customer": {
    "id": 115310627314723951,
    "email": "customer2@example.com"
  },
  "line_items": [
    {
      "id": 866550311766439001,
      "product_id": 7513594,
      "variant_id": 34505432,
      "title": "Premium Widget",
      "quantity": 1,
      "price": "29.99",
      "sku": "WIDGET-001"
    }
  ],
  "shipping_address": {
    "first_name": "Jane",
    "last_name": null,
    "address1": "456 Oak Ave",
    "address2": null,
    "city": "Portland",
    "province": null,
    "province_code": "OR",
    "country": "United States",
    "country_code": "US",
    "zip": "97201",
    "phone": null
  }
}
```

**File:** `modules/fulfillment/src/test/resources/shopify/orders-create-webhook-no-shipping.json`
```json
{
  "id": 820982911946154502,
  "name": "#1003",
  "email": "digital@example.com",
  "currency": "USD",
  "financial_status": "paid",
  "customer": {
    "id": 115310627314723952,
    "email": "digital@example.com"
  },
  "line_items": [
    {
      "id": 866550311766439002,
      "product_id": 7513594,
      "variant_id": 34505432,
      "title": "Digital Product",
      "quantity": 1,
      "price": "9.99",
      "sku": "DIGITAL-001"
    }
  ]
}
```

### 2.3 Sample Domain Data

Used across unit tests. Not fixture files, but in-test construction:

```kotlin
// Order with shipping address populated
val testOrder = Order(
    idempotencyKey = "test-idem-key",
    skuId = skuId,
    vendorId = vendorId,
    customerId = customerId,
    totalAmount = BigDecimal("59.98"),
    totalCurrency = Currency.USD,
    paymentIntentId = "pi_test_123",
    status = OrderStatus.CONFIRMED,
    quantity = 2,
    shippingAddress = ShippingAddress(
        customerName = "John Doe",
        addressLine1 = "123 Main St",
        addressLine2 = "Apt 4B",
        city = "Los Angeles",
        province = "California",
        provinceCode = "CA",
        country = "United States",
        countryCode = "US",
        zip = "90001",
        phone = "+15551234567"
    )
)
```

```kotlin
// SupplierProductMapping for CJ
val testMapping = SupplierProductMapping(
    supplierProductId = "04A22450-67F0-4617-A132-E7AE7F8963B0",
    supplierVariantId = "B7C9D1E2-3F4A-5B6C-7D8E-9F0A1B2C3D4E"
)
```

---

## Boundary Cases

### 3.1 Shopify Shipping Address — NullNode Guard (CLAUDE.md #17)

The NullNode bug from PR #39: `get()?.asText()` returns `"null"` (the string) for JSON `null` values. The guard `?.let { if (!it.isNull) it.asText() else null }` must be applied to ALL 11 fields.

**Test class:** `ShopifyOrderAdapterTest`

| Test Method Name | Input | Expected Output | What It Catches |
|---|---|---|---|
| `parse returns Kotlin null for JSON null lastName in shipping address` | `"last_name": null` in fixture | `address.lastName` is `null` (Kotlin null) | NullNode trap: `get()?.asText()` returns `"null"` string |
| `parse returns Kotlin null for JSON null address2 in shipping address` | `"address2": null` in fixture | `address.address2` is `null` (Kotlin null) | Same NullNode trap on nullable field |
| `parse returns Kotlin null for JSON null province in shipping address` | `"province": null` in fixture | `address.province` is `null` (Kotlin null) | Same NullNode trap |
| `parse returns Kotlin null for JSON null phone in shipping address` | `"phone": null` in fixture | `address.phone` is `null` (Kotlin null) | Same NullNode trap |
| `parse returns Kotlin null for all-null shipping address fields` | All 11 fields set to JSON `null` | All fields on `ChannelShippingAddress` are Kotlin `null` | Guard consistency — if one field is guarded, all must be |
| `parse returns null shippingAddress when shipping_address node absent` | No `shipping_address` key in JSON | `order.shippingAddress` is `null` | Missing node handling |
| `parse returns null shippingAddress when shipping_address is JSON null` | `"shipping_address": null` | `order.shippingAddress` is `null` | NullNode on parent object |
| `parse extracts non-null fields correctly when some fields are null` | Mixed null/present (fixture 2.2 null variant) | `firstName = "Jane"`, `lastName = null`, `city = "Portland"`, `phone = null` | Mixed extraction correctness |

**Critical assertion pattern for every null test:**
```kotlin
// WRONG (PR #39 bug):
assertThat(address.lastName).isNotEqualTo("null")

// CORRECT:
assertThat(address.lastName).isNull()
```

### 3.2 Shopify Shipping Address — Missing Fields (Field Absent from JSON)

Distinct from JSON `null` — the field key is entirely absent.

| Test Method Name | Input | Expected Output |
|---|---|---|
| `parse returns null for missing firstName in shipping address` | `shipping_address` object with no `first_name` key | `address.firstName` is `null` |
| `parse returns null for missing provinceCode in shipping address` | `shipping_address` object with no `province_code` key | `address.provinceCode` is `null` |

### 3.3 CJ API Response — NullNode Guard (CLAUDE.md #17)

The CJ adapter parses `code`, `result`, `message`, `data.orderId` from the response. Each needs a NullNode guard.

**Test class:** `CjSupplierOrderAdapterWireMockTest`

| Test Method Name | Fixture | Expected Output |
|---|---|---|
| `returns Failure when data orderId is JSON null in success response` | `create-order-null-fields.json` (code=200 but `data.orderId` is null) | `SupplierOrderResult.Failure` with reason containing "no orderId" |
| `returns Failure when data node is JSON null in success response` | Custom inline: `"data": null` with code=200 | `SupplierOrderResult.Failure` with reason containing "no orderId" |
| `returns Failure when message is JSON null in error response` | Custom inline: `"message": null, "code": 1600501` | `SupplierOrderResult.Failure` with reason containing "Unknown error" (fallback) |

### 3.4 Order Entity — Edge Cases

**Test class:** `OrderStateMachineTest`

| Test Method Name | Input | Expected Output |
|---|---|---|
| `CONFIRMED to FAILED is a valid transition` | Order in CONFIRMED, call `updateStatus(FAILED)` | Status is FAILED, no exception |
| `PENDING to FAILED is a valid transition` | Order in PENDING, call `updateStatus(FAILED)` | Status is FAILED, no exception |
| `FAILED to CONFIRMED throws IllegalArgumentException` | Order in FAILED, call `updateStatus(CONFIRMED)` | `assertThrows<IllegalArgumentException>` |
| `FAILED to SHIPPED throws IllegalArgumentException` | Order in FAILED, call `updateStatus(SHIPPED)` | `assertThrows<IllegalArgumentException>` |
| `FAILED to PENDING throws IllegalArgumentException` | Order in FAILED, call `updateStatus(PENDING)` | `assertThrows<IllegalArgumentException>` |
| `SHIPPED to FAILED throws IllegalArgumentException` | Order in SHIPPED, call `updateStatus(FAILED)` | `assertThrows<IllegalArgumentException>` — FAILED is only reachable from CONFIRMED and PENDING |

### 3.5 SupplierOrderPlacementService — Edge Cases

**Test class:** `SupplierOrderPlacementServiceTest`

| Test Method Name | Input | Expected Output |
|---|---|---|
| `skips placement when order already has supplierOrderId` | Order with `supplierOrderId = "existing-123"` | Adapter never called; `verify(supplierOrderAdapter, never()).placeOrder(any())` |
| `transitions to FAILED when no supplier product mapping found` | Resolver returns `null` | Order status = FAILED, `failureReason` contains "No supplier product mapping" |
| `throws when order not found` | `orderRepository.findById()` returns empty | `assertThrows<IllegalArgumentException>` |
| `does not catch adapter exceptions for Resilience4j` | Adapter throws `HttpClientErrorException` | Exception propagates — `assertThrows<HttpClientErrorException>` |

### 3.6 CjSupplierOrderAdapter — Edge Cases

**Test class:** `CjSupplierOrderAdapterWireMockTest`

| Test Method Name | Input | Expected Output |
|---|---|---|
| `sends CJ-Access-Token header` | Any valid request | `wireMock.verify(postRequestedFor(...).withHeader("CJ-Access-Token", equalTo("test-token")))` |
| `sends correct JSON request body structure` | Valid `SupplierOrderRequest` | WireMock verifies: `orderNumber`, `shippingCustomerName`, `shippingAddress`, `shippingCity`, `shippingProvince`, `shippingZip`, `shippingCountryCode`, `shippingCountry`, `shippingPhone`, `fromCountryCode`, `products[0].vid`, `products[0].quantity` |
| `returns Failure for blank baseUrl` | `baseUrl = ""` | `SupplierOrderResult.Failure` with reason containing "credentials" |
| `returns Failure for blank accessToken` | `accessToken = ""` | `SupplierOrderResult.Failure` with reason containing "credentials" |
| `propagates HTTP 401 as exception` | WireMock returns HTTP 401 | Exception propagates (not caught by adapter); Resilience4j handles retry |
| `propagates HTTP 500 as exception` | WireMock returns HTTP 500 | Exception propagates (not caught by adapter) |
| `maps address line2 into shippingAddress field` | Request with `addressLine1 = "123 Main St"`, `addressLine2 = "Apt 4B"` | Request body `shippingAddress` = `"123 Main St Apt 4B"` (concatenated) |
| `handles null address line2 in request` | Request with `addressLine2 = null` | Request body `shippingAddress` = `"123 Main St"` (no trailing space) |

### 3.7 Quantity Edge Cases

| Test Class | Test Method Name | Expected Output |
|---|---|---|
| `CjSupplierOrderAdapterWireMockTest` | `request body products array contains correct quantity` | WireMock: `matchingJsonPath("$.products[0].quantity", equalTo("2"))` |
| `LineItemOrderCreatorTest` | `processLineItem passes quantity from ChannelLineItem to CreateOrderCommand` | `verify(orderService).create(argThat<CreateOrderCommand> { quantity == lineItem.quantity })` |

### 3.8 Idempotency

| Test Class | Test Method Name | Input | Expected Output |
|---|---|---|---|
| `SupplierOrderPlacementServiceTest` | `skips placement when order already has supplierOrderId` | Order with non-null `supplierOrderId` | Adapter never called; order unchanged |
| `SupplierOrderPlacementServiceTest` | `places order when supplierOrderId is null` | Order with null `supplierOrderId` | Adapter called once |

---

## Contract Test Candidates

**Test class:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjSupplierOrderAdapterWireMockTest.kt`

**Setup pattern** (follows `CjDropshippingAdapterWireMockTest` and `ShopifyPriceSyncAdapterWireMockTest`):
```kotlin
companion object {
    @JvmField
    @RegisterExtension
    val wireMock: WireMockExtension = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build()
}

private fun adapter(baseUrl: String = wireMock.baseUrl(), token: String = "test-token"): CjSupplierOrderAdapter =
    CjSupplierOrderAdapter(baseUrl = baseUrl, accessToken = token)

private fun loadFixture(path: String): String =
    this::class.java.classLoader
        .getResource("wiremock/$path")
        ?.readText()
        ?: throw IllegalArgumentException("Fixture not found: wiremock/$path")
```

**WireMock dependency:** Add `testImplementation("org.wiremock:wiremock-standalone:3.5.4")` to `modules/fulfillment/build.gradle.kts`.

### Test Methods

| # | Test Method Name | WireMock Stub | Assertion |
|---|---|---|---|
| 1 | `successful order placement returns Success with orderId from response` | POST `/api2.0/v1/shopping/order/createOrderV2` returns `create-order-success.json` | `result is SupplierOrderResult.Success` and `result.supplierOrderId == "2011152148163605"` |
| 2 | `out of stock error returns Failure with reason` | Returns `create-order-out-of-stock.json` | `result is SupplierOrderResult.Failure` and `result.reason.contains("product out of stock")` |
| 3 | `invalid address error returns Failure with reason` | Returns `create-order-invalid-address.json` | `result is SupplierOrderResult.Failure` and `result.reason.contains("invalid shipping address")` |
| 4 | `auth failure propagates as exception` | Returns HTTP 401 with `create-order-auth-failure.json` | `assertThrows<HttpClientErrorException.Unauthorized>` — adapter does NOT catch HTTP errors |
| 5 | `sends CJ-Access-Token header in request` | Any valid stub | `wireMock.verify(postRequestedFor(urlEqualTo("/api2.0/v1/shopping/order/createOrderV2")).withHeader("CJ-Access-Token", equalTo("test-token")))` |
| 6 | `request body contains orderNumber matching internal order ID` | Capture request body | `matchingJsonPath("$.orderNumber", equalTo(request.orderId.toString()))` |
| 7 | `request body contains all shipping fields` | Capture request body | Verify `shippingCustomerName`, `shippingAddress`, `shippingCity`, `shippingProvince`, `shippingZip`, `shippingCountryCode`, `shippingCountry`, `shippingPhone`, `fromCountryCode` via `matchingJsonPath` |
| 8 | `request body products array has correct vid and quantity` | Capture request body | `matchingJsonPath("$.products[0].vid", equalTo(request.supplierVariantId))` and `matchingJsonPath("$.products[0].quantity", equalTo("2"))` |
| 9 | `returns Failure when data orderId is JSON null in success response` | Returns `create-order-null-fields.json` | `result is SupplierOrderResult.Failure` with reason containing "no orderId" |
| 10 | `returns Failure for blank baseUrl` | No WireMock stub needed | `adapter(baseUrl = "").placeOrder(request)` returns `SupplierOrderResult.Failure` |
| 11 | `returns Failure for blank accessToken` | No WireMock stub needed | `adapter(token = "").placeOrder(request)` returns `SupplierOrderResult.Failure` |
| 12 | `HTTP 500 propagates as exception` | Returns HTTP 500 | `assertThrows<HttpServerErrorException.InternalServerError>` |

### Fixture Source Verification

All CJ response fixtures must be cross-referenced against `https://developers.cjdropshipping.com/api2.0/v1/shopping/order/createOrderV2` documentation (NFR-7). Key structural elements from docs:
- Top-level `code`, `result`, `message`, `data`, `requestId` fields
- Success: `code` = 200, `result` = true, `data.orderId` is the CJ order identifier
- Error codes: 1600501 (out of stock), 1600502 (invalid address), 1600001 (auth)
- HTTP 200 for all business responses; HTTP 401 only for completely missing auth

---

## Unit Tests

### 5.1 OrderStateMachineTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/OrderStateMachineTest.kt`

Tests the new `FAILED` status transitions on the `Order` entity. Existing state transition tests in `OrderServiceTest` test through the service layer; these test the transition map directly.

| Test Method | Setup | Action | Assertion |
|---|---|---|---|
| `CONFIRMED to FAILED is valid` | `Order(status = CONFIRMED)` | `order.updateStatus(FAILED)` | `order.status == FAILED` |
| `PENDING to FAILED is valid` | `Order(status = PENDING)` | `order.updateStatus(FAILED)` | `order.status == FAILED` |
| `FAILED is terminal - no transition to CONFIRMED` | `Order(status = CONFIRMED)`, then `updateStatus(FAILED)` | `order.updateStatus(CONFIRMED)` | `assertThrows<IllegalArgumentException>` |
| `FAILED is terminal - no transition to SHIPPED` | Same | `order.updateStatus(SHIPPED)` | `assertThrows<IllegalArgumentException>` |
| `FAILED is terminal - no transition to PENDING` | Same | `order.updateStatus(PENDING)` | `assertThrows<IllegalArgumentException>` |
| `FAILED is terminal - no transition to DELIVERED` | Same | `order.updateStatus(DELIVERED)` | `assertThrows<IllegalArgumentException>` |
| `SHIPPED to FAILED is not valid` | `Order(status = SHIPPED)` | `order.updateStatus(FAILED)` | `assertThrows<IllegalArgumentException>` |
| `DELIVERED to FAILED is not valid` | `Order(status = DELIVERED)` | `order.updateStatus(FAILED)` | `assertThrows<IllegalArgumentException>` |

### 5.2 SupplierOrderPlacementServiceTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/SupplierOrderPlacementServiceTest.kt`

**Mocks:** `OrderRepository`, `SupplierOrderAdapter`, `SupplierProductMappingResolver`

| Test Method | Setup | Action | Assertion |
|---|---|---|---|
| `stores supplierOrderId on order when adapter returns Success` | Order in CONFIRMED with null `supplierOrderId`, resolver returns mapping, adapter returns `Success("CJ-ORDER-123")` | `service.placeSupplierOrder(orderId)` | Captured saved order has `supplierOrderId == "CJ-ORDER-123"`, status still CONFIRMED |
| `transitions to FAILED with reason when adapter returns Failure` | Order in CONFIRMED, resolver returns mapping, adapter returns `Failure("product out of stock")` | `service.placeSupplierOrder(orderId)` | Captured saved order has `status == FAILED`, `failureReason == "product out of stock"` |
| `skips placement when supplierOrderId already populated` | Order in CONFIRMED with `supplierOrderId = "existing"` | `service.placeSupplierOrder(orderId)` | `verify(supplierOrderAdapter, never()).placeOrder(any())`, `verify(orderRepository, never()).save(any())` |
| `transitions to FAILED when no mapping found` | Order in CONFIRMED, resolver returns `null` | `service.placeSupplierOrder(orderId)` | Captured saved order has `status == FAILED`, `failureReason` contains "No supplier product mapping" |
| `throws when order not found` | `findById` returns empty | `service.placeSupplierOrder(unknownId)` | `assertThrows<IllegalArgumentException>` |
| `passes correct SupplierOrderRequest to adapter` | Order with known fields, resolver returns mapping | `service.placeSupplierOrder(orderId)` | `verify(adapter).placeOrder(argThat { orderId == order.id && supplierVariantId == mapping.supplierVariantId && quantity == order.quantity && shippingAddress == order.shippingAddress })` |

### 5.3 ShopifyOrderAdapterTest (Extended)

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/channel/ShopifyOrderAdapterTest.kt`

Extends existing test class. Existing tests remain. New tests:

| Test Method | Fixture | Assertion |
|---|---|---|
| `parse extracts complete shipping address with all fields` | `orders-create-webhook-with-shipping.json` | `address.firstName == "John"`, `address.lastName == "Doe"`, `address.address1 == "123 Main St"`, `address.address2 == "Apt 4B"`, `address.city == "Los Angeles"`, `address.province == "California"`, `address.provinceCode == "CA"`, `address.country == "United States"`, `address.countryCode == "US"`, `address.zip == "90001"`, `address.phone == "+15551234567"` |
| `parse returns Kotlin null for JSON null lastName` | `orders-create-webhook-null-address-fields.json` | `assertThat(address.lastName).isNull()` |
| `parse returns Kotlin null for JSON null address2` | `orders-create-webhook-null-address-fields.json` | `assertThat(address.address2).isNull()` |
| `parse returns Kotlin null for JSON null province` | `orders-create-webhook-null-address-fields.json` | `assertThat(address.province).isNull()` |
| `parse returns Kotlin null for JSON null phone` | `orders-create-webhook-null-address-fields.json` | `assertThat(address.phone).isNull()` |
| `parse extracts non-null fields correctly when others are null` | `orders-create-webhook-null-address-fields.json` | `address.firstName == "Jane"`, `address.city == "Portland"`, `address.provinceCode == "OR"`, `address.countryCode == "US"` |
| `parse returns null shippingAddress when node absent` | `orders-create-webhook-no-shipping.json` | `assertThat(order.shippingAddress).isNull()` |
| `parse returns null shippingAddress when node is JSON null` | Inline JSON: `"shipping_address": null` | `assertThat(order.shippingAddress).isNull()` |
| `parse returns null for missing firstName in shipping address` | Inline JSON: `shipping_address` with no `first_name` key | `assertThat(address.firstName).isNull()` |
| `parse extracts quantity from line items` | Any fixture with `"quantity": 2` | `assertThat(order.lineItems[0].quantity).isEqualTo(2)` (already exists but verifies data flows) |

### 5.4 OrderServiceTest (Extended)

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/OrderServiceTest.kt`

New tests added to existing class:

| Test Method | Setup | Assertion |
|---|---|---|
| `routeToVendor publishes OrderConfirmed event with correct orderId and skuId` | Pending order | `verify(eventPublisher).publishEvent(argThat<OrderConfirmed> { orderId.value == order.id && skuId.value == order.skuId })` |
| `create persists quantity from command` | Command with `quantity = 3` | Saved order has `quantity == 3` |
| `create persists shippingAddress from command` | Command with `shippingAddress` populated | Saved order has all shipping fields set correctly |

### 5.5 LineItemOrderCreatorTest (Extended)

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/LineItemOrderCreatorTest.kt`

New tests added to existing class:

| Test Method | Setup | Assertion |
|---|---|---|
| `processLineItem passes quantity from ChannelLineItem to CreateOrderCommand` | `lineItem.quantity = 2` | `verify(orderService).create(argThat<CreateOrderCommand> { quantity == 2 })` |
| `processLineItem maps ChannelShippingAddress to ShippingAddress on command` | `channelOrder` with `ChannelShippingAddress(firstName = "John", lastName = "Doe", ...)` | `verify(orderService).create(argThat<CreateOrderCommand> { shippingAddress?.customerName == "John Doe" && shippingAddress?.addressLine1 == "123 Main St" })` |
| `processLineItem concatenates firstName and lastName for customerName` | `firstName = "John"`, `lastName = "Doe"` | `shippingAddress?.customerName == "John Doe"` |
| `processLineItem handles null lastName in customerName` | `firstName = "John"`, `lastName = null` | `shippingAddress?.customerName == "John"` (no trailing space) |
| `processLineItem handles null firstName in customerName` | `firstName = null`, `lastName = "Doe"` | `shippingAddress?.customerName == "Doe"` (no leading space) |
| `processLineItem passes null shippingAddress when channelOrder has none` | `channelOrder.shippingAddress = null` | `verify(orderService).create(argThat<CreateOrderCommand> { shippingAddress == null })` |
| `processLineItem calls routeToVendor after order creation` | Standard setup, `orderService.create` returns (order, true) | `verify(orderService).routeToVendor(order.id)` after `create` and `setChannelMetadata` |

### 5.6 SupplierOrderPlacementListenerTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/SupplierOrderPlacementListenerTest.kt`

**Mocks:** `SupplierOrderPlacementService`

| Test Method | Input | Assertion |
|---|---|---|
| `onOrderConfirmed delegates to placement service with correct orderId` | `OrderConfirmed(orderId = OrderId(uuid), skuId = SkuId(skuUuid))` | `verify(service).placeSupplierOrder(uuid)` |

### 5.7 CjSupplierOrderAdapterBlankCredentialTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjSupplierOrderAdapterBlankCredentialTest.kt`

Follows `StripeRefundAdapterBlankCredentialTest` pattern.

| Test Method | Input | Assertion |
|---|---|---|
| `placeOrder returns Failure when baseUrl is blank` | `CjSupplierOrderAdapter(baseUrl = "", accessToken = "token")` | `result is SupplierOrderResult.Failure`, `result.reason.contains("credentials")` |
| `placeOrder returns Failure when accessToken is blank` | `CjSupplierOrderAdapter(baseUrl = "http://example.com", accessToken = "")` | `result is SupplierOrderResult.Failure`, `result.reason.contains("credentials")` |
| `placeOrder does not make HTTP call when credentials blank` | Same as above | No HTTP activity (no WireMock needed — test constructs adapter directly) |

---

## E2E Playbook Scenarios

### 6.1 Happy Path: Shopify Order -> CJ Order Placed

**Test class:** `SupplierOrderPlacementIntegrationTest`
**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/integration/SupplierOrderPlacementIntegrationTest.kt`

**Pattern:** Follows `OrderLifecycleTest` — Mockito-based integration test with in-memory repository store, not Spring test context. The event listener is tested separately with `TransactionTemplate` in the Spring integration test (6.4).

**Setup:**
- In-memory order store (same pattern as `OrderLifecycleTest.setupRepositoryWithInMemoryStore()`)
- Mock `InventoryChecker` (returns true)
- Mock `SupplierOrderAdapter` (returns `Success("CJ-ORDER-123")`)
- Mock `SupplierProductMappingResolver` (returns mapping)
- Real `OrderService`, `SupplierOrderPlacementService`

**Steps:**
1. Create order with shipping address and quantity via `OrderService.create(command)` where command includes `quantity = 2` and `shippingAddress`
2. Call `orderService.routeToVendor(order.id)` - verify status = CONFIRMED
3. Call `supplierOrderPlacementService.placeSupplierOrder(order.id)` (direct call, not event-driven)
4. Assert: order in store has `supplierOrderId == "CJ-ORDER-123"`
5. Assert: order status is still CONFIRMED (not changed by success)
6. Verify: adapter called with correct `SupplierOrderRequest` containing the order's shipping address, variant mapping, and quantity

### 6.2 CJ Rejection: Order FAILED with Reason

**Same test class, different test method.**

**Steps:**
1. Create order, route to vendor (CONFIRMED)
2. Configure adapter mock to return `Failure("product out of stock")`
3. Call `supplierOrderPlacementService.placeSupplierOrder(order.id)`
4. Assert: order status = FAILED
5. Assert: `order.failureReason == "product out of stock"`
6. Assert: `order.supplierOrderId` is null

### 6.3 Missing Variant Mapping: Order FAILED

**Same test class, different test method.**

**Steps:**
1. Create order, route to vendor (CONFIRMED)
2. Configure resolver to return `null`
3. Call `supplierOrderPlacementService.placeSupplierOrder(order.id)`
4. Assert: order status = FAILED
5. Assert: `order.failureReason` contains "No supplier product mapping"
6. Verify: adapter never called

### 6.4 Idempotent Retry: Skip When Already Placed

**Same test class, different test method.**

**Steps:**
1. Create order, route to vendor (CONFIRMED)
2. Set `order.supplierOrderId = "already-placed-123"` in store
3. Call `supplierOrderPlacementService.placeSupplierOrder(order.id)`
4. Verify: adapter never called
5. Assert: `order.supplierOrderId` still = "already-placed-123" (unchanged)

### 6.5 Event-Driven Full Chain (Spring Context Required)

**Note for Phase 5:** If a Spring integration test with real `TransactionTemplate` is feasible, this scenario tests the event bus:

1. Publish `OrderConfirmed` inside `TransactionTemplate.execute {}`
2. Verify `SupplierOrderPlacementListener.onOrderConfirmed()` fires
3. Verify downstream service called

This requires a Spring test context (`@SpringBootTest`) and is subject to project test infrastructure constraints. If the project does not have a Spring integration test pattern with `TransactionTemplate`, this scenario should be documented as a manual verification step rather than an automated test. The unit test for the listener (5.6) covers the delegation logic.

---

## Existing Test Updates

The following existing tests will fail to compile when `quantity` is added as a required field on `Order` and `CreateOrderCommand`. Phase 5 must update these.

### 7.1 Files Requiring `quantity` Addition

| File | What Changes |
|---|---|
| `OrderServiceTest.kt` | `createCommand()` and `pendingOrder()` helper methods need `quantity` parameter |
| `LineItemOrderCreatorTest.kt` | `Order(...)` construction needs `quantity` parameter |
| `OrderLifecycleTest.kt` | All `CreateOrderCommand(...)` and `Order(...)` calls need `quantity` |
| `OrderControllerTest.kt` | Any `Order(...)` construction needs `quantity` |
| `ShipmentTrackerTest.kt` | Any `Order(...)` construction needs `quantity` |
| `VendorSlaBreachRefunderTest.kt` | Any `Order(...)` construction needs `quantity` |
| `DelayAlertServiceTest.kt` | Any `Order(...)` construction if present |
| `ShopifyOrderProcessingServiceTest.kt` | If it constructs `ChannelOrder`, needs `shippingAddress` field |
| `FulfillmentDataProviderImplTest.kt` | Any `Order(...)` construction needs `quantity` |

### 7.2 Files Requiring `shippingAddress` on `ChannelOrder`

| File | What Changes |
|---|---|
| `ShopifyOrderAdapterTest.kt` | Existing tests should still pass (fixture may not have `shipping_address`, so `shippingAddress` will be null — acceptable) |
| `ShopifyOrderProcessingServiceTest.kt` | `ChannelOrder(...)` construction needs `shippingAddress` parameter (nullable, so `null` is fine for existing tests) |
| `LineItemOrderCreatorTest.kt` | `ChannelOrder(...)` construction needs `shippingAddress` parameter |

---

## Test Dependencies

**File:** `modules/fulfillment/build.gradle.kts`

Add WireMock dependency for contract tests:
```kotlin
testImplementation("org.wiremock:wiremock-standalone:3.5.4")
```

The following are already present and sufficient:
- `org.springframework.boot:spring-boot-starter-test` (JUnit 5, AssertJ)
- `org.mockito.kotlin:mockito-kotlin:5.3.1`
- `com.fasterxml.jackson.module:jackson-module-kotlin`

---

## Quality Checklist

Phase 5 must verify every test against these rules before considering it done:

- [ ] Every test calls production code (service, adapter, or entity method)
- [ ] Every assertion operates on the return value or side effect of production code
- [ ] No `assert(true)` anywhere
- [ ] No `assertThat(fixture).contains(...)` (fixture content checking)
- [ ] No `// Phase 5:` deferred comments — either the test is written or it is explicitly omitted with rationale
- [ ] Every CJ API response field parsed by the adapter has a corresponding JSON null boundary test
- [ ] Every Shopify `shipping_address` field parsed by the adapter has a corresponding JSON null boundary test
- [ ] NullNode assertions use `assertThat(field).isNull()`, never `assertThat(field).isNotEqualTo("null")`
- [ ] WireMock fixtures match CJ API documentation structure (code, result, message, data, requestId)
- [ ] All `Order(...)` constructions in updated existing tests include `quantity` parameter
- [ ] `ChannelOrder(...)` constructions include `shippingAddress` parameter (nullable)
- [ ] `SupplierOrderPlacementListener` annotated with `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` — verified by ArchUnit Rule 1
