# FR-028: CJ API Contract Reconciliation — Summary

**Linear ticket:** RAT-47
**Feature name:** `cj-api-contract-reconciliation`
**Phase:** 5 (Implementation Complete)
**Date:** 2026-04-14

---

## Feature Summary

Audited and reconciled all remaining CJ Dropshipping API adapters, WireMock fixtures, and test
classes against verified API documentation and live API calls. Created `docs/api/cj_shopping_api.yaml`
as the authoritative source of truth for the Shopping/Order API and webhook contracts.

This work directly addresses PM-020 prevention items #145 (audit all remaining CJ adapters) and #147
(create `cj_shopping_api.yaml`).

---

## Key Findings

### What was correct (no changes needed)
- `CjTrackingProcessingService` webhook field parsing: `params.orderId`, `params.trackingNumber`, `params.logisticName` all match CJ LOGISTIC webhook docs
- `CjTrackingWebhookController` webhook structure: same fields, correct
- `CjSupplierOrderAdapter` response parsing: `data.orderId` is correct
- `CjSupplierOrderAdapter` request field names: all match except address handling
- Auth error code `1600001`: already correct in fixtures (live-verified)
- `CjCarrierMapper` carrier name mappings: not enumerable from docs, kept as-is

### What was wrong (fixed)
- **Address field concatenation:** Adapter concatenated `addressLine1 + addressLine2` into single `shippingAddress`. CJ API has separate `shippingAddress` and `shippingAddress2` fields (the concatenation could silently truncate at 200 chars)
- **Fabricated error codes:** `1600501` (out of stock) and `1600502` (invalid address) are NOT in CJ docs and were never observed in live calls. Replaced with verified codes `1603000` and `1600100`
- **Missing `success` field:** All 7 fixtures were missing the `success` boolean field that CJ includes in every response
- **Wrong field name:** `orderNum` in success fixture should be `orderNumber` per createOrderV2 docs (order/list uses `orderNum` — CJ inconsistency documented in YAML)
- **Missing fixture documentation:** No fixtures had API doc source citations (PM-020 prevention item)

### What is unverified (documented)
- `CjWebhookTokenVerificationFilter` Bearer token auth: CJ docs do NOT document any webhook authentication mechanism. Kept as defensive measure, marked `x-cj-verified: unverified`
- Error code `1600200` (rate limit): not in CJ docs, kept as plausible
- `CjCarrierMapper` carrier name strings: CJ docs don't enumerate `logisticName` values

---

## Changes Made

See Files Modified section below for the complete list.

## Files Modified

### New Files
| File | Description |
|------|-------------|
| `docs/api/cj_shopping_api.yaml` | Verified OpenAPI spec for CJ Shopping API (orders, payment, webhooks) with `x-cj-verified` markers |

### Modified Production Code
| File | Change |
|------|--------|
| `CjSupplierOrderAdapter.kt` | Send `addressLine2` as separate `shippingAddress2` field instead of concatenating into `shippingAddress` |
| `CjWebhookTokenVerificationFilter.kt` | Added `x-cj-verified: unverified` KDoc note documenting that Bearer auth is not in CJ webhook docs |

### Modified Fixtures (7 files)
| File | Changes |
|------|---------|
| `create-order-success.json` | `orderNum` -> `orderNumber`, added `success: true`, added realistic response fields, added doc headers |
| `create-order-auth-failure.json` | Added `success: false`, added doc headers |
| `create-order-invalid-address.json` | Code `1600502` -> `1600100`, message updated, added `success: false`, added doc headers |
| `create-order-null-fields.json` | `orderNum` -> `orderNumber`, added `success: true`, added doc headers |
| `create-order-out-of-stock.json` | Code `1600501` -> `1603000`, message updated to match live format, added `success: false`, added doc headers |
| `error-401.json` | Added `success: false`, added doc headers |
| `error-429.json` | Added `success: false`, added doc headers (code `1600200` marked unverified) |

### Modified Tests (1 file)
| File | Changes |
|------|---------|
| `CjSupplierOrderAdapterWireMockTest.kt` | Updated error message assertions for out-of-stock and invalid-address tests; updated address tests to verify separate `shippingAddress`/`shippingAddress2` fields |

### Unchanged (verified correct)
- `CjTrackingProcessingService.kt` — field names match LOGISTIC webhook docs
- `CjTrackingWebhookController.kt` — field names match
- `CjCarrierMapper.kt` — carrier names not enumerable from docs
- `CjTrackingProcessingServiceTest.kt` — all 13 tests use correct webhook structure
- `CjTrackingWebhookControllerTest.kt` — all 8 tests use correct structure
- `CjWebhookTokenVerificationFilterTest.kt` — all 8 tests unchanged
- `CjTrackingWebhookIntegrationTest.kt` — all 3 tests use correct structure
- `CjCarrierMapperTest.kt` — all 14 tests unchanged

---

## Testing Completed

- `./gradlew clean test` — **BUILD SUCCESSFUL** (all modules green)
- All 62 CJ-related tests pass after reconciliation
- No regressions in non-CJ tests

---

## API Verification Method

| Endpoint/Schema | Verification | Date |
|----------------|-------------|------|
| `getAccessToken` | Live API call | 2026-04-14 |
| `createOrderV2` request schema | Docs (shopping.html) | 2026-04-14 |
| `createOrderV2` error response | Live API call (code 1603000) | 2026-04-14 |
| Auth error (code 1600001) | Live API call | 2026-04-14 |
| Param error (code 1600300) | Live API call | 2026-04-14 |
| `order/list` response envelope | Live API call (empty list) | 2026-04-14 |
| LOGISTIC webhook payload | Docs (webhook.html) | 2026-04-14 |
| ORDER webhook payload | Docs (webhook.html) | 2026-04-14 |
| Webhook auth mechanism | NOT documented by CJ | 2026-04-14 |
| `webhook/set` configuration | Docs (webhook.html) | 2026-04-14 |

---

## Deployment Notes

- No database migration needed
- No configuration changes needed
- No new dependencies
- Single PR — all changes are internal contract alignment
- Branch: `fix/RAT-47-cj-api-contract-reconciliation`
