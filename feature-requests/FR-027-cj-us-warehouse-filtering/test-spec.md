# FR-027: CJ US Warehouse Filtering -- Test Specification

**Linear ticket:** RAT-45
**Phase:** 4 (Test Specification)
**Spec:** `feature-requests/FR-027-cj-us-warehouse-filtering/spec.md`
**Implementation plan:** `feature-requests/FR-027-cj-us-warehouse-filtering/implementation-plan.md`
**Revised:** 2026-04-09 (aligned with verified CJ API documentation -- no warehouses array, no geographic routing)

---

## Acceptance Criteria

### SC-1: Verified-inventory filter active in discovery

| # | Test | Assert |
|---|---|---|
| 1a | `CjDropshippingAdapterWireMockTest`: stub `listV2` with `product-list-success.json` (all 3 products have positive `warehouseInventoryNum`) | WireMock `verify()`: request URL contains query param `verifiedWarehouse=1` |
| 1b | Same test: inspect returned candidates | `candidates.size >= 3` (4 categories x 3 products, but only one category stubbed in test); all 3 products returned because all have positive inventory |
| 1c | `CjDropshippingAdapterWireMockTest`: stub with `product-list-zero-inventory.json` (all products have `warehouseInventoryNum: 0`) | `candidates.isEmpty()` -- zero-inventory products excluded per NFR-3 |
| 1d | `CjDropshippingAdapterWireMockTest`: stub with `product-list-null-inventory.json` (products with `warehouseInventoryNum: null` or field absent) | `candidates.isEmpty()` -- fail-closed per NFR-3 |
| 1e | `CjDropshippingAdapterWireMockTest`: stub with `product-list-mixed-inventory.json` | Only products with positive `warehouseInventoryNum` returned; products with zero, null, or absent inventory excluded |

### SC-2: Warehouse inventory metadata in demand signals

