# FR-014: Spec Architecture Audit — Implementation Plan

## Technical Design

This is a **documentation-only** change. The single deliverable is an updated `docs/plans/solo-operator-spec.md` that reflects the implemented codebase while preserving its authority as the design standard.

No code changes. No migrations. No tests affected.

### Approach

Edit `solo-operator-spec.md` section-by-section, applying the 15 divergences from the spec. Each change falls into one of three categories:

1. **Update** — Implementation name is obviously better; update the spec
2. **Keep** — Spec's design is the target; leave as-is (add implementation status note if helpful)
3. **Flag** — Design decision needed; add a note in the Open Decisions section

## Architecture Decisions

- **All cost components become `Money`** — The spec mixed `Money` and `Percentage` types in `CostEnvelope.Verified`. The implementation correctly uses all `Money` fields (fees are pre-computed into absolute amounts by the adapters before constructing the envelope). This is cleaner and more composable — the `fullyBurdened` sum just adds all fields.
- **`currencyBuffer` moves to Open Decisions** — Not implemented, but relevant for Phase 2+ international expansion. Rather than delete it, flag it as a future decision.
- **`CarrierRateProvider` simplified** — Tracking belongs in the `fulfillment` module, not the carrier rate interface. The implementation's simpler `getRate()` returning `Money` is correct for the cost gate's needs.
- **Schema table names get `sku_` prefix** — Implementation used `sku_cost_envelopes` and `sku_stress_test_results`. More explicit; update spec.
- **`Verified` uses `internal constructor` + factory** — The implementation enforces construction only via `CostGateService`. The spec showed a `data class` with public constructor. Update spec to show the `class` + `companion object create()` pattern.

## Layer-by-Layer Implementation

### Documentation Layer (sole layer)

File: `docs/plans/solo-operator-spec.md`

**Section 2.3 — Application Modules:**
- Update module paths to reference `modules/` directory (FR-013)

**Section 3.1 — CostEnvelope:**
- Replace 14-field mixed-type definition with 13-field all-`Money` definition
- Update field names: `unitCost` → `supplierUnitCost`, `freightCost`/`lastMileCost` → `inboundShipping`/`outboundShipping`, `modeledCac` → `customerAcquisitionCost`, `reverseLogistics` → `returnHandlingCost`, `supportAllocation` → `customerServiceCost`, `refundReserve` → `refundAllowance`, `chargebackReserve` → `chargebackAllowance`, `taxHandling` → `taxesAndDuties`
- Remove `dimWeightSurcharge` and `currencyBuffer` from the Verified fields
- Add `warehousingCost`
- Update `fullyBurdened` comment from "14 components" to "13 components"
- Show `internal constructor` + `companion object create()` pattern
- Add currency homogeneity `init` block

**Section 3.2 — Stress Test Gate:**
- No changes needed — `LaunchReadySku` and `StressTestedMargin` match implementation

**Section 3.3 — SKU State Machine:**
- Update `TerminationReason` enum values to match implementation: `STRESS_TEST_FAILED`, `MARGIN_BELOW_FLOOR`, `REFUND_RATE_EXCEEDED`, `CHARGEBACK_RATE_EXCEEDED`, `VENDOR_SLA_BREACH`, `COMPLIANCE_VIOLATION`, `MANUAL_OVERRIDE`

**Section 3.4 — Money Value Type:**
- Update `Money` code block to show `normalizedAmount`, `CurrencyMismatchException`, scale-4 enforcement, `Money.of()` factory
- Update `Percentage` to show 0–100 range validation, `toDecimalFraction()`, `Percentage.of()` factories

**Section 4.2 — Adapter Interface Pattern:**
- Update `CarrierRateProvider` to match actual interface: `carrierName: String`, `getRate(origin: Address, destination: Address, dims: PackageDimensions): Money`
- Keep `PlatformAdapter` and `SupplierAdapter` as-is (target design for unbuilt modules)
- Add a note that `CarrierRateProvider` currently lives in `catalog` module and will move to `fulfillment` when that module is built

**Section 5.1 — Pre-Launch Requirements:**
- Update "All 14 cost components" to "All 13 cost components"

**Section 6.2 — Core Schema Entities:**
- Rename `cost_envelopes` → `sku_cost_envelopes`
- Rename `stress_test_results` → `sku_stress_test_results`
- Add `sku_state_history` table (implemented in FR-003)
- Update `cost_envelopes` key fields to "sku_id, all 13 cost components, verified_at"
- Update Flyway path to `modules/app/src/main/resources/db/migration/`

**Section 10 — Open Decisions:**
- Add `currencyBuffer` as an open decision: "Currency buffer component in CostEnvelope — needed for international expansion (Phase 2+) but not in current 13-component structure"

**Document metadata:**
- Update status from "Draft v1.0" to "Draft v1.1 — Post-Implementation Audit"
- Add changelog section at bottom

## Task Breakdown

### Documentation Layer
- [x] Update document metadata (version, status)
- [x] Update Section 2.3 module paths to `modules/` structure
- [x] Update Section 3.1 CostEnvelope code block (13 all-Money fields, internal constructor, init block)
- [x] Update Section 3.3 TerminationReason enum values
- [x] Update Section 3.4 Money code block (normalizedAmount, scale-4, CurrencyMismatchException, factories)
- [x] Update Section 3.4 Percentage code block (0-100 validation, toDecimalFraction, factories)
- [x] Update Section 4.2 CarrierRateProvider interface signature
- [x] Update Section 5.1 cost component count (14 → 13)
- [x] Update Section 6.2 table names and add sku_state_history
- [x] Update Section 6.2 Flyway migration path
- [x] Add currencyBuffer to Section 10 Open Decisions
- [x] Add changelog section at bottom of document

## Testing Strategy

No code changes — no tests needed. Validation is manual review of the updated spec against the codebase.

## Rollout Plan

1. Edit `docs/plans/solo-operator-spec.md` in place
2. Commit on current branch
3. No deployment impact — documentation only
