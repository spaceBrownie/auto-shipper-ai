# FR-027: CJ US Warehouse Filtering -- Implementation Plan

**Linear ticket:** RAT-45
**Phase:** 3 (Implementation Planning)
**Spec:** `feature-requests/FR-027-cj-us-warehouse-filtering/spec.md`
**Revised:** 2026-04-09 (corrected against verified CJ API documentation)

---

## Technical Design

### Architecture Overview

This feature touches two modules across the discovery-to-fulfillment pipeline, plus a schema migration:

1. **Portfolio module** (`CjDropshippingAdapter`) -- Add `verifiedWarehouse=1` query param to `listV2`; capture `warehouseInventoryNum` from response; exclude zero/null inventory products.
2. **Fulfillment module** (`CjSupplierOrderAdapter`, `SupplierProductMappingResolver`, `SupplierOrderPlacementService`, `SupplierOrderRequest`) -- Derive `fromCountryCode` from mapping's `warehouse_country_code`; add `logisticName` from config; inject Spring `ObjectMapper`.
3. **Persistence** (Flyway V22 migration) -- Add `warehouse_country_code VARCHAR(10)` nullable column to `supplier_product_mappings`.

The changes are scoped entirely within adapter/proxy and service layers. No domain model changes required -- `RawCandidate.demandSignals: Map<String, String>` already carries arbitrary metadata, and `SupplierProductMapping` is a simple data class (not a JPA entity).

### Data Flow

```
CJ listV2 API (countryCode=US, verifiedWarehouse=1)
     |
     v
CjDropshippingAdapter.fetch()
     |-- Parse warehouseInventoryNum from each product (get() + NullNode guard)
     |-- Exclude products where warehouseInventoryNum is zero, null, or absent
     |-- Add cj_warehouse_inventory_num to demandSignals
     v
DemandScanJob.collectFromSources()
     |-- Score, deduplicate, persist as DemandCandidate
     |-- demandSignals JSON contains cj_warehouse_inventory_num
     v
(SKU created, supplier_product_mappings row inserted)
     |-- warehouse_country_code = "US" for CJ US-warehouse products
     v
SupplierOrderPlacementService.placeSupplierOrder()
     |-- SupplierProductMappingResolver returns mapping with warehouseCountryCode
     |-- Pass warehouseCountryCode to SupplierOrderRequest
     v
CjSupplierOrderAdapter.placeOrder()
     |-- fromCountryCode = request.warehouseCountryCode ?: "CN" (backward compat)
     |-- logisticName from config property
     |-- Log fromCountryCode + logisticName at INFO
```

### Key API Details (from verified CJ documentation)

**Discovery (`listV2` request params):**
- `countryCode=US` -- already present, filters to US-shippable products
- `verifiedWarehouse=1` -- NEW, restricts to products with CJ-verified warehouse inventory

**Discovery (`listV2` response field):**
- `warehouseInventoryNum` (integer) -- total inventory quantity across warehouses. Zero/null = no verified stock.

**Ordering (`createOrderV2` request body):**
- `fromCountryCode` -- currently hardcoded to `"CN"`, will derive from mapping's `warehouse_country_code`
- `logisticName` -- required field, currently not sent (pre-existing bug)
- `shopLogisticsType` -- not sent; default is `2` (Seller Logistics), which lets CJ handle warehouse routing

**Out of scope:** `warehouses` array parsing, `inventoryInfo` field parsing, per-product `/product/stock/getInventoryByPid` calls, explicit warehouse selection via `storageId`.

---

## Architecture Decisions

### AD-1: Derive `fromCountryCode` from mapping, not hardcode "US"

**Decision:** `CjSupplierOrderAdapter` reads `warehouseCountryCode` from `SupplierOrderRequest` and uses it as `fromCountryCode`. Falls back to `"CN"` when null (legacy mappings).

**Why:** Hardcoding `"US"` would break existing orders placed against legacy supplier mappings that have `warehouse_country_code = NULL`. Deriving from the mapping makes the system data-driven: new US-warehouse mappings get `"US"`, legacy mappings get `"CN"`, and future non-US warehouse mappings (e.g., `"DE"` for EU expansion) work without code changes.

