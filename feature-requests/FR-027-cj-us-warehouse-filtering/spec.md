# FR-027: CJ US Warehouse Filtering

**Linear ticket:** RAT-45
**Status:** Specification (Phase 2)
**Created:** 2026-04-09
**Revised:** 2026-04-09 (corrected against verified CJ API documentation)

---

## Problem Statement

All CJ Dropshipping orders currently source from China (`"fromCountryCode" to "CN"` hardcoded in `CjSupplierOrderAdapter`, line 49). This results in:

1. **7-21 day shipping times** to US customers, causing poor customer experience and elevated refund/chargeback rates that threaten the 5% refund / 2% chargeback auto-shutdown thresholds.
2. **Higher fully-burdened costs** from international shipping, customs duties, and the `taxesAndDuties` component in the cost envelope, reducing the number of SKUs that pass the stress test gate (gross margin >= 50%, protected net margin >= 30%).
3. **Customs and regulatory complexity** that adds compliance surface area unnecessary for Phase 1 domestic-only operations.
4. **No warehouse awareness anywhere in the pipeline** -- `DemandScanJob` discovers products without filtering by verified inventory, `supplier_product_mappings` stores no warehouse metadata, and order creation sends no logistics carrier name (`logisticName` is required by CJ but not sent -- a pre-existing bug).

CJ Dropshipping operates US-based warehouses including **CJ US West (Chino)** and **CJ US East (NJ)**. Products stocked in these warehouses ship domestically in 2-5 business days at domestic carrier rates. For Phase 1, the system should exclusively discover and source products with verified US warehouse inventory.

### Pre-existing bugs addressed by this feature

Two pre-existing issues in `CjSupplierOrderAdapter` must be fixed as part of this work because they directly affect correct US warehouse ordering:

