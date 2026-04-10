# FR-027: CJ US Warehouse Filtering — Summary

**Linear ticket:** RAT-45
**Phase:** 5 (Implementation Complete)

---

## Feature Summary

Scoped CJ Dropshipping product discovery and order placement to US warehouses only. Products are now filtered to verified US warehouse inventory during discovery, and orders specify US as the shipment origin country instead of the previously hardcoded China origin. This enables 2-5 day domestic shipping, lower costs, zero customs duties, and better stress test pass rates for Phase 1.

## Changes Made

### Discovery Filtering (portfolio module)
- `CjDropshippingAdapter` now sends `verifiedWarehouse=1` alongside the existing `countryCode=US` query param, restricting results to products with CJ-verified US warehouse inventory
- Products with zero, null, absent, negative, or non-numeric `warehouseInventoryNum` are excluded (fail-closed per NFR-3)
- `cj_warehouse_inventory_num` captured in `demandSignals` for downstream audit
- INFO logging for per-category filtering stats

### Order Routing (fulfillment module)
- `CjSupplierOrderAdapter` derives `fromCountryCode` from the supplier mapping's `warehouseCountryCode` instead of hardcoding `"CN"`. Legacy mappings (null) fall back to `"CN"` for backward compatibility.
- `logisticName` added from configurable property `cj-dropshipping.default-logistic-name` (was a required CJ API field that was never sent — pre-existing bug)
- Spring-managed `ObjectMapper` injected, replacing bare `ObjectMapper()` (CLAUDE.md #20 fix)
- `@PostConstruct` warning if `logisticName` is not configured
- INFO logging for fromCountryCode + logisticName per order

### Data Model (fulfillment module)
- `SupplierProductMapping` and `SupplierOrderRequest` extended with `warehouseCountryCode: String? = null`
- `SupplierProductMappingResolver` native SQL updated to SELECT `warehouse_country_code`
- `SupplierOrderPlacementService` threads `warehouseCountryCode` from mapping to order request

### Schema
- V22 migration adds nullable `warehouse_country_code VARCHAR(10)` to `supplier_product_mappings`

## Files Modified

| File | Change |
|------|--------|
| `modules/portfolio/src/main/kotlin/.../CjDropshippingAdapter.kt` | verifiedWarehouse param, inventory filtering, demandSignals |
| `modules/fulfillment/src/main/kotlin/.../CjSupplierOrderAdapter.kt` | ObjectMapper injection, logisticName, fromCountryCode derivation |
| `modules/fulfillment/src/main/kotlin/.../SupplierProductMappingResolver.kt` | warehouseCountryCode field + SQL |
| `modules/fulfillment/src/main/kotlin/.../SupplierOrderAdapter.kt` | warehouseCountryCode on SupplierOrderRequest |
| `modules/fulfillment/src/main/kotlin/.../SupplierOrderPlacementService.kt` | Pass warehouseCountryCode through |
| `modules/app/src/main/resources/db/migration/V22__supplier_mapping_warehouse_country.sql` | New migration |
| `modules/app/src/main/resources/application.yml` | default-logistic-name config |
| `modules/portfolio/src/test/resources/wiremock/cj/product-list-success.json` | Added warehouseInventoryNum |
| `modules/portfolio/src/test/resources/wiremock/cj/product-list-zero-inventory.json` | New fixture |
| `modules/portfolio/src/test/resources/wiremock/cj/product-list-null-inventory.json` | New fixture |
| `modules/portfolio/src/test/resources/wiremock/cj/product-list-mixed-inventory.json` | New fixture (7 products, 5 boundary cases) |
| `modules/portfolio/src/test/kotlin/.../CjDropshippingAdapterWireMockTest.kt` | 4 tests (1 updated, 3 new) |
| `modules/fulfillment/src/test/kotlin/.../CjSupplierOrderAdapterWireMockTest.kt` | 5 tests (1 helper updated, 4 new) |
| `modules/fulfillment/src/test/kotlin/.../SupplierOrderPlacementServiceTest.kt` | 1 new test |

## Testing Completed

- `./gradlew test` — BUILD SUCCESSFUL, all tests pass
- **Portfolio module:** 8 tests (4 new: zero/null/mixed inventory filtering, query param verification)
- **Fulfillment module:** 15 adapter tests (4 new: US/CN routing, logisticName present/absent), 8 service tests (1 new: warehouseCountryCode flow)
- **Boundary cases covered:** JSON null, absent field, zero, negative, string-type inventory; null/US/DE country code derivation; blank/configured logisticName

## Deployment Notes

1. **V22 migration** runs automatically on startup. Adds nullable column — no data migration needed. Existing rows get NULL (backward-compatible CN fallback).
2. **Set `CJ_DEFAULT_LOGISTIC_NAME` env var** before deployment. Value must match an available CJ logistics option for US-to-US shipping. Determine empirically via CJ Freight Calculation API or CJ support.
3. **Pre-deployment validation:** Make a real CJ `listV2` API call with `verifiedWarehouse=1` to confirm `warehouseInventoryNum` appears in the response.
4. **Rollback:** Revert code only. V22 migration is additive (nullable column). Legacy mappings continue to work with CN fallback.

## Out of Scope (deferred)

- Explicit warehouse selection (Chino vs NJ) via `storageId`
- Geographic routing (West Coast/East Coast)
- CJ Freight Calculation API integration
- `inventoryInfo` field parsing