### AD-2: Configurable `logisticName` instead of freight API integration

**Decision:** `logisticName` is a Spring configuration property (`cj-dropshipping.default-logistic-name`) with an empty default. The adapter includes it in the request body if non-blank.

**Why:** A full CJ Freight Calculation API integration (`/logistic/freightCalculate`) requires knowing the specific warehouse, destination, and product weight to query available carriers and rates. This is out of scope for Phase 1. A configurable default is sufficient -- the correct value can be determined empirically against the live API and set in config. If blank, the adapter logs a warning and sends the order without it (CJ may accept orders without `logisticName` -- empirical verification needed).

### AD-3: `shopLogisticsType=2` (default) instead of explicit warehouse selection

**Decision:** Do not send `shopLogisticsType` in the order request. CJ defaults to type 2 (Seller Logistics), which delegates warehouse routing to CJ.

**Why:** Explicit warehouse selection (`shopLogisticsType=1` + `storageId`) requires knowing specific warehouse IDs per product, which come from `/product/stock/getInventoryByPid` (a per-product API call). This multiplies API calls during discovery and adds complexity for marginal benefit. CJ's default routing with `fromCountryCode=US` already restricts to US warehouses, and CJ optimizes for proximity internally. Geographic routing (Chino vs NJ) is deferred per spec.

### AD-4: Warehouse metadata via `demandSignals` map (not new RawCandidate fields)

**Decision:** Carry `cj_warehouse_inventory_num` through `RawCandidate.demandSignals` as a string key-value.

**Why:** `RawCandidate` is a cross-provider domain type. Adding CJ-specific inventory fields would violate the open/closed principle and force null fields on every other provider. The `demandSignals: Map<String, String>` field exists precisely for provider-specific metadata.

### AD-5: Single nullable column, not JSONB

**Decision:** Add `warehouse_country_code VARCHAR(10)` as a single nullable column on `supplier_product_mappings`. No `warehouse_code` column (unlike first-pass plan).

**Why:** The spec defers explicit warehouse selection (Chino vs NJ). Without warehouse IDs, a `warehouse_code` column has no data to store. The `warehouse_country_code` alone is sufficient for determining `fromCountryCode`. If explicit warehouse selection is added later, a follow-up migration can add `warehouse_code`. This avoids storing empty columns.

---

## Layer-by-Layer Implementation

### Layer 1: Flyway V22 Migration

**File:** `modules/app/src/main/resources/db/migration/V22__supplier_mapping_warehouse_country.sql`

```sql
-- FR-027: CJ US Warehouse Filtering
-- Add warehouse country code to supplier product mappings.
-- Nullable for backward compatibility: NULL = legacy mapping (assumed CN origin).
ALTER TABLE supplier_product_mappings
    ADD COLUMN warehouse_country_code VARCHAR(10);
```

Single nullable column. No default value -- existing rows retain NULL (NFR-2). No index needed: queries filter by `(sku_id, supplier_type)` which already has a unique index; `warehouse_country_code` is read as part of the row, not used as a filter predicate.

### Layer 2: SupplierProductMapping + Resolver

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/SupplierProductMappingResolver.kt`

**2a.** Extend `SupplierProductMapping` data class:

```kotlin
data class SupplierProductMapping(
    val supplierProductId: String,
    val supplierVariantId: String,
    val warehouseCountryCode: String? = null
)
```

Default `null` so existing callers constructing `SupplierProductMapping` in tests continue to compile without changes.

**2b.** Update native SQL query to SELECT the new column:

```kotlin
"""SELECT supplier_product_id, supplier_variant_id, warehouse_country_code
   FROM supplier_product_mappings
   WHERE sku_id = :skuId AND supplier_type = 'CJ_DROPSHIPPING'"""
```

**2c.** Update row mapping:

```kotlin
val row = results.first() as Array<*>
return SupplierProductMapping(
    supplierProductId = row[0] as String,
    supplierVariantId = row[1] as String,
    warehouseCountryCode = row[2] as? String
)
```

`row[2] as? String` safely handles NULL from legacy rows.

### Layer 3: SupplierOrderRequest

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/SupplierOrderAdapter.kt`

