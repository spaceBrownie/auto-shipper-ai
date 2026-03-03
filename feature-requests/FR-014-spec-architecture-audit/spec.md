# FR-014: Spec Architecture Audit

## Problem Statement

The `docs/plans/solo-operator-spec.md` is the authoritative design document for the solo-operator commerce engine. Feature requests FR-001 through FR-005 and FR-013 have been implemented, but the spec contains class names, interface signatures, enum values, table names, and cost component definitions that no longer match the actual codebase. The spec must be updated to reflect obvious implementation improvements while remaining the source of truth for all planned-but-unbuilt modules.

**Guiding principle:** The spec takes precedence. Only update the spec where the implementation made an obvious, justified name change or structural improvement. Where the implementation diverged substantively from the spec's intent, document the gap for future resolution — do not retroactively bless all implementation choices.

## Business Requirements

1. **Audit every type, interface, enum, and table name** referenced in `solo-operator-spec.md` against the actual codebase
2. **Update the spec** to reflect implementation reality where changes are obvious improvements (e.g., better naming, structural refinements)
3. **Flag divergences** where the spec's original intent differs from implementation and a design decision is needed
4. **Preserve the spec's authority** over unimplemented modules (pricing, vendor, fulfillment, capital, compliance, portfolio) — those sections remain as-is unless an already-implemented shared type affects them
5. **Update code examples** in the spec to match actual Kotlin signatures, package paths, and module locations

## Success Criteria

- [ ] Every class/type in the spec's code blocks matches actual implementation names (for implemented modules)
- [ ] `TerminationReason` enum values in the spec reflect the implemented values
- [ ] `CostEnvelope.Verified` fields in the spec match the implemented 13-component structure
- [ ] `CarrierRateProvider` interface signature in the spec matches the actual interface
- [ ] Database table names in Section 6.2 match actual Flyway migration table names
- [ ] `Money` and `Percentage` code examples reflect actual implementation signatures
- [ ] Adapter interface patterns in Section 4.2 are updated to reflect what was actually built
- [ ] Module paths reference `modules/` directory structure (from FR-013 refactor)
- [ ] All divergences that require a design decision are documented in a "Divergence Log" within the spec or as a separate section
- [ ] No changes made to sections describing unimplemented modules unless directly affected by shared types

## Non-Functional Requirements

- The spec remains a single self-contained markdown document
- Changes are limited to sections describing already-implemented code — future module sections are not rewritten
- The document version is bumped (e.g., from "Draft v1.0" to "Draft v1.1 — Post-Implementation Audit")
- A changelog section is added at the bottom of the spec summarizing what was updated and why

## Dependencies

- FR-001 (shared domain primitives) — implemented
- FR-002 (project bootstrap) — implemented
- FR-003 (catalog SKU lifecycle) — implemented
- FR-004 (catalog cost gate) — implemented
- FR-005 (catalog stress test) — implemented
- FR-013 (project structure refactor) — implemented

### Known Divergences to Resolve

| # | Spec Section | Spec Says | Implementation Has | Recommendation |
|---|---|---|---|---|
| 1 | 3.1 CostEnvelope | 14 components, some as `Percentage` | 13 components, all as `Money` | Update spec — all-Money is cleaner; note component mapping |
| 2 | 3.1 CostEnvelope | `unitCost`, `freightCost`, `lastMileCost`, `dimWeightSurcharge` | `supplierUnitCost`, `inboundShipping`, `outboundShipping` (no dimWeight) | Update spec — split shipping is more precise |
| 3 | 3.1 CostEnvelope | `currencyBuffer: Percentage` | Not present | Flag — decide if currencyBuffer is still needed for international expansion |
| 4 | 3.1 CostEnvelope | `reverseLogistics`, `supportAllocation` | `returnHandlingCost`, `customerServiceCost` | Update spec — implementation names are clearer |
| 5 | 3.1 CostEnvelope | `modeledCac` | `customerAcquisitionCost` | Update spec — more explicit |
| 6 | 3.1 CostEnvelope | Missing | `warehousingCost` | Update spec — implementation added a valid cost component |
| 7 | 3.2 StressTestResult | Not specified as separate type | `StressTestResult` data class + `StressTestResultEntity` | Update spec — audit trail is valuable |
| 8 | 3.3 TerminationReason | 7 values: `MARGIN_BELOW_THRESHOLD`, `COST_EXCEEDS_PRICE_CEILING`, `HIGH_REFUND_RATE`, `HIGH_CHARGEBACK_RATE`, `VENDOR_SLA_BREACH`, `CAC_INSTABILITY`, `DYNAMIC_PRICING_INEFFECTIVE` | 7 values: `STRESS_TEST_FAILED`, `MARGIN_BELOW_FLOOR`, `REFUND_RATE_EXCEEDED`, `CHARGEBACK_RATE_EXCEEDED`, `VENDOR_SLA_BREACH`, `COMPLIANCE_VIOLATION`, `MANUAL_OVERRIDE` | Update spec — implementation values are more actionable; `STRESS_TEST_FAILED` and `COMPLIANCE_VIOLATION` are important additions |
| 9 | 4.2 CarrierRateProvider | `getRates(ShipmentSpec): List<CarrierRate>` + `getTrackingStatus()` | `getRate(origin, dest, dims): Money` + `carrierName: String` | Update spec — simpler interface; tracking belongs in fulfillment module |
| 10 | 4.2 PlatformAdapter | Full interface: `listSku`, `pauseSku`, `updatePrice`, `getFees` | Only `ShopifyPlatformFeeProvider` (concrete, fees only) | Keep spec — full interface is the target; note partial implementation |
| 11 | 4.2 SupplierAdapter | Full interface: `getProductCost`, `placeOrder`, `getOrderStatus` | Not implemented | Keep spec as-is |
| 12 | 6.2 Schema | `cost_envelopes` | `sku_cost_envelopes` | Update spec — prefixed name is clearer |
| 13 | 6.2 Schema | Not specified | `sku_state_history`, `sku_stress_test_results` | Update spec — add these tables |
| 14 | 2.3 Module paths | Root-level implied | `modules/` directory structure | Update spec — reflect FR-013 refactor |
| 15 | 3.4 Money | Shows raw constructor | Implementation uses scale=4, `CurrencyMismatchException` | Update spec — more precise |
