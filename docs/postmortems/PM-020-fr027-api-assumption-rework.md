# PM-020: FR-027 — API Assumptions Caused Full Phase 2-4 Rework

**Date:** 2026-04-10
**Severity:** Medium
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

FR-027 (CJ US Warehouse Filtering) completed Phases 1-4 of the feature-request-v2 workflow based on fabricated CJ API response structures — a `warehouses` array on products and `warehouseCode`/`areaId` parameters on order creation that don't exist. After the user flagged the discrepancy and provided the real CJ API docs URL, Phases 2-4 were re-run from scratch with corrected assumptions. The rework also surfaced a pre-existing bug (`logisticName` required field never sent) and eliminated unnecessary complexity (geographic routing deferred). The feature shipped cleanly on the second pass — PR #47 approved without corrections.

## Timeline

| Time | Event |
|------|-------|
| Session start | RAT-45 pulled from Linear. Graphify filemap generated. Phase 1 subagent launched. |
| Phase 1 complete | Discovery reported CJ integration architecture. Identified `fromCountryCode` hardcoded to `"CN"`, bare `ObjectMapper()`, no warehouse metadata. |
| Phase 2 (first pass) | Spec written assuming CJ `listV2` returns a `warehouses` array per product with `warehouseId`, `countryCode` fields. |
| Phase 3 (first pass) | Implementation plan designed around parsing `warehouses` array, geographic routing (Chino vs NJ via state-to-region map), `warehouseCodes: List<String>` on `SupplierOrderRequest`. 22 tasks across 6 tiers. |
| Phase 4 (first pass) | Test spec written with 19 boundary cases for the `warehouses` array fields. |
| **User flags problem** | User reports: "I do not see the warehouse array you mentioned in the phase 3 doc. Here is the doc." Provides `developers.cjdropshipping.cn/en/api/api2/api/product.html`. |
| API docs fetched | WebFetch of CJ Product API: no `warehouses` array. Response has flat `warehouseInventoryNum` integer. `countryCode=US` param already filters US warehouse products. `verifiedWarehouse` param exists. |
| Order API docs fetched | WebFetch of CJ Shopping API: `fromCountryCode` (required), `logisticName` (required — **never sent by our adapter**), `shopLogisticsType`, `storageId`. No `warehouseCode` or `areaId` on orders. |
| **Root cause clear** | Phase 1 subagent fabricated the `warehouses` array structure. All downstream phases built on this fabrication. Two pre-existing bugs surfaced: missing `logisticName`, bare `ObjectMapper()`. |
| Phases 2-4 re-run | Spec, plan, and test-spec rewritten against verified CJ API docs. Scope simplified: no geographic routing, no per-product stock API calls, `shopLogisticsType=2` (CJ handles routing). |
| Filemap + reconciliation | User flagged that graphify filemap was never passed to revised subagents. Test-spec mixed inventory fixture reconciled to include all 7 boundary products (was 5). |
| Phase 5 | 4-round execution: Round 0 (orchestrator: migration, fixtures, config), Round 1 (data model), Round 2 (2 parallel agents: order routing + discovery filtering), Round 3 (2 parallel test agents). `./gradlew test` green. |
| Phase 6 | Branch `feat/RAT-45-cj-us-warehouse-filtering`, PR #47 created, graphify rebuilt. PR approved without corrections. |

## Symptom

No runtime error — the symptom was a spec/plan/test-spec built on a non-existent API response structure. The Phase 3 implementation plan contained code parsing a `warehouses` array:

```kotlin
private fun extractUsWarehouses(product: JsonNode): List<WarehouseInfo> {
    val warehouses = product.get("warehouses") ?: return emptyList()
    // ... parse warehouseId, countryCode from each array element
}
```

This code would have compiled and run, but silently returned empty lists for every product — `get("warehouses")` would return `null` because the field doesn't exist. The adapter would have discovered zero products, appearing as an empty CJ catalog rather than a code bug.

## Root Cause

**5 Whys:**

1. **Why** was the implementation plan built on a non-existent `warehouses` array? → The Phase 1 Discovery subagent described the CJ API response as containing a `warehouses` array with `warehouseId` and `countryCode` fields per product.

2. **Why** did the Phase 1 subagent report an incorrect API structure? → The subagent was instructed to "understand how CJ product search currently works" by reading the adapter code (`CjDropshippingAdapter.kt`). The adapter code doesn't contain any warehouse-related response parsing (it was the feature being added). The subagent inferred/fabricated the API response structure rather than stating "unknown — needs verification."

3. **Why** didn't Phases 2-3 catch the fabrication? → The subagents for Phases 2-3 received the Phase 1 findings as input context and treated them as verified facts. The spec and plan were internally consistent with the fabricated structure, so no internal contradiction flagged it.

4. **Why** wasn't the real API documentation consulted during Phase 1? → The Phase 1 subagent prompt did not include a directive to fetch or verify external API documentation. The `/unblock` integration was available but not used for API contract verification. The subagent relied on code reading + inference instead of authoritative docs.