Add `warehouseCountryCode` field with default for backward compatibility:

```kotlin
data class SupplierOrderRequest(
    val orderNumber: String,
    val shippingAddress: ShippingAddress?,
    val supplierProductId: String,
    val supplierVariantId: String,
    val quantity: Int,
    val warehouseCountryCode: String? = null
)
```

All existing callers and test helpers (`validRequest()` in WireMock test) continue to compile without changes.

### Layer 4: CjSupplierOrderAdapter

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjSupplierOrderAdapter.kt`

**4a. Inject Spring `ObjectMapper` (BR-6)**

Replace bare `ObjectMapper()` with constructor-injected bean:

```kotlin
@Component
@Profile("!local")
class CjSupplierOrderAdapter(
    @Value("\${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("\${cj-dropshipping.api.access-token:}") private val accessToken: String,
    @Value("\${cj-dropshipping.default-logistic-name:}") private val logisticName: String,
    private val objectMapper: ObjectMapper
) : SupplierOrderAdapter {
```

Per CLAUDE.md #13: `logisticName` uses `${key:}` empty default so the bean can instantiate under any profile.

**4b. Remove bare ObjectMapper line**

Delete: `private val objectMapper = ObjectMapper()`

**4c. Derive `fromCountryCode` from request (BR-3)**

Replace the hardcoded `"fromCountryCode" to "CN"` line:

```kotlin
val fromCountryCode = request.warehouseCountryCode ?: "CN"
```

**4d. Build `logisticName` into body (BR-4)**

Add `logisticName` to the body map conditionally:

```kotlin
val body = mutableMapOf(
    "orderNumber" to request.orderNumber,
    "shippingCountryCode" to (address?.countryCode ?: "US"),
    "shippingCountry" to (address?.country ?: "United States"),
    "shippingCustomerName" to (address?.customerName ?: ""),
    "shippingAddress" to shippingAddressLine,
    "shippingCity" to (address?.city ?: ""),
    "shippingProvince" to (address?.province ?: ""),
    "shippingZip" to (address?.zip ?: ""),
    "shippingPhone" to (address?.phone ?: ""),
    "fromCountryCode" to fromCountryCode,
    "products" to listOf(
        mapOf(
            "vid" to request.supplierVariantId,
            "quantity" to request.quantity
        )
    )
)

if (logisticName.isNotBlank()) {
    body["logisticName"] = logisticName
}
```

Note: body changes from `mapOf` (immutable) to `mutableMapOf` to allow conditional `logisticName` addition.

**4e. Log routing decision at INFO (NFR-4)**

Before the HTTP call:

```kotlin
logger.info(
    "CJ order {}: fromCountryCode={}, logisticName={}",
    request.orderNumber, fromCountryCode, logisticName.ifBlank { "(not configured)" }
)
```

**4f. Warn on blank `logisticName` at startup**

Not needed as a separate init block -- the INFO log per order already shows `"(not configured)"` when blank. If a startup-time warning is desired, add a `@PostConstruct` method:

```kotlin
@PostConstruct
private fun warnIfLogisticNameBlank() {
    if (logisticName.isBlank()) {
        logger.warn("cj-dropshipping.default-logistic-name is not configured — orders will omit logisticName")
    }
}
```

However, since this is a `@Profile("!local")` component, the warn only fires in non-local profiles. This is acceptable.

### Layer 5: SupplierOrderPlacementService

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/SupplierOrderPlacementService.kt`

Pass `warehouseCountryCode` from resolved mapping to order request:

```kotlin
val request = SupplierOrderRequest(
    orderNumber = orderId.toString(),
    shippingAddress = order.shippingAddress,
    supplierProductId = mapping.supplierProductId,
    supplierVariantId = mapping.supplierVariantId,
    quantity = order.quantity,
    warehouseCountryCode = mapping.warehouseCountryCode
)
```

If `mapping.warehouseCountryCode` is null (legacy), `SupplierOrderRequest.warehouseCountryCode` is null, and the adapter falls back to `"CN"`.

### Layer 6: CjDropshippingAdapter

**File:** `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/CjDropshippingAdapter.kt`

**6a. Add `verifiedWarehouse=1` query param (BR-1)**

In the `fetch()` method, update the URI builder:

```kotlin
.uri { uri ->
    uri.path("/product/listV2")
        .queryParam("keyWord", category)
        .queryParam("countryCode", "US")
        .queryParam("verifiedWarehouse", 1)
        .queryParam("page", 1)
        .queryParam("size", 20)
        .build()
}
```

**6b. Extract `warehouseInventoryNum` with NullNode guard (CLAUDE.md #17)**

In the product iteration loop, before calling `mapProduct()`:

```kotlin
for (product in products) {
    val inventoryNum = product.get("warehouseInventoryNum")
        ?.let { if (!it.isNull) it.asInt(-1) else null }

    if (inventoryNum == null || inventoryNum <= 0) {
        val pid = product.path("pid").asText("unknown")
        logger.debug("Excluding CJ product {} — warehouseInventoryNum is {} (zero, null, or absent)", pid, inventoryNum)
        continue
    }

    candidates.add(mapProduct(product, category, inventoryNum))
}
```

Uses `get()` (not `path()`) per CLAUDE.md #15 because we need null-coalescing. The NullNode guard `?.let { if (!it.isNull) ... else null }` per CLAUDE.md #17 handles JSON `null` values correctly. `asInt(-1)` returns -1 for non-numeric values, which the `<= 0` check catches.

**6c. Add `cj_warehouse_inventory_num` to demandSignals (BR-1)**

Update `mapProduct()` to accept the inventory number and include it:

```kotlin
private fun mapProduct(product: JsonNode, category: String, warehouseInventoryNum: Int): RawCandidate {
    val sellPrice = product.path("sellPrice").asDouble(0.0)
    return RawCandidate(
        productName = product.path("productNameEn").asText("Unknown Product"),
        category = product.path("categoryName").asText(category),
        description = product.path("description").asText(""),
        sourceType = sourceType(),
        supplierUnitCost = Money.of(BigDecimal.valueOf(sellPrice), Currency.USD),
        estimatedSellingPrice = Money.of(
            BigDecimal.valueOf(sellPrice).multiply(BigDecimal("2.5")),
            Currency.USD
        ),
        demandSignals = mapOf(
            "cj_pid" to product.path("pid").asText(""),
            "cj_category_id" to product.path("categoryId").asText(""),
            "cj_product_image" to product.path("productImage").asText(""),
            "cj_warehouse_inventory_num" to warehouseInventoryNum.toString()
        )
    )
}
```

Note: `path()` is correct for the existing fields (`pid`, `categoryId`, etc.) because they use `asText("")` with an explicit default (CLAUDE.md #15 -- `path()` is fine when not using null-coalescing).

**6d. Log filtering stats per category**

After the product loop:

```kotlin
val totalProducts = products?.size() ?: 0
logger.info("CJ category '{}': {} total products, {} passed warehouse inventory filter", category, totalProducts, candidatesFromCategory)
```

Where `candidatesFromCategory` is the count added in this iteration.

### Layer 7: WireMock Fixtures

**7a. Update `product-list-success.json`**

**File:** `modules/portfolio/src/test/resources/wiremock/cj/product-list-success.json`

Add `warehouseInventoryNum` field to all three existing products:

```json
{
  "pid": "04A22450-67F0-4617-A132-E7AE7F8963B0",
  "productNameEn": "Stainless Steel Kitchen Knife Set",
  ...existing fields...,
  "warehouseInventoryNum": 500
}
```

All three products get positive inventory numbers so the existing happy-path test continues to pass.

**7b. Create `product-list-zero-inventory.json`**

**File:** `modules/portfolio/src/test/resources/wiremock/cj/product-list-zero-inventory.json`

Products where `warehouseInventoryNum` is 0. Tests NFR-3 fail-closed behavior.

**7c. Create `product-list-null-inventory.json`**

**File:** `modules/portfolio/src/test/resources/wiremock/cj/product-list-null-inventory.json`

Products where `warehouseInventoryNum` is `null` or absent. Tests NFR-3 fail-closed behavior.

**7d. Create `product-list-mixed-inventory.json`**

**File:** `modules/portfolio/src/test/resources/wiremock/cj/product-list-mixed-inventory.json`

7 products with mixed inventory states: 2 positive (pass), 1 zero, 1 null, 1 absent field, 1 negative, 1 string-type. Tests selective filtering — only the 2 positive-inventory products should be returned. See test-spec.md Section 2.4 for exact fixture JSON.

### Layer 8: Application Config

**File:** `modules/app/src/main/resources/application.yml`

Add `default-logistic-name` under the existing `cj-dropshipping` section:

```yaml
cj-dropshipping:
  api:
    base-url: ${CJ_API_BASE_URL:https://developers.cjdropshipping.com/api2.0/v1}
    access-token: ${CJ_ACCESS_TOKEN:}
  default-logistic-name: ${CJ_DEFAULT_LOGISTIC_NAME:}
  webhook:
    secret: ${CJ_WEBHOOK_SECRET:}
```

Empty default per CLAUDE.md #13. The correct logistic name value will be determined empirically against the CJ API and set via environment variable.

---

## Task Breakdown

### Tier 1: Schema & Data Model (no dependencies)

- [x] **T1.1** Create Flyway migration `V22__supplier_mapping_warehouse_country.sql` -- add nullable `warehouse_country_code VARCHAR(10)` to `supplier_product_mappings`
- [x] **T1.2** Extend `SupplierProductMapping` data class with `warehouseCountryCode: String? = null`
- [x] **T1.3** Update `SupplierProductMappingResolver` native SQL to SELECT `warehouse_country_code` and map `row[2] as? String`
- [x] **T1.4** Add `warehouseCountryCode: String? = null` to `SupplierOrderRequest`

### Tier 2: Order Routing (depends on Tier 1)

- [x] **T2.1** Inject Spring `ObjectMapper` into `CjSupplierOrderAdapter` constructor, remove bare `ObjectMapper()` (BR-6)
- [x] **T2.2** Add `@Value("\${cj-dropshipping.default-logistic-name:}")` for `logisticName` to `CjSupplierOrderAdapter` constructor
- [x] **T2.3** Replace hardcoded `"fromCountryCode" to "CN"` with `request.warehouseCountryCode ?: "CN"` (BR-3)
- [x] **T2.4** Change body from `mapOf` to `mutableMapOf`; add conditional `logisticName` to body (BR-4)
- [x] **T2.5** Add INFO log for `fromCountryCode` and `logisticName` per order (NFR-4)
- [x] **T2.6** Add `@PostConstruct` warning if `logisticName` is blank
- [x] **T2.7** Update `SupplierOrderPlacementService` to pass `mapping.warehouseCountryCode` to `SupplierOrderRequest`

### Tier 3: Discovery Filtering (independent of Tier 2)

- [x] **T3.1** Add `verifiedWarehouse=1` query param to `CjDropshippingAdapter.fetch()` URI builder
- [x] **T3.2** Add inventory extraction with `get()` + NullNode guard; exclude zero/null/absent inventory products with DEBUG log
- [x] **T3.3** Update `mapProduct()` signature to accept `warehouseInventoryNum: Int`; add `cj_warehouse_inventory_num` to demandSignals
- [x] **T3.4** Add INFO log for warehouse filtering stats per category

### Tier 4: Fixtures & Config (supports Tier 5)

- [x] **T4.1** Update `product-list-success.json` -- add `warehouseInventoryNum` field to all 3 products
- [x] **T4.2** Create `product-list-zero-inventory.json` fixture
- [x] **T4.3** Create `product-list-null-inventory.json` fixture
- [x] **T4.4** Create `product-list-mixed-inventory.json` fixture
- [x] **T4.5** Add `default-logistic-name: ${CJ_DEFAULT_LOGISTIC_NAME:}` to `application.yml` under `cj-dropshipping`

### Tier 5: Tests

- [x] **T5.1** Update `CjDropshippingAdapterWireMockTest` happy-path test: verify `verifiedWarehouse=1` param sent, verify `cj_warehouse_inventory_num` in demandSignals
- [x] **T5.2** New test: `product-list-zero-inventory.json` -- all products excluded, returns empty list
- [x] **T5.3** New test: `product-list-null-inventory.json` -- all products excluded, returns empty list (NFR-3)
- [x] **T5.4** New test: `product-list-mixed-inventory.json` -- only positive-inventory products returned
- [x] **T5.5** Update `CjSupplierOrderAdapterWireMockTest` `adapter()` helper: pass `jacksonObjectMapper()` and `""` for logisticName
- [x] **T5.6** New test: `warehouseCountryCode = "US"` -- request body contains `"fromCountryCode":"US"`
- [x] **T5.7** New test: `warehouseCountryCode = null` (legacy) -- request body contains `"fromCountryCode":"CN"`
- [x] **T5.8** New test: `logisticName` configured -- request body contains `"logisticName":"CJPacket"` (or configured value)
- [x] **T5.9** New test: `logisticName` blank -- request body does NOT contain `logisticName` key
- [x] **T5.10** Update `SupplierOrderPlacementServiceTest` happy-path: `SupplierProductMapping` now has `warehouseCountryCode`, verify it flows to `SupplierOrderRequest`
- [x] **T5.11** Verify all 10 existing `CjSupplierOrderAdapterWireMockTest` tests pass with updated constructor

---

## Testing Strategy

### Test-to-BR/SC Mapping

| Test | BR/SC | What It Verifies |
|---|---|---|
| T5.1: happy path + `verifiedWarehouse=1` | BR-1, SC-1 | Query param sent to CJ; inventory num in demandSignals (SC-2) |
| T5.2: zero inventory excluded | BR-1, NFR-3, SC-1 | Fail-closed on zero inventory |
| T5.3: null inventory excluded | BR-1, NFR-3, SC-1 | Fail-closed on null/absent inventory |
| T5.4: mixed inventory filtering | BR-1, SC-1 | Selective filtering -- only positive inventory passes |
| T5.5: adapter helper updated | BR-6, SC-7 | Injected ObjectMapper used (no bare ObjectMapper) |
| T5.6: fromCountryCode=US | BR-3, SC-4 | US warehouse mapping produces US origin |
| T5.7: fromCountryCode=CN fallback | BR-3, NFR-2, SC-4 | Legacy null mapping falls back to CN |
| T5.8: logisticName present | BR-4, SC-5 | Configured logistic name included in body |
| T5.9: logisticName absent | BR-4, SC-5 | Blank config omits logisticName from body |
| T5.10: warehouseCountryCode flow | BR-2, BR-3, SC-3 | Mapping's warehouseCountryCode flows through to request |
| T5.11: existing tests green | All | Backward compatibility of constructor change |

### Constructor Signature Change Impact

The `CjSupplierOrderAdapter` constructor changes from:

```kotlin
CjSupplierOrderAdapter(baseUrl: String, accessToken: String)
```

to:

```kotlin
CjSupplierOrderAdapter(baseUrl: String, accessToken: String, logisticName: String, objectMapper: ObjectMapper)
```

The existing `CjSupplierOrderAdapterWireMockTest.adapter()` helper must be updated:

```kotlin
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

Import: `com.fasterxml.jackson.module.kotlin.jacksonObjectMapper`. Tests that need `logisticName` pass it explicitly; existing tests use the empty default.

The `validRequest()` helper also needs updating for tests that set `warehouseCountryCode`:

```kotlin
private fun validRequest(
    orderNumber: String = "order-001",
    quantity: Int = 2,
    supplierVariantId: String = "vid-abc-123",
    supplierProductId: String = "pid-xyz-456",
    warehouseCountryCode: String? = null
): SupplierOrderRequest = SupplierOrderRequest(
    ...existing fields...,
    warehouseCountryCode = warehouseCountryCode
)
```

### SupplierOrderPlacementServiceTest Impact

The existing test at line 76 constructs `SupplierProductMapping` without `warehouseCountryCode`:

```kotlin
val mapping = SupplierProductMapping(supplierProductId = "pid1", supplierVariantId = "vid1")
```

This still compiles because `warehouseCountryCode` has a default value of `null`. Existing tests remain unchanged. A new test should construct with `warehouseCountryCode = "US"` and verify the `argThat` matcher checks `warehouseCountryCode == "US"` on the captured `SupplierOrderRequest`.

### What This Testing Strategy Does NOT Cover

- **Live CJ API validation** -- WireMock fixtures include `warehouseInventoryNum` based on CJ documentation. The actual field name must be confirmed against a real `listV2` response before production deployment.
- **Inventory staleness** -- A product may show positive `warehouseInventoryNum` at discovery time but be out of stock at order time. Handled by existing Resilience4j retry/circuit breaker.
- **logisticName correctness** -- The correct value for `logisticName` depends on the CJ account configuration and available carriers. Requires empirical verification.

---

## Rollout Plan

### Pre-deployment

1. **Validate CJ API response structure** -- Make a real `listV2` call with `verifiedWarehouse=1` and confirm `warehouseInventoryNum` appears in the response with expected semantics (integer, positive = in stock). If the field name or behavior differs, update adapter parsing and fixtures before deploying.
2. **Determine logistic name** -- Query CJ documentation or support for available `logisticName` values for US-to-US domestic shipping with `fromCountryCode=US`. Set `CJ_DEFAULT_LOGISTIC_NAME` env var accordingly.
3. **Run Flyway migration on staging** -- `V22__supplier_mapping_warehouse_country.sql`. Verify existing `supplier_product_mappings` rows retain NULL for `warehouse_country_code`.

### Deployment

1. Deploy the updated application. V22 migration runs automatically on startup.
2. Existing supplier product mappings (`warehouse_country_code = NULL`) continue to work -- `CjSupplierOrderAdapter` falls back to `"fromCountryCode": "CN"`, matching current behavior.
3. New products discovered by `DemandScanJob` will only include verified-inventory US-warehouse products.

### Post-deployment Validation

1. Trigger a `DemandScanJob` run (or wait for scheduled cron). Verify:
   - CJ candidates have `cj_warehouse_inventory_num` in their `demandSignals` JSON
   - Log output shows per-category filtering: `"CJ category 'X': Y total products, Z passed warehouse inventory filter"`
2. Place a test order for a new US-warehouse SKU. Verify:
   - Order log shows `"CJ order {id}: fromCountryCode=US, logisticName=..."`
   - CJ API accepts the order (check for success response)
3. Place a test order for a legacy SKU (no warehouse mapping). Verify:
   - Order log shows `fromCountryCode=CN` (backward compatible)

### Rollback Procedure

1. **Code rollback** -- Revert to previous application version. Adapter returns to hardcoded `"CN"` and no `logisticName`. The `warehouseCountryCode` field on `SupplierOrderRequest` defaults to `null`, so no callers break.
2. **Schema** -- V22 migration only adds a nullable column. No data loss. Column can remain harmlessly or be dropped:
   ```sql
   ALTER TABLE supplier_product_mappings DROP COLUMN IF EXISTS warehouse_country_code;
   ```

### Risk Matrix

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `warehouseInventoryNum` field absent from real API response | Low | High -- zero products discovered | Pre-deployment API validation; spec confirmed field from CJ docs |
| `verifiedWarehouse=1` param ignored or unsupported by CJ | Low | Medium -- unfiltered results (same as today) | Adapter still excludes zero/null inventory products client-side |
| `logisticName` value incorrect for US domestic | Medium | Medium -- CJ rejects order or uses bad carrier | Configurable; empirical verification; CJ may accept without it |
| CJ returns `warehouseInventoryNum=0` for all US products | Low | High -- empty discovery pipeline | Monitor `DemandScanJob` results; remove `verifiedWarehouse=1` if needed |
| ObjectMapper injection breaks serialization | Very Low | High -- all CJ orders fail | `jacksonObjectMapper()` in tests validates serialization; Spring auto-configures Kotlin module |