- **Missing `logisticName`**: The CJ `createOrderV2` API requires `logisticName` (logistics carrier name). Our adapter does not send it. Orders may fail or default to an undesirable carrier.
- **Bare `ObjectMapper()`**: Line 20 constructs `ObjectMapper()` directly instead of injecting the Spring-managed bean (CLAUDE.md constraint #20 violation).

## Business Requirements

### BR-1: Discovery restricted to verified US-warehouse products

`CjDropshippingAdapter.fetch()` must add `verifiedWarehouse=1` to the `listV2` query parameters, restricting results to products with CJ-verified inventory. The existing `countryCode=US` parameter already filters to products with US warehouse stock. Combined, these two parameters ensure only products with verified inventory in US warehouses are returned.

Additionally, the adapter must capture `warehouseInventoryNum` (total inventory quantity) from each product in the response and include it in the `demandSignals` map as `cj_warehouse_inventory_num`. Products where this field is zero, null, or absent must be excluded.

### BR-2: Warehouse country code persisted with supplier mapping

When a CJ product is mapped to a SKU (via `supplier_product_mappings`), a `warehouse_country_code` column must record the warehouse country. For Phase 1, all CJ products discovered through the US-filtered pipeline will have `warehouse_country_code = 'US'`. This column provides:
- Audit trail confirming the product was sourced from a US warehouse
- Future extensibility for multi-country warehouse support
- Query capability for reporting on sourcing geography

The `SupplierProductMappingResolver` must return this field in its `SupplierProductMapping` data class.

### BR-3: Orders routed from US origin

`CjSupplierOrderAdapter` must change `fromCountryCode` from `"CN"` to `"US"` for orders where the supplier product mapping has `warehouse_country_code = 'US'`. This tells CJ to fulfill from a US warehouse.

For Phase 1, warehouse selection within the US (Chino vs NJ) is delegated to CJ. The adapter will use the default `shopLogisticsType=2` (Seller Logistics), which lets CJ choose the optimal warehouse and carrier. Explicit warehouse selection via `shopLogisticsType=1` + `storageId` is deferred to a follow-up feature when geographic routing data justifies the complexity.

**Rationale for deferring explicit warehouse selection:** The CJ API's `storageId` parameter requires knowing specific warehouse IDs, which come from `/product/stock/getInventoryByPid` (per-product API call) or `/product/globalWarehouseList`. Adding per-product stock lookups during discovery multiplies API calls by the number of products. CJ's default routing with `fromCountryCode=US` already restricts to US warehouses and CJ optimizes for proximity. The marginal benefit of explicit Chino-vs-NJ selection does not justify the API complexity for Phase 1.

### BR-4: `logisticName` gap addressed

The `CjSupplierOrderAdapter` must send the `logisticName` field in order creation requests. For Phase 1, this will be a configurable property (`cj-dropshipping.default-logistic-name`) with a sensible default. The exact value must be determined during implementation by consulting the CJ Freight Calculation API or CJ documentation for available US domestic logistics options. If the correct value cannot be determined before implementation, the adapter must:
1. Include `logisticName` in the request body sourced from the config property
2. Log a warning at startup if the property is blank
3. Allow orders to proceed without it (CJ may accept orders without `logisticName` despite documenting it as required -- this needs empirical verification)

### BR-5: Cost envelope reflects domestic shipping reality

For SKUs sourced from US warehouses:
- **`taxesAndDuties`**: Should be `Money.ZERO` for domestic US-to-US shipments (no customs). Callers of `CostGateService.verify()` are responsible for passing zero when the supplier mapping has `warehouse_country_code = 'US'`.
- **`inboundShipping`**: Remains `Money.ZERO` for dropshipping (supplier ships directly to customer).
- **`outboundShipping`**: No change needed. Already fetched from carrier rate providers. Origin address accuracy (Chino vs NJ) is a follow-up concern tied to explicit warehouse selection (deferred per BR-3).

### BR-6: Bare ObjectMapper violation fixed

`CjSupplierOrderAdapter` must inject the Spring-managed `ObjectMapper` bean via constructor injection instead of constructing `ObjectMapper()` directly. Per CLAUDE.md constraint #20.

## Success Criteria

### SC-1: Verified-inventory filter active in discovery

Given a `DemandScanJob` run, when `CjDropshippingAdapter.fetch()` calls the CJ `listV2` endpoint, then the request includes both `countryCode=US` and `verifiedWarehouse=1` query parameters. Products with zero or null `warehouseInventoryNum` are excluded from the returned candidate list.

### SC-2: Warehouse inventory metadata in demand signals

Given a CJ product returned by `listV2` with `warehouseInventoryNum=500`, when mapped to a `RawCandidate`, then `demandSignals` contains `cj_warehouse_inventory_num=500`.

### SC-3: Warehouse country code persisted in mapping

Given a SKU with a CJ supplier product mapping created after this feature, when queried via `SupplierProductMappingResolver.resolve()`, then the returned `SupplierProductMapping` includes `warehouseCountryCode = "US"`. The `supplier_product_mappings` table row has `warehouse_country_code = 'US'`.

### SC-4: Orders specify US origin

Given an order for a US-addressed customer on a SKU with `warehouse_country_code = 'US'`, when `CjSupplierOrderAdapter.placeOrder()` executes, then the CJ API request body contains `"fromCountryCode": "US"`. The hardcoded `"CN"` value is removed.

### SC-5: `logisticName` included in order requests

Given an order placed through `CjSupplierOrderAdapter`, when the request body is sent to CJ, then it includes a `logisticName` field sourced from configuration. If the config property is blank, a warning is logged.

### SC-6: Stress test pass rate improvement

Given identical product sets, SKUs sourced from US warehouses should have `taxesAndDuties = Money.ZERO` (domestic, no customs) in their cost envelopes. This should measurably increase the proportion of SKUs passing the stress test gate compared to China-sourced equivalents. Verified qualitatively by comparing cost envelope components.

### SC-7: ObjectMapper injection

`CjSupplierOrderAdapter` uses the Spring-injected `ObjectMapper` bean via constructor parameter. No bare `ObjectMapper()` constructor calls exist in the class. Verified by code review.

## Non-Functional Requirements

### NFR-1: No additional API calls for warehouse filtering during discovery

Warehouse filtering in discovery must use only data returned by the existing `listV2` endpoint call. The `countryCode` and `verifiedWarehouse` parameters are request-side filters; `warehouseInventoryNum` is a response-side field. No per-product secondary API calls (e.g., `/product/stock/getInventoryByPid`) are required or permitted during discovery. Per-product stock queries are deferred to future explicit warehouse selection work.

### NFR-2: Backward-compatible schema migration

The `supplier_product_mappings` table alteration must be a new Flyway migration (V22) that adds `warehouse_country_code` as a nullable `VARCHAR(10)` column with no default. Existing rows retain `NULL`, indicating legacy mappings created before warehouse awareness. The system must handle `NULL` warehouse country code gracefully:
- `SupplierProductMappingResolver` returns `null` for `warehouseCountryCode` on legacy rows
- `CjSupplierOrderAdapter` falls back to `fromCountryCode = "CN"` when warehouse country code is `null` (backward-compatible with pre-existing mappings)

### NFR-3: Graceful degradation on inventory data absence

If a product's `warehouseInventoryNum` field is missing, null, zero, or not a valid number in the CJ API response, the product must be excluded from discovery results. Fail closed, not open. Log at DEBUG level with the product ID for troubleshooting.

### NFR-4: Order routing auditable

The `fromCountryCode` value and `logisticName` used for each order must be logged at INFO level with the order number. This supports debugging shipping delays and cost discrepancies.

### NFR-5: No impact on non-CJ providers

Other `DemandSignalProvider` implementations (Google Trends, Reddit, YouTube, CJ Affiliate) are unaffected. Warehouse filtering is CJ-specific logic within `CjDropshippingAdapter`. The `warehouse_country_code` column on `supplier_product_mappings` is supplier-agnostic by design but only populated by CJ flows in Phase 1.

### NFR-6: Configuration over hardcoding

The `logisticName` default must be a Spring configuration property (`cj-dropshipping.default-logistic-name`), not a hardcoded string. The `fromCountryCode` is derived from the mapping's `warehouse_country_code`, not hardcoded to any single value.

### NFR-7: Test coverage

- WireMock tests for `CjDropshippingAdapter`: `verifiedWarehouse=1` param sent, products with zero inventory excluded, `cj_warehouse_inventory_num` captured in demand signals
- WireMock tests for `CjSupplierOrderAdapter`: `fromCountryCode=US` when warehouse country is US, `fromCountryCode=CN` fallback for legacy null mappings, `logisticName` included, injected ObjectMapper used
- Unit tests for `SupplierProductMappingResolver`: returns `warehouseCountryCode` field, handles null for legacy rows
- Schema migration test: V22 migration applies cleanly, existing rows unaffected

## Dependencies

### Internal Dependencies

| Component | Change Type | Description |
|---|---|---|
| `CjDropshippingAdapter` (portfolio) | Modified | Add `verifiedWarehouse=1` query param; capture `warehouseInventoryNum` in demand signals; exclude products with zero/null inventory |
| `CjSupplierOrderAdapter` (fulfillment) | Modified | Derive `fromCountryCode` from mapping's warehouse country code; add `logisticName` from config; inject Spring `ObjectMapper`; fall back to `"CN"` for legacy null mappings |
| `SupplierOrderRequest` (fulfillment) | Modified | Add `warehouseCountryCode: String?` field to carry warehouse metadata from mapping to adapter |
| `SupplierProductMapping` (fulfillment) | Modified | Add `warehouseCountryCode: String?` field |
| `SupplierProductMappingResolver` (fulfillment) | Modified | Select `warehouse_country_code` in native query; populate new field |
| `SupplierOrderPlacementService` (fulfillment) | Modified | Pass `warehouseCountryCode` from resolved mapping into `SupplierOrderRequest` |
| `supplier_product_mappings` table | Schema change | V22 migration adding `warehouse_country_code VARCHAR(10)` nullable column |
| `RawCandidate` (portfolio) | Unchanged | Warehouse metadata flows through existing `demandSignals: Map<String, String>` |
| `CostGateService` (catalog) | Unchanged | Callers responsible for passing correct `taxesAndDuties` (zero for US-warehouse-sourced SKUs) |

### External Dependencies

| Dependency | Risk | Mitigation |
|---|---|---|
| CJ `listV2` API `verifiedWarehouse` param | Parameter documented but behavior may differ from docs | WireMock tests for adapter; manual verification against live API in staging |
| CJ `listV2` response `warehouseInventoryNum` field | Field documented; value may be stale or zero for some products | Exclude zero/null values (NFR-3); accept that real-time stock accuracy is a CJ platform limitation |
| CJ `createOrderV2` `fromCountryCode=US` behavior | Switching from CN to US may surface different validation requirements or error codes | Resilience4j circuit breaker already wraps adapter; log full error responses for debugging |
| CJ `logisticName` valid values | Available values depend on `fromCountryCode` and destination; no freight adapter exists yet | Make configurable; log warning if blank; empirically verify with live API |
| CJ warehouse inventory levels | Products may show as available but be out of stock at order time | Handle as order-placement failure via existing Resilience4j retry; not solvable at discovery time |

### Constraint Dependencies

| CLAUDE.md Constraint | Relevance |
|---|---|
| #13 (@Value empty defaults) | New `cj-dropshipping.default-logistic-name` property must use `${key:}` syntax |
| #15 (Jackson get vs path) | Any new JSON parsing of CJ response fields must use `get()` for null-coalescing |
| #17 (NullNode guard) | Any new `get()?.asText()` calls on CJ JSON must use `?.let { if (!it.isNull) it.asText() else null }` |
| #20 (No bare ObjectMapper) | BR-6 explicitly addresses this for `CjSupplierOrderAdapter` |

## Out of Scope

The following are explicitly deferred and not part of this feature:

1. **Explicit warehouse selection (Chino vs NJ)** -- Requires per-product `/product/stock/getInventoryByPid` calls and `shopLogisticsType=1` + `storageId` on order creation. Deferred to a follow-up feature.
2. **Geographic routing (West Coast to Chino, East Coast to NJ)** -- Depends on explicit warehouse selection. CJ handles routing internally when given `fromCountryCode=US`.
3. **CJ Freight Calculation API integration** -- A full freight adapter for querying available logistics options and rates. For Phase 1, `logisticName` is a static config value.
4. **`inventoryInfo` field parsing** -- The `listV2` response includes an `inventoryInfo` string field described as "Warehouse inventory details JSON" but with no documented structure. We use the well-documented `warehouseInventoryNum` integer field instead.
5. **Multi-country warehouse support** -- The `warehouse_country_code` column supports future expansion, but only US is populated in Phase 1.
6. **Origin address accuracy for carrier rate lookups** -- Passing Chino or NJ as the origin for outbound shipping rate calculations requires knowing the specific warehouse. Deferred with explicit warehouse selection.