5. **Why** is there no structural gate preventing fabricated API contracts from reaching implementation? → The feature-request-v2 workflow has no "verify external API contracts against documentation" checkpoint. WireMock fixture accuracy is a postmortem lesson (PM-014) but is tested at implementation time, not at spec time.

**Contributing factor — graphify filemap underutilization:** The filemap was generated before Phase 1 but the revised Phase 2-4 subagents (after the API correction) didn't receive it in their prompts. The orchestrator forgot to include the `## File Map` section when re-running phases. This didn't cause the API fabrication but meant the revised subagents did redundant file discovery.

**Contributing factor — `logisticName` gap:** The CJ `createOrderV2` API requires `logisticName` (logistics carrier name), but `CjSupplierOrderAdapter` never sent it. This pre-existing bug was invisible until the API docs were read end-to-end. It would have caused order failures or silent carrier defaulting in production. The first-pass spec missed it entirely because the fabricated API model didn't include `logisticName` scrutiny.

## Fix Applied

Phases 2-4 were re-executed from scratch with verified CJ API documentation as the source of truth:

1. **Spec (Phase 2):** Rewrote all 6 BRs to use actual CJ API params (`verifiedWarehouse=1`, `warehouseInventoryNum` integer, `fromCountryCode`, `logisticName`). Added `logisticName` as BR-4. Removed geographic routing. Added explicit "Out of Scope" section.

2. **Plan (Phase 3):** Reduced from 22 tasks/6 tiers to 23 tasks/5 tiers (net similar count but dramatically simpler — no `warehouses` array parsing, no `WarehouseSelection` data class, no `WEST_REGION_STATES` map, no `warehouseCodes: List<String>`). Added `logisticName` config property. Changed `warehouseCodes: List<String>` to `warehouseCountryCode: String?`.

3. **Test spec (Phase 4):** Rewrote all fixtures to use `warehouseInventoryNum` integer field instead of `warehouses` array. Reconciled mixed inventory fixture to 7 products covering all boundary cases (zero, null, absent, negative, string-type).

4. **Implementation (Phase 5):** Executed against corrected plan. All tests green. PR #47 approved without corrections.

### Files Changed
- `feature-requests/FR-027-cj-us-warehouse-filtering/spec.md` — Full rewrite against real CJ API
- `feature-requests/FR-027-cj-us-warehouse-filtering/implementation-plan.md` — Full rewrite, simplified architecture
- `feature-requests/FR-027-cj-us-warehouse-filtering/test-spec.md` — Full rewrite, corrected fixtures and boundary cases
- All implementation files per FR-027 summary.md

## Impact

**No production impact** — the fabrication was caught before implementation. If it had reached Phase 5 without correction:
- `CjDropshippingAdapter` would have parsed a non-existent `warehouses` array, silently returning zero candidates for every category
- `DemandScanJob` would have logged `"CJ Dropshipping returned 0 candidates"` — appearing as an empty CJ catalog, not a code bug
- Debugging would have required comparing WireMock fixtures against real API responses to identify the structural mismatch
- The `logisticName` gap would have remained, causing order placement failures or silent carrier defaulting

**Session cost:** ~2 hours of subagent compute wasted on the first pass of Phases 2-4. The rework added ~45 minutes to the session. Total session was still within normal bounds for a feature of this scope.

### Post-fix: Live API verification revealed deeper rot

After fixing the adapter and fixtures for the `listV2` endpoint, a live API call against the real CJ `listV2` endpoint revealed that **the pre-existing adapter code (before FR-027) was also wrong**. The original `product-list-success.json` fixture and `CjDropshippingAdapter` used response structures that never matched the real API:

| Field | Code assumed | Real API |
|-------|-------------|----------|
| Response path | `data.list[]` | `data.content[].productList[]` |
| Product ID | `pid` | `id` |
| Product name | `productNameEn` | `nameEn` |
| Price | `sellPrice` (number) | `sellPrice` (string range `"0.58 -- 93.24"`) |
| Image | `productImage` | `bigImage` |
| Category | `categoryName` | `threeCategoryName` |
| Pagination | `pageNum`/`total` | `pageNumber`/`totalRecords` |

This means the adapter would have returned **zero candidates** against the live API — `data.list` would be null, silently producing an empty result. The fixture-based WireMock tests all passed because the fixtures matched the (wrong) code, not the real API.

**Verified OpenAPI YAML spec created:** `docs/api/cj_product_api.yaml` — 15/16 Product API endpoints verified against the live API with `x-cj-verified: true`. This is now the authoritative source of truth.

**Scope of remaining rot:** 6 more CJ production classes, 7 WireMock fixtures, and 6 test classes have not been audited against real API contracts. Tracked in **RAT-47** (CJ API contract reconciliation).

## Lessons Learned

