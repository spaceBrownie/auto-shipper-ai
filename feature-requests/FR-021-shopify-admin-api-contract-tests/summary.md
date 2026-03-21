# FR-021: Shopify Admin API Contract Tests — Summary

## Feature Summary

WireMock contract tests for all three Shopify Admin REST API adapters (`ShopifyListingAdapter`, `ShopifyPlatformFeeProvider`, `ShopifyPriceSyncAdapter`), following the pattern established by FR-017. Fixtures are sourced from official Shopify API documentation, not reverse-engineered from adapter code (PM-014 lesson). API doc research surfaced three production bugs that were fixed in this PR.

## Bugs Fixed

### 1. `plan_name` mapping (HIGH — incorrect platform fees)
`ShopifyPlatformFeeProvider.PLAN_FEE_RATES` used incorrect keys: `"shopify"`, `"advanced"`, `"plus"`. The real Shopify REST API returns `"professional"`, `"unlimited"`, `"shopify_plus"`. Only `"basic"` was correct. All non-Basic stores would fall through to the 2% default rate, overcharging platform fees in the cost envelope.

**Fix:** Corrected map keys to match real Shopify API `plan_name` values.

### 2. CLAUDE.md #13 violations (MEDIUM — startup crashes)
- `ShopifyPlatformFeeProvider`: `@Value("${shopify.api.access-token}")` missing `:}` empty default
- `InventoryCheckAdapter` (fulfillment): Same `@Value` violation on both `shopify.api.access-token` and `shopify.api.base-url`

**Fix:** Added `:}` empty defaults to all three `@Value` annotations. Added blank-token guard in `ShopifyPlatformFeeProvider.getFee()`.

### 3. `ShopifyPriceSyncAdapter` fallback path (MEDIUM — missing auth)
The backward-compatibility fallback PUT to Shopify did not send the `X-Shopify-Access-Token` header, so it would always receive a 401.

**Fix:** Added `accessToken` constructor parameter and `.header("X-Shopify-Access-Token", accessToken)` to the fallback PUT chain.

## Changes Made

### Config
- Added WireMock dependency (`testImplementation`) to catalog and pricing `build.gradle.kts`

### Adapter Bug Fixes
- `ShopifyPlatformFeeProvider` — corrected `PLAN_FEE_RATES` keys, fixed `@Value` annotation, added blank-token guard
- `ShopifyPriceSyncAdapter` — added `accessToken` parameter and auth header to fallback path
- `InventoryCheckAdapter` — fixed two `@Value` annotations (CLAUDE.md #13 audit)

### Test Fixtures (13 files)
- 10 catalog fixtures under `modules/catalog/src/test/resources/wiremock/shopify/`
- 3 pricing fixtures under `modules/pricing/src/test/resources/wiremock/shopify/`
- All sourced from official Shopify Admin REST API documentation

### Test Classes (3 classes, 21 new tests)
- `ShopifyListingAdapterWireMockTest` — 8 tests (product CRUD, error cases, auth verification)
- `ShopifyPlatformFeeProviderWireMockTest` — 8 tests (all plan names, fallback, errors, auth)
- `ShopifyPriceSyncAdapterWireMockTest` — 5 tests (delegation path, fallback path, errors)

### Existing Test Updates
- `ShopifyPriceSyncAdapterTest` — updated constructor call and Mockito stubs for new `accessToken` parameter

## Files Modified

### Production Code
| File | Change |
|---|---|
| `modules/catalog/build.gradle.kts` | Added WireMock testImplementation dependency |
| `modules/pricing/build.gradle.kts` | Added WireMock testImplementation dependency |
| `modules/catalog/src/main/kotlin/.../ShopifyPlatformFeeProvider.kt` | Fixed plan_name keys, @Value default, blank-token guard |
| `modules/pricing/src/main/kotlin/.../ShopifyPriceSyncAdapter.kt` | Added accessToken param, auth header on fallback path |
| `modules/fulfillment/src/main/kotlin/.../InventoryCheckAdapter.kt` | Fixed @Value empty defaults |

### Test Code
| File | Change |
|---|---|
| `modules/catalog/src/test/kotlin/.../ShopifyListingAdapterWireMockTest.kt` | New — 8 WireMock contract tests |
| `modules/catalog/src/test/kotlin/.../ShopifyPlatformFeeProviderWireMockTest.kt` | New — 8 WireMock contract tests |
| `modules/pricing/src/test/kotlin/.../ShopifyPriceSyncAdapterWireMockTest.kt` | New — 5 WireMock contract tests |
| `modules/pricing/src/test/kotlin/.../ShopifyPriceSyncAdapterTest.kt` | Updated for new constructor parameter |

### Test Fixtures (New)
| File | Content |
|---|---|
| `modules/catalog/src/test/resources/wiremock/shopify/product-create-201.json` | POST /products.json success |
| `modules/catalog/src/test/resources/wiremock/shopify/product-update-200.json` | PUT /products/{id}.json success |
| `modules/catalog/src/test/resources/wiremock/shopify/variant-update-200.json` | PUT /variants/{id}.json success |
| `modules/catalog/src/test/resources/wiremock/shopify/shop-basic-200.json` | plan_name: "basic" |
| `modules/catalog/src/test/resources/wiremock/shopify/shop-professional-200.json` | plan_name: "professional" |
| `modules/catalog/src/test/resources/wiremock/shopify/shop-unlimited-200.json` | plan_name: "unlimited" |
| `modules/catalog/src/test/resources/wiremock/shopify/shop-shopify-plus-200.json` | plan_name: "shopify_plus" |
| `modules/catalog/src/test/resources/wiremock/shopify/error-401.json` | 401 Unauthorized |
| `modules/catalog/src/test/resources/wiremock/shopify/error-422.json` | 422 Unprocessable Entity |
| `modules/catalog/src/test/resources/wiremock/shopify/error-429.json` | 429 Rate Limited |
| `modules/pricing/src/test/resources/wiremock/shopify/variant-update-200.json` | PUT /variants/{id}.json success |
| `modules/pricing/src/test/resources/wiremock/shopify/error-401.json` | 401 Unauthorized |
| `modules/pricing/src/test/resources/wiremock/shopify/error-404.json` | 404 Not Found |

## Testing Completed

- `./gradlew test` — BUILD SUCCESSFUL, all tests pass across all modules
- 21 new WireMock contract tests + all existing tests pass
- No network access or Shopify API keys required
- Request body and header assertions verify exact API contract compliance

## Deployment Notes

- No database migrations required
- No new configuration properties required (all `@Value` annotations use empty defaults)
- Bug fixes are backward-compatible — corrected plan_name keys will produce correct fee rates for Shopify/Advanced/Plus stores that were previously falling through to the default rate