| # | Test | Assert |
|---|---|---|
| 2a | `CjDropshippingAdapterWireMockTest`: happy path with `product-list-success.json` | `assertSignalPresent(first, "cj_warehouse_inventory_num")` |
| 2b | Same test: verify exact inventory value | `first.demandSignals["cj_warehouse_inventory_num"] == "500"` (matches fixture's first product `warehouseInventoryNum: 500`) |

### SC-3: Warehouse country code persisted in mapping

| # | Test | Assert |
|---|---|---|
| 3a | `SupplierOrderPlacementServiceTest`: construct `SupplierProductMapping(supplierProductId = "pid1", supplierVariantId = "vid1", warehouseCountryCode = "US")` and verify it flows to `SupplierOrderRequest` | `argThat<SupplierOrderRequest> { warehouseCountryCode == "US" }` on the captured adapter call |
| 3b | Same test class: construct legacy `SupplierProductMapping(supplierProductId = "pid1", supplierVariantId = "vid1")` (no warehouseCountryCode) | `argThat<SupplierOrderRequest> { warehouseCountryCode == null }` -- default null preserved |

### SC-4: Orders specify US origin

| # | Test | Assert |
|---|---|---|
| 4a | `CjSupplierOrderAdapterWireMockTest`: place order with `warehouseCountryCode = "US"` | WireMock `verify()`: request body contains `"fromCountryCode":"US"` |
| 4b | `CjSupplierOrderAdapterWireMockTest`: place order with `warehouseCountryCode = null` (legacy mapping) | WireMock `verify()`: request body contains `"fromCountryCode":"CN"` |
| 4c | `CjSupplierOrderAdapterWireMockTest`: place order with `warehouseCountryCode = "DE"` (future expansion) | WireMock `verify()`: request body contains `"fromCountryCode":"DE"` -- data-driven, not hardcoded |

### SC-5: `logisticName` included in order requests

| # | Test | Assert |
|---|---|---|
| 5a | `CjSupplierOrderAdapterWireMockTest`: adapter constructed with `logisticName = "CJPacket"` | WireMock `verify()`: request body contains `"logisticName":"CJPacket"` |
| 5b | `CjSupplierOrderAdapterWireMockTest`: adapter constructed with `logisticName = ""` (blank) | WireMock `verify()`: request body does NOT contain `"logisticName"` key |

### SC-6: Stress test pass rate improvement (qualitative)

| # | Test | Assert |
|---|---|---|
| 6a | No dedicated test. SC-3 confirms `warehouseCountryCode` flows through the pipeline. Cost envelope callers will pass `taxesAndDuties = Money.ZERO` for `warehouseCountryCode == "US"` mappings. Verified by existing `CostGateService` tests when callers are updated. | Documented for traceability only. |

### SC-7: ObjectMapper injection

| # | Test | Assert |
|---|---|---|
| 7a | `CjSupplierOrderAdapterWireMockTest`: all existing tests pass with adapter constructed via `CjSupplierOrderAdapter(baseUrl, accessToken, logisticName, jacksonObjectMapper())` | All 10 existing tests green -- confirms Spring-compatible ObjectMapper serializes correctly |
| 7b | `CjSupplierOrderAdapterWireMockTest`: `request body verification` test passes with injected ObjectMapper | WireMock `verify()` assertions on all body fields pass (same as existing test) |

---

## Fixture Data

### 2.1 `product-list-success.json` (updated)

Update the existing fixture at `modules/portfolio/src/test/resources/wiremock/cj/product-list-success.json` to add `warehouseInventoryNum` to all three products. All values are positive so existing happy-path tests continue to pass after inventory filtering is added.

```json
{
  "code": 200,
  "result": true,
  "message": "Success",
  "requestId": "f95cd31d-3907-47ce-ac1a-dfdee4315960",
  "data": {
    "pageNum": 1,
    "pageSize": 20,
    "total": 3,
    "list": [
      {
        "pid": "04A22450-67F0-4617-A132-E7AE7F8963B0",
        "productNameEn": "Stainless Steel Kitchen Knife Set",
        "productName": "[\"不锈钢厨刀套装\"]",
        "productSku": "CJNSSYWY01847",
        "categoryName": "Kitchen & Dining / Knives & Accessories",
        "categoryId": "5E656DFB-9BAE-44DD-A755-40AFA2E0E686",
        "sellPrice": 12.50,
        "productImage": "https://cc-west-usa.oss-us-west-1.aliyuncs.com/20210129/knife-set.png",
        "productWeight": 350,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Professional 5-piece stainless steel kitchen knife set with wooden block",
        "createTime": null,
        "warehouseInventoryNum": 500
      },
      {
        "pid": "1B3F7E82-A4C9-4D21-B8E6-92C3A5D10F47",
        "productNameEn": "Silicone Cooking Utensil Set",
        "productName": "[\"硅胶厨具套装\"]",
        "productSku": "CJNSSYWY02103",
        "categoryName": "Kitchen & Dining / Kitchen Utensils",
        "categoryId": "5E656DFB-9BAE-44DD-A755-40AFA2E0E686",
        "sellPrice": 8.99,
        "productImage": "https://cc-west-usa.oss-us-west-1.aliyuncs.com/20210215/utensil-set.png",
        "productWeight": 280,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Heat-resistant silicone cooking utensil set, 10 pieces",
        "createTime": null,
        "warehouseInventoryNum": 1200
      },
      {
        "pid": "7C9D2A15-3E8B-4F6A-91D7-5B4C8E2F0A63",
        "productNameEn": "Bamboo Cutting Board",
        "productName": "[\"竹砧板\"]",
        "productSku": "CJNSSYWY03291",
        "categoryName": "Kitchen & Dining / Cutting Boards",
        "categoryId": "5E656DFB-9BAE-44DD-A755-40AFA2E0E686",
        "sellPrice": 6.75,
        "productImage": "https://cc-west-usa.oss-us-west-1.aliyuncs.com/20210301/cutting-board.png",
        "productWeight": 420,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Organic bamboo cutting board with juice groove, large size",
        "createTime": null,
        "warehouseInventoryNum": 250
      }
    ]
  }
}
```

### 2.2 `product-list-zero-inventory.json` (new)

All products have `warehouseInventoryNum: 0`. Adapter must return empty list (fail closed per NFR-3).

**File:** `modules/portfolio/src/test/resources/wiremock/cj/product-list-zero-inventory.json`

```json
{
  "code": 200,
  "result": true,
  "message": "Success",
  "requestId": "b27de44a-1234-5678-abcd-ef0123456789",
  "data": {
    "pageNum": 1,
    "pageSize": 20,
    "total": 2,
    "list": [
      {
        "pid": "ZERO-INV-001",
        "productNameEn": "LED Desk Lamp",
        "productSku": "CJLED001",
        "categoryName": "Home & Garden / Lighting",
        "categoryId": "CAT-HOME-001",
        "sellPrice": 15.00,
        "productImage": "https://example.com/led-lamp.png",
        "productWeight": 500,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Adjustable LED desk lamp with USB charging port",
        "createTime": null,
        "warehouseInventoryNum": 0
      },
      {
        "pid": "ZERO-INV-002",
        "productNameEn": "Bamboo Phone Stand",
        "productSku": "CJBAM001",
        "categoryName": "Electronics / Accessories",
        "categoryId": "CAT-ELEC-001",
        "sellPrice": 3.50,
        "productImage": "https://example.com/phone-stand.png",
        "productWeight": 120,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Bamboo phone holder stand for desk",
        "createTime": null,
        "warehouseInventoryNum": 0
      }
    ]
  }
}
```

### 2.3 `product-list-null-inventory.json` (new)

Products with `warehouseInventoryNum` as JSON `null` or completely absent. Both must be excluded.

**File:** `modules/portfolio/src/test/resources/wiremock/cj/product-list-null-inventory.json`

```json
{
  "code": 200,
  "result": true,
  "message": "Success",
  "requestId": "c38ef55b-2345-6789-bcde-f01234567890",
  "data": {
    "pageNum": 1,
    "pageSize": 20,
    "total": 2,
    "list": [
      {
        "pid": "NULL-INV-001",
        "productNameEn": "Product With Null Inventory",
        "productSku": "CJNULL001",
        "categoryName": "Electronics",
        "categoryId": "CAT-ELEC-001",
        "sellPrice": 5.00,
        "productImage": "https://example.com/null-inv.png",
        "productWeight": 100,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Product with warehouseInventoryNum explicitly null",
        "createTime": null,
        "warehouseInventoryNum": null
      },
      {
        "pid": "ABSENT-INV-001",
        "productNameEn": "Product With Missing Inventory Field",
        "productSku": "CJMISS001",
        "categoryName": "Electronics",
        "categoryId": "CAT-ELEC-001",
        "sellPrice": 6.00,
        "productImage": "https://example.com/missing-inv.png",
        "productWeight": 100,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Product with warehouseInventoryNum field completely absent",
        "createTime": null
      }
    ]
  }
}
```

### 2.4 `product-list-mixed-inventory.json` (new)

Mix of products: some with positive inventory, some with zero, some with null, some with field absent. Tests selective filtering.

**File:** `modules/portfolio/src/test/resources/wiremock/cj/product-list-mixed-inventory.json`

```json
{
  "code": 200,
  "result": true,
  "message": "Success",
  "requestId": "d49fg66c-3456-7890-cdef-012345678901",
  "data": {
    "pageNum": 1,
    "pageSize": 20,
    "total": 5,
    "list": [
      {
        "pid": "GOOD-INV-001",
        "productNameEn": "Stainless Steel Water Bottle",
        "productSku": "CJWAT001",
        "categoryName": "Sports & Outdoors / Hydration",
        "categoryId": "CAT-SPORT-001",
        "sellPrice": 9.25,
        "productImage": "https://example.com/water-bottle.png",
        "productWeight": 350,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Insulated stainless steel water bottle, 32oz",
        "createTime": null,
        "warehouseInventoryNum": 800
      },
      {
        "pid": "ZERO-INV-MIX",
        "productNameEn": "Silicone Keyboard Cover",
        "productSku": "CJKEY001",
        "categoryName": "Electronics / Accessories",
        "categoryId": "CAT-ELEC-001",
        "sellPrice": 2.10,
        "productImage": "https://example.com/keyboard-cover.png",
        "productWeight": 50,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Ultra-thin silicone keyboard cover for laptop",
        "createTime": null,
        "warehouseInventoryNum": 0
      },
      {
        "pid": "NULL-INV-MIX",
        "productNameEn": "Mini USB Fan",
        "productSku": "CJFAN001",
        "categoryName": "Electronics / Accessories",
        "categoryId": "CAT-ELEC-001",
        "sellPrice": 4.20,
        "productImage": "https://example.com/usb-fan.png",
        "productWeight": 150,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Portable mini USB fan for desk",
        "createTime": null,
        "warehouseInventoryNum": null
      },
      {
        "pid": "ABSENT-INV-MIX",
        "productNameEn": "Glass Tea Infuser",
        "productSku": "CJTEA001",
        "categoryName": "Kitchen & Dining / Tea",
        "categoryId": "CAT-HOME-001",
        "sellPrice": 7.80,
        "productImage": "https://example.com/tea-infuser.png",
        "productWeight": 200,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Borosilicate glass tea infuser with lid",
        "createTime": null
      },
      {
        "pid": "GOOD-INV-002",
        "productNameEn": "Yoga Mat Premium",
        "productSku": "CJYOG001",
        "categoryName": "Sports & Outdoors / Yoga",
        "categoryId": "CAT-SPORT-001",
        "sellPrice": 11.50,
        "productImage": "https://example.com/yoga-mat.png",
        "productWeight": 800,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Premium non-slip yoga mat, 6mm thick",
        "createTime": null,
        "warehouseInventoryNum": 150
      },
      {
        "pid": "NEG-INV-MIX",
        "productNameEn": "Wireless Charging Pad",
        "productSku": "CJWCP001",
        "categoryName": "Electronics / Accessories",
        "categoryId": "CAT-ELEC-001",
        "sellPrice": 8.00,
        "productImage": "https://example.com/charging-pad.png",
        "productWeight": 120,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Wireless charging pad with negative inventory (B16)",
        "createTime": null,
        "warehouseInventoryNum": -5
      },
      {
        "pid": "STR-INV-MIX",
        "productNameEn": "Portable Bluetooth Speaker",
        "productSku": "CJSPK001",
        "categoryName": "Electronics / Audio",
        "categoryId": "CAT-ELEC-001",
        "sellPrice": 14.00,
        "productImage": "https://example.com/speaker.png",
        "productWeight": 300,
        "productUnit": "unit(s)",
        "productType": null,
        "description": "Portable speaker with string-type inventory (B17)",
        "createTime": null,
        "warehouseInventoryNum": "not-a-number"
      }
    ]
  }
}
```

**Expected after filtering:** 2 candidates returned (pids: `GOOD-INV-001`, `GOOD-INV-002`). 5 products excluded: `ZERO-INV-MIX` (zero), `NULL-INV-MIX` (null), `ABSENT-INV-MIX` (absent field), `NEG-INV-MIX` (negative, B16), `STR-INV-MIX` (string-type, B17).

### 2.5 `SupplierOrderRequest` examples

**US warehouse mapping (new path):**
```kotlin
SupplierOrderRequest(
    orderNumber = "order-us-001",
    shippingAddress = ShippingAddress(
        customerName = "Jane Doe",
        addressLine1 = "456 Oak Ave",
        addressLine2 = "Suite 200",
        city = "Portland",
        province = "Oregon",
        country = "United States",
        countryCode = "US",
        zip = "97201",
        phone = "+1-503-555-0199"
    ),
    supplierProductId = "pid-xyz-456",
    supplierVariantId = "vid-abc-123",
    quantity = 2,
    warehouseCountryCode = "US"
)
```

**Legacy mapping (null warehouse, backward compat):**
```kotlin
SupplierOrderRequest(
    orderNumber = "order-legacy-001",
    shippingAddress = ShippingAddress(
        customerName = "Jane Doe",
        addressLine1 = "456 Oak Ave",
        addressLine2 = "Suite 200",
        city = "Portland",
        province = "Oregon",
        country = "United States",
        countryCode = "US",
        zip = "97201",
        phone = "+1-503-555-0199"
    ),
    supplierProductId = "pid-xyz-456",
    supplierVariantId = "vid-abc-123",
    quantity = 2,
    warehouseCountryCode = null
)
```

### 2.6 Expected `createOrderV2` request body (US warehouse)

```json
{
  "orderNumber": "order-us-001",
  "shippingCountryCode": "US",
  "shippingCountry": "United States",
  "shippingCustomerName": "Jane Doe",
  "shippingAddress": "456 Oak Ave Suite 200",
  "shippingCity": "Portland",
  "shippingProvince": "Oregon",
  "shippingZip": "97201",
  "shippingPhone": "+1-503-555-0199",
  "fromCountryCode": "US",
  "logisticName": "CJPacket",
  "products": [
    {
      "vid": "vid-abc-123",
      "quantity": 2
    }
  ]
}
```

Key differences from current behavior:
- `fromCountryCode` is `"US"` (was hardcoded `"CN"`)
- `logisticName` field is present (was absent)

### 2.7 Expected `createOrderV2` request body (legacy fallback)

```json
{
  "orderNumber": "order-legacy-001",
  "shippingCountryCode": "US",
  "shippingCountry": "United States",
  "shippingCustomerName": "Jane Doe",
  "shippingAddress": "456 Oak Ave Suite 200",
  "shippingCity": "Portland",
  "shippingProvince": "Oregon",
  "shippingZip": "97201",
  "shippingPhone": "+1-503-555-0199",
  "fromCountryCode": "CN",
  "products": [
    {
      "vid": "vid-abc-123",
      "quantity": 2
    }
  ]
}
```

Key difference: `fromCountryCode` falls back to `"CN"`, `logisticName` absent (blank config).

---

## Boundary Cases

### 3.1 JSON null boundary cases for `warehouseInventoryNum` (CLAUDE.md #17)

The implementation uses `product.get("warehouseInventoryNum")?.let { if (!it.isNull) it.asInt(-1) else null }` to extract inventory. Every edge case must be tested:

| # | Scenario | JSON shape | Extraction behavior | Expected result | Assert |
|---|---|---|---|---|---|
| B1 | `warehouseInventoryNum` is JSON `null` | `"warehouseInventoryNum": null` | `get()` returns `NullNode` (non-null); `NullNode.isNull == true`; guard returns `null` | Product excluded | Covered by `product-list-null-inventory.json` fixture; `candidates.isEmpty()` |
| B2 | `warehouseInventoryNum` field absent | no `warehouseInventoryNum` key | `get()` returns `null` (Kotlin null); `?.let` short-circuits to `null` | Product excluded | Covered by `product-list-null-inventory.json` fixture; `candidates.isEmpty()` |
| B3 | `warehouseInventoryNum` is `0` | `"warehouseInventoryNum": 0` | Guard returns `0`; `0 <= 0` check catches it | Product excluded | Covered by `product-list-zero-inventory.json` fixture; `candidates.isEmpty()` |
| B4 | `warehouseInventoryNum` is negative | `"warehouseInventoryNum": -5` | Guard returns `-5`; `-5 <= 0` check catches it | Product excluded | Tested in `product-list-mixed-inventory.json` or as an additional element in a fixture (see B10 below) |
| B5 | `warehouseInventoryNum` is a string | `"warehouseInventoryNum": "abc"` | `asInt(-1)` returns `-1` for non-numeric; `-1 <= 0` check catches it | Product excluded gracefully | Tested in `product-list-mixed-inventory.json` or dedicated boundary fixture (see B11 below) |
| B6 | `warehouseInventoryNum` is a large positive number | `"warehouseInventoryNum": 999999` | Guard returns `999999`; `> 0` passes | Product included | Covered by giving one product a large value in any fixture |
| B7 | `warehouseInventoryNum` is `1` (minimum valid) | `"warehouseInventoryNum": 1` | Guard returns `1`; `> 0` passes | Product included | Spot-checked in happy-path or mixed fixture |

### 3.2 `warehouseCountryCode` on `SupplierOrderRequest`

| # | Scenario | Input | Expected behavior | Assert |
|---|---|---|---|---|
| B8 | `warehouseCountryCode` is `null` (legacy mapping) | `SupplierOrderRequest(..., warehouseCountryCode = null)` | `request.warehouseCountryCode ?: "CN"` evaluates to `"CN"` | WireMock `verify()`: body contains `"fromCountryCode":"CN"` |
| B9 | `warehouseCountryCode` is `"US"` | `SupplierOrderRequest(..., warehouseCountryCode = "US")` | Evaluates to `"US"` directly | WireMock `verify()`: body contains `"fromCountryCode":"US"` |
| B10 | `warehouseCountryCode` is unexpected value `"DE"` | `SupplierOrderRequest(..., warehouseCountryCode = "DE")` | Evaluates to `"DE"` -- data-driven, no whitelist | WireMock `verify()`: body contains `"fromCountryCode":"DE"` |

### 3.3 `logisticName` configuration boundary cases

| # | Scenario | Constructor arg | Expected behavior | Assert |
|---|---|---|---|---|
| B11 | `logisticName` is blank (empty string) | `CjSupplierOrderAdapter(..., logisticName = "", ...)` | `logisticName.isNotBlank()` is `false`; field NOT added to body map | WireMock `verify()`: request body does NOT contain `"logisticName"` |
| B12 | `logisticName` is non-blank (`"CJPacket"`) | `CjSupplierOrderAdapter(..., logisticName = "CJPacket", ...)` | `isNotBlank()` is `true`; `body["logisticName"] = "CJPacket"` | WireMock `verify()`: request body contains `"logisticName":"CJPacket"` |
| B13 | `logisticName` is whitespace-only (`"  "`) | `CjSupplierOrderAdapter(..., logisticName = "  ", ...)` | `"  ".isNotBlank()` is `false` in Kotlin; field NOT added | WireMock `verify()`: request body does NOT contain `"logisticName"` |

### 3.4 ObjectMapper injection boundary case

| # | Scenario | Expected behavior | Assert |
|---|---|---|---|
| B14 | Adapter constructed with `jacksonObjectMapper()` (Kotlin module) serializes `body` map correctly | `writeValueAsString(body)` produces valid JSON with all fields; no missing defaults or wrong nullability | All 10 existing `CjSupplierOrderAdapterWireMockTest` tests pass without changes to their assertions |

### 3.5 `verifiedWarehouse` query parameter boundary case

| # | Scenario | Expected behavior | Assert |
|---|---|---|---|
| B15 | `CjDropshippingAdapter.fetch()` sends `verifiedWarehouse=1` alongside existing `countryCode=US` | Both params present in every `listV2` request | WireMock `verify()` with `withQueryParam("verifiedWarehouse", equalTo("1"))` and `withQueryParam("countryCode", equalTo("US"))` |

### 3.6 Negative `warehouseInventoryNum` and non-numeric boundary cases

B16 and B17 are included in `product-list-mixed-inventory.json` (Section 2.4):

| # | Scenario | JSON shape | Fixture product |
|---|---|---|---|
| B16 | Negative inventory | `"warehouseInventoryNum": -5` | `NEG-INV-MIX` in `product-list-mixed-inventory.json`; `asInt(-1)` returns `-5`; `-5 <= 0` excludes it |
| B17 | String instead of number | `"warehouseInventoryNum": "not-a-number"` | `STR-INV-MIX` in `product-list-mixed-inventory.json`; `asInt(-1)` returns `-1`; `-1 <= 0` excludes it |

**`product-list-mixed-inventory.json` totals:** 7 products, 2 pass filter (pids: `GOOD-INV-001`, `GOOD-INV-002`). 5 excluded: zero (1), null (1), absent (1), negative (1), string-type (1).

---

## Test Class Specifications

### 4.1 `CjDropshippingAdapterWireMockTest` (portfolio module)

Existing test class at `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/CjDropshippingAdapterWireMockTest.kt`. Extends `WireMockAdapterTestBase`.

**Updated tests:**

| Test name | Fixture | Assertion changes |
|---|---|---|
| `happy path - products mapped with correct fields and demand signals` (existing) | `product-list-success.json` (updated with `warehouseInventoryNum`) | Add: `assertSignalPresent(first, "cj_warehouse_inventory_num")`; `assertThat(first.demandSignals["cj_warehouse_inventory_num"]).isEqualTo("500")`; WireMock `verify()` with `withQueryParam("verifiedWarehouse", equalTo("1"))` |

**New tests:**

| Test name | Fixture | Assertions |
|---|---|---|
| `zero inventory products excluded - returns empty list` | `product-list-zero-inventory.json` | `assertThat(candidates).isEmpty()` |
| `null and absent inventory products excluded - fail closed` | `product-list-null-inventory.json` | `assertThat(candidates).isEmpty()` |
| `mixed inventory - only positive inventory products returned` | `product-list-mixed-inventory.json` | `assertThat(candidates).hasSize(2)`; `assertThat(candidates.map { it.demandSignals["cj_pid"] }).containsExactlyInAnyOrder("GOOD-INV-001", "GOOD-INV-002")`; `assertThat(candidates.map { it.demandSignals["cj_pid"] }).doesNotContain("ZERO-INV-MIX", "NULL-INV-MIX", "ABSENT-INV-MIX", "NEG-INV-MIX", "STR-INV-MIX")` |
| `verifiedWarehouse query parameter sent` | `product-list-success.json` | WireMock `verify(getRequestedFor(urlPathEqualTo("/product/listV2")).withQueryParam("verifiedWarehouse", equalTo("1")).withQueryParam("countryCode", equalTo("US")))` |

### 4.2 `CjSupplierOrderAdapterWireMockTest` (fulfillment module)

Existing test class at `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjSupplierOrderAdapterWireMockTest.kt`.

**Adapter factory change:**

```kotlin
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

private fun adapter(
    baseUrl: String = wireMock.baseUrl(),
    accessToken: String = "test-cj-access-token",
    logisticName: String = "",
    objectMapper: ObjectMapper = jacksonObjectMapper()
): CjSupplierOrderAdapter = CjSupplierOrderAdapter(
    baseUrl = baseUrl,
    accessToken = accessToken,
    logisticName = logisticName,
    objectMapper = objectMapper
)
```

**Updated `validRequest()` helper:**

```kotlin
private fun validRequest(
    orderNumber: String = "order-001",
    quantity: Int = 2,
    supplierVariantId: String = "vid-abc-123",
    supplierProductId: String = "pid-xyz-456",
    warehouseCountryCode: String? = null
): SupplierOrderRequest = SupplierOrderRequest(
    orderNumber = orderNumber,
    shippingAddress = ShippingAddress(
        customerName = "Jane Doe",
        addressLine1 = "456 Oak Ave",
        addressLine2 = "Suite 200",
        city = "Portland",
        province = "Oregon",
        country = "United States",
        countryCode = "US",
        zip = "97201",
        phone = "+1-503-555-0199"
    ),
    supplierProductId = supplierProductId,
    supplierVariantId = supplierVariantId,
    quantity = quantity,
    warehouseCountryCode = warehouseCountryCode
)
```

**Existing tests:** All 10 existing tests continue to pass. The `adapter()` helper now defaults `logisticName = ""` (blank) and `objectMapper = jacksonObjectMapper()`. The `validRequest()` helper defaults `warehouseCountryCode = null`, which triggers `fromCountryCode = "CN"` (backward compatible with current behavior).

**New tests:**

| Test name | Setup | Assertions |
|---|---|---|
| `US warehouse mapping sends fromCountryCode US` | `validRequest(warehouseCountryCode = "US")`, stub success response | WireMock `verify()`: `.withRequestBody(containing("\"fromCountryCode\":\"US\""))` |
| `null warehouse mapping falls back to fromCountryCode CN` | `validRequest(warehouseCountryCode = null)`, stub success response | WireMock `verify()`: `.withRequestBody(containing("\"fromCountryCode\":\"CN\""))` |
| `unexpected warehouse country code is data-driven` | `validRequest(warehouseCountryCode = "DE")`, stub success response | WireMock `verify()`: `.withRequestBody(containing("\"fromCountryCode\":\"DE\""))` |
| `logisticName configured - included in request body` | `adapter(logisticName = "CJPacket")`, `validRequest()`, stub success response | WireMock `verify()`: `.withRequestBody(containing("\"logisticName\":\"CJPacket\""))` |
| `logisticName blank - omitted from request body` | `adapter(logisticName = "")`, `validRequest()`, stub success response | WireMock `verify()`: body does NOT contain `"logisticName"`; use `.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.not(containing("logisticName")))` or assert via request body string inspection |

### 4.3 `SupplierOrderPlacementServiceTest` (fulfillment module)

Existing test class at `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/SupplierOrderPlacementServiceTest.kt`.

**Existing tests:** All 7 existing tests continue to pass. `SupplierProductMapping` constructor gets `warehouseCountryCode: String? = null` default, so existing constructions like `SupplierProductMapping(supplierProductId = "pid1", supplierVariantId = "vid1")` compile unchanged. The captured `SupplierOrderRequest` will have `warehouseCountryCode = null` (legacy fallback).

**New test:**

| Test name | Setup | Assertions |
|---|---|---|
| `US warehouse mapping flows warehouseCountryCode to adapter` | `val mapping = SupplierProductMapping(supplierProductId = "pid1", supplierVariantId = "vid1", warehouseCountryCode = "US")`; same happy-path setup | `verify(supplierOrderAdapter).placeOrder(argThat<SupplierOrderRequest> { warehouseCountryCode == "US" && orderNumber == order.id.toString() && quantity == 2 && supplierVariantId == "vid1" && supplierProductId == "pid1" })` |

---

## E2E Playbook Scenarios

### 5.1 Full pipeline: US warehouse product discovery through order placement

**Preconditions:**
- CJ API configured with valid credentials
- V22 migration applied (warehouse_country_code column exists)
- `cj-dropshipping.default-logistic-name` set to `"CJPacket"` (or appropriate value)

**Steps:**
1. `DemandScanJob` triggers `CjDropshippingAdapter.fetch()`
2. Adapter sends `listV2` with `countryCode=US` and `verifiedWarehouse=1`
3. CJ returns products; adapter extracts `warehouseInventoryNum`, excludes zero/null/absent
4. Products with positive inventory become `RawCandidate` with `cj_warehouse_inventory_num` in `demandSignals`
5. SKU created from candidate; `supplier_product_mappings` row inserted with `warehouse_country_code = 'US'`
6. Order confirmed for SKU; `SupplierOrderPlacementService.placeSupplierOrder()` called
7. `SupplierProductMappingResolver` returns mapping with `warehouseCountryCode = "US"`
8. `SupplierOrderRequest` constructed with `warehouseCountryCode = "US"`
9. `CjSupplierOrderAdapter.placeOrder()` sends `fromCountryCode = "US"` and `logisticName = "CJPacket"`
10. CJ order placed successfully with US domestic fulfillment

**Verification points:**
- Step 2: `verifiedWarehouse=1` in request URL
- Step 3: Products with zero/null inventory NOT in candidate list
- Step 4: `cj_warehouse_inventory_num` present in `demandSignals`
- Step 5: `warehouse_country_code = 'US'` in DB row
- Step 7: `warehouseCountryCode = "US"` on resolved mapping
- Step 9: `"fromCountryCode": "US"` and `"logisticName": "CJPacket"` in CJ request body
- Step 9: INFO log: `"CJ order {id}: fromCountryCode=US, logisticName=CJPacket"`

### 5.2 Regression: Legacy mapping backward compatibility

**Preconditions:**
- Existing `supplier_product_mappings` row with `warehouse_country_code = NULL` (pre-FR-027 mapping)
- `cj-dropshipping.default-logistic-name` left blank (unconfigured)

**Steps:**
1. Order confirmed for legacy SKU
2. `SupplierProductMappingResolver` returns mapping with `warehouseCountryCode = null`
3. `SupplierOrderRequest` constructed with `warehouseCountryCode = null`
4. `CjSupplierOrderAdapter.placeOrder()` evaluates `request.warehouseCountryCode ?: "CN"` to `"CN"`
5. Adapter does NOT add `logisticName` to body (blank config)
6. CJ order placed with `fromCountryCode = "CN"` -- same as current production behavior

**Verification points:**
- Step 4: `"fromCountryCode": "CN"` in CJ request body
- Step 5: No `"logisticName"` key in request body
- Step 6: Order placement succeeds (no regression from constructor change)

---

## Contract Test Candidates

No new domain types are introduced by this feature:

- `SupplierProductMapping` is a data class (not a domain aggregate or sealed type) -- adding a nullable field with a default value does not change its contract.
- `SupplierOrderRequest` is a data class -- adding a nullable field with a default value is backward-compatible.
- `RawCandidate` is unchanged -- warehouse metadata flows through the existing `demandSignals: Map<String, String>`.
- No new sealed types, state machines, or value types are introduced.

**Conclusion:** No contract tests are needed for FR-027. All behavioral changes are covered by WireMock integration tests (adapter-level) and Mockito unit tests (service-level).

---

## Test-to-Task Mapping

| Test (from Section 4) | Implementation Plan Task |
|---|---|
| Happy path updated (SC-1, SC-2, B15) | T5.1 |
| Zero inventory excluded | T5.2 |
| Null/absent inventory excluded | T5.3 |
| Mixed inventory filtering | T5.4 |
| Adapter helper updated (SC-7) | T5.5 |
| fromCountryCode=US (SC-4) | T5.6 |
| fromCountryCode=CN fallback (SC-4, NFR-2) | T5.7 |
| logisticName configured (SC-5) | T5.8 |
| logisticName blank (SC-5) | T5.9 |
| warehouseCountryCode flow (SC-3) | T5.10 |
| Existing tests green (SC-7) | T5.11 |