### What went well
- **User caught the fabrication early** — before Phase 5 implementation, avoiding throwaway code
- **WebFetch of real API docs** was decisive — one fetch of the CJ product API and one of the shopping API provided complete, authoritative field-level documentation
- **Revised workflow was efficient** — Phases 2-4 re-ran cleanly using the corrected API knowledge. The second pass produced a cleaner, simpler design (no geographic routing, no per-product stock API calls)
- **Pre-existing bugs surfaced** — the `logisticName` gap and bare `ObjectMapper()` violation were found because the full API docs were read end-to-end, not just the fields relevant to the feature
- **Graphify filemap** provided instant file discovery for 20 classes across 17 files (~555 tokens). When it was included in subagent prompts, agents went straight to reading files instead of searching
- **Phase 5 execution was clean** — 4 rounds, 2 parallel agent pairs, all tests green on first run. The corrected plan produced zero implementation rework

### What could be improved
- **No API doc verification gate in the workflow** — the feature-request-v2 workflow assumes codebase exploration is sufficient for understanding external API contracts. It isn't. Subagents confidently fabricate API structures when the code doesn't contain the answer.
- **Graphify filemap not consistently passed to subagents** — the orchestrator generated it but forgot to include it in the revised Phase 2-4 subagent prompts. The workflow doc says "each subsequent subagent gets the latest filemap in its prompt as a `## File Map` section" but enforcement is manual.
- **Phase 1 Discovery is too trusting** — the subagent's findings flowed uncritically into Phases 2-4. There's no "confidence level" or "needs verification" marker on claims about external systems.
- **First-pass Phase 1 didn't use /unblock** — the unblocked context engine could have surfaced CJ API documentation links from prior PRs or Slack discussions, providing a cross-check on the fabricated structure.

## Prevention

### Structural changes to feature-request-v2 workflow

- [ ] **Add "External API Verification" gate to Phase 1 instructions** — When a feature modifies an external API adapter, Phase 1 must include a step: "Fetch and read the external API documentation. Do NOT infer API response structures from adapter code alone." Include the WebFetch tool call in the Phase 1 instruction template. This makes API doc verification a workflow requirement, not an ad-hoc check.

- [ ] **Add `api-contracts` section to spec.md template** — Phase 2 spec should have an "External API Contracts" section that documents the verified request/response shapes with a source URL. This creates a traceable link from the spec to the authoritative docs and makes fabrication visible (the section would be empty or cite "inferred from code" rather than a URL).

- [ ] **Enforce filemap inclusion in subagent prompts** — Add a validation step to `validate-phase.py` that checks whether `filemap.txt` exists for the feature directory. If it exists, the orchestrator instructions should mandate including it. Consider generating the filemap section as a string that can be copy-pasted into prompts.

- [ ] **Add `logisticName` to CjSupplierOrderAdapter config** — Determine the correct CJ logistics name for US domestic shipping by querying the CJ Freight Calculation API or CJ support. Set `CJ_DEFAULT_LOGISTIC_NAME` environment variable before production deployment. (This is a deployment action, not a code prevention item.)

### Live API verification (completed during this session)

- [x] **Verified CJ Product API spec** — Created `docs/api/cj_product_api.yaml` with 15/16 endpoints tested against live API calls. Each verified path marked with `x-cj-verified: true`. Response schemas document real field names, types, and envelope shapes.

- [x] **Fixed CjDropshippingAdapter + all 6 fixtures** — Corrected `data.list[]` → `data.content[].productList[]`, `pid` → `id`, `productNameEn` → `nameEn`, `sellPrice` number → string range parse, `productImage` → `bigImage`. All tests updated and green.

### Remaining reconciliation (tracked in RAT-47)

- [x] **Audit all remaining CJ adapters against verified YAML** — 6 production classes (CjSupplierOrderAdapter, CjTrackingProcessingService, CjTrackingWebhookController, CjWebhookTokenVerificationFilter, CjCarrierMapper, DemandScanJob), 7 WireMock fixtures (order placement + error envelopes), 6 test classes. Completed in FR-028/RAT-47 (PR #48). Fixed shippingAddress2, fabricated error codes, missing `success` field, and `orderNum` -> `orderNumber`.

- [x] **Create `docs/api/cj_shopping_api.yaml`** — Same live-verification process for the Shopping/Order API. Required for auditing `CjSupplierOrderAdapter` and order placement fixtures. Completed in FR-028/RAT-47 (PR #48). Covers 16 Shopping API endpoints + 7 webhook payload schemas with `x-cj-verified` markers.

### CLAUDE.md constraint candidate

- [ ] **Evaluate CLAUDE.md constraint #21 candidate**: "All WireMock fixtures for external API adapters must cite the API documentation URL in a comment header. Fixtures without a documentation source are assumed fabricated and must not be used as the basis for adapter design." — This would structurally prevent PM-014-style and PM-020-style fabrication by requiring traceability.
