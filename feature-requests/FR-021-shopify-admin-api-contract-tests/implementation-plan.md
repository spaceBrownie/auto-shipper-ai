# FR-021: Shopify Admin API Contract Tests — Implementation Plan

## Technical Design

### Architecture Overview

This feature adds WireMock-based contract tests for three Shopify Admin REST API adapters across two modules, following the pattern established by FR-017 (RAT-15) for demand signal adapters. Each adapter gets a dedicated `*WireMockTest` class that starts a local WireMock server, stubs Shopify API endpoints with fixtures sourced from official Shopify docs, instantiates the adapter with the WireMock base URL, and asserts both request structure (body, headers, URI) and response parsing.

The three adapters under test:
- **`ShopifyListingAdapter`** (catalog) — product creation, pause, archive, price update
- **`ShopifyPlatformFeeProvider`** (catalog) — shop plan lookup and fee calculation
- **`ShopifyPriceSyncAdapter`** (pricing) — variant price sync via delegation or fallback

### Adapter Instantiation Strategy

Each test class instantiates its adapter directly with the WireMock server's base URL — no `@SpringBootTest` context is loaded. This requires constructing the adapter's `RestClient` dependency manually:

- **`ShopifyListingAdapter`**: Accepts a `RestClient` constructor parameter (`shopifyRestClient`). Test creates `RestClient.builder().baseUrl(wireMock.baseUrl()).build()` and passes it along with `"test-access-token"` and a real `ObjectMapper`.
- **`ShopifyPlatformFeeProvider`**: Accepts `RestClient`, `accessToken`, and `estimatedOrderValue`. Test creates a fresh `RestClient` pointing at WireMock.
- **`ShopifyPriceSyncAdapter`**: The delegation path uses mocked `PlatformAdapter` and `PlatformListingRepository`; the fallback path exercises the `shopifyRestClient` against WireMock. Test creates a `RestClient` pointing at WireMock, plus Mockito mocks for the other two dependencies.

### Fixture File Strategy

Fixtures live under `src/test/resources/wiremock/shopify/` in each module (catalog and pricing). All fixture content is sourced from official Shopify Admin REST API documentation, not reverse-engineered from adapter code.

**Catalog module fixtures** (`modules/catalog/src/test/resources/wiremock/shopify/`):
| File | Source | Used By |
|---|---|---|
| `product-create-201.json` | POST /admin/api/2024-01/products.json response | ShopifyListingAdapterWireMockTest |
| `product-update-200.json` | PUT /admin/api/2024-01/products/{id}.json response | ShopifyListingAdapterWireMockTest (pause/archive) |
| `variant-update-200.json` | PUT /admin/api/2024-01/variants/{id}.json response | ShopifyListingAdapterWireMockTest (price update) |
| `shop-basic-200.json` | GET /admin/api/2024-01/shop.json (plan_name: "basic") | ShopifyPlatformFeeProviderWireMockTest |
| `shop-professional-200.json` | GET /admin/api/2024-01/shop.json (plan_name: "professional") | ShopifyPlatformFeeProviderWireMockTest |
| `shop-unlimited-200.json` | GET /admin/api/2024-01/shop.json (plan_name: "unlimited") | ShopifyPlatformFeeProviderWireMockTest |
| `shop-shopify-plus-200.json` | GET /admin/api/2024-01/shop.json (plan_name: "shopify_plus") | ShopifyPlatformFeeProviderWireMockTest |
| `error-401.json` | Shopify 401 Unauthorized response | Both test classes |
| `error-422.json` | Shopify 422 Unprocessable Entity response | ShopifyListingAdapterWireMockTest |
| `error-429.json` | Shopify 429 Rate Limited response | ShopifyListingAdapterWireMockTest |

**Pricing module fixtures** (`modules/pricing/src/test/resources/wiremock/shopify/`):
| File | Source | Used By |
|---|---|---|
| `variant-update-200.json` | PUT /admin/api/2024-01/variants/{id}.json response | ShopifyPriceSyncAdapterWireMockTest |
| `error-401.json` | Shopify 401 Unauthorized response | ShopifyPriceSyncAdapterWireMockTest |
| `error-404.json` | Shopify 404 Not Found response | ShopifyPriceSyncAdapterWireMockTest |

All fixtures use realistic Shopify response shapes: numeric int64 IDs, string-formatted prices (e.g., `"29.99"`), nested `product.variants` arrays, and representative field sets. Zero real credentials, tokens, store names, or PII.

## Architecture Decisions

### Self-contained test classes per module (no shared base across modules)

FR-017's `WireMockAdapterTestBase` in the portfolio module provides `loadFixture()` and `assertValidRawCandidates()` helpers. The Shopify adapters live in catalog and pricing modules, which are separate bounded contexts. Importing the portfolio base class would create a cross-module test dependency that violates bounded-context boundaries.

Instead, each Shopify WireMock test class will include its own `loadFixture()` helper as a private function. This is a small amount of duplication (3 lines) in exchange for zero cross-module coupling. The `assertValidRawCandidates()` helper is portfolio-specific (it asserts `RawCandidate` properties) and not relevant to Shopify adapter assertions.

### No @SpringBootTest

Following the FR-017 pattern, these are lightweight integration tests that exercise HTTP request construction and response parsing without loading Spring context. This keeps test execution fast (no context startup), avoids needing a database or application.yml, and isolates the test to the adapter's HTTP behavior.

Adapters are constructed directly with:
- A `RestClient` pointing at WireMock's dynamic port
- Test values for `@Value`-injected fields (`accessToken`, `estimatedOrderValue`)
- An `ObjectMapper` instance (for `ShopifyListingAdapter`)
- Mockito mocks for non-HTTP dependencies (for `ShopifyPriceSyncAdapter`)

### ShopifyPriceSyncAdapter dual-path testing

`ShopifyPriceSyncAdapter.syncPrice()` has two code paths:
1. **Delegation path**: When a `PlatformListingEntity` exists with a valid `externalVariantId`, delegates to `PlatformAdapter.updatePrice()`. This path does NOT use the `shopifyRestClient` directly — it goes through the injected `PlatformAdapter`. WireMock is not needed here; Mockito verifies the delegation.
2. **Fallback path**: When no `PlatformListingEntity` exists, makes a direct PUT to Shopify via `shopifyRestClient`. This path exercises the HTTP client against WireMock. This is also where the missing auth header bug and UUID-as-variant-ID bug live.

The WireMock test class uses Mockito for `PlatformAdapter` and `PlatformListingRepository`, and WireMock for the `shopifyRestClient`. Tests for the delegation path verify Mockito interactions; tests for the fallback path verify WireMock request patterns.

## Layer-by-Layer Implementation

### Layer 1: Config (build.gradle.kts changes)

Add the WireMock `testImplementation` dependency to both consuming modules. Use the same version as portfolio (`3.4.2`).

### Layer 2: Proxy (bug fixes in adapter code)

Three bugs must be fixed before the WireMock tests can pass:

1. **plan_name mapping** (`ShopifyPlatformFeeProvider`): The `PLAN_FEE_RATES` map uses incorrect keys. Shopify's `GET /admin/api/2024-01/shop.json` returns `plan_name` values of `"basic"`, `"professional"`, `"unlimited"`, and `"shopify_plus"`. The current code uses `"shopify"`, `"advanced"`, and `"plus"` — only `"basic"` is correct.

2. **CLAUDE.md #13 violation** (`ShopifyPlatformFeeProvider`): The `@Value("${shopify.api.access-token}")` annotation is missing the `:}` empty default. This crashes bean instantiation in profiles where the property is absent. Additionally, there is no early-return guard in `getFee()` for a blank access token.

3. **Fallback auth header** (`ShopifyPriceSyncAdapter`): The fallback PUT does not send the `X-Shopify-Access-Token` header. The adapter also needs to accept the access token via `@Value("${shopify.api.access-token:}")` (with the required empty default) for use in the fallback path.

### Layer 3: Test fixtures (JSON files)

All fixture JSON files sourced from official Shopify Admin REST API documentation. Each fixture contains the full representative response shape Shopify returns, not minimal stubs.

### Layer 4: Test classes

Three WireMock test classes:

1. **`ShopifyListingAdapterWireMockTest`** (catalog module): Tests product creation (POST), pause (PUT draft), archive (PUT archived), price update (PUT variant), plus error cases (401, 422, 429).

2. **`ShopifyPlatformFeeProviderWireMockTest`** (catalog module): Tests each plan name mapping (basic, professional, unlimited, shopify_plus), unknown plan fallback, blank token guard, and 401 error.

3. **`ShopifyPriceSyncAdapterWireMockTest`** (pricing module): Tests delegation path (Mockito), fallback path (WireMock with auth header and request body assertions), and error cases (401, 404).

## Task Breakdown

### Config Layer
- [x] Add `testImplementation("org.wiremock:wiremock-standalone:3.4.2")` to `modules/catalog/build.gradle.kts`
- [x] Add `testImplementation("org.wiremock:wiremock-standalone:3.4.2")` to `modules/pricing/build.gradle.kts`

### Proxy Layer (Bug Fixes)
- [x] Fix `PLAN_FEE_RATES` keys in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyPlatformFeeProvider.kt`: change `"shopify"` to `"professional"`, `"advanced"` to `"unlimited"`, `"plus"` to `"shopify_plus"` (verified against Shopify community docs: Basic → `"basic"`, Shopify → `"professional"`, Advanced → `"unlimited"`, Plus → `"shopify_plus"`)
- [x] Fix `@Value` annotation in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyPlatformFeeProvider.kt`: change `@Value("\${shopify.api.access-token}")` to `@Value("\${shopify.api.access-token:}")`
- [x] Add early-return guard in `ShopifyPlatformFeeProvider.getFee()`: if `accessToken.isBlank()`, log warning and throw `IllegalStateException` before making HTTP call (matches `ShopifyListingAdapter` pattern)
- [x] Add `@Value("\${shopify.api.access-token:}") private val accessToken: String` constructor parameter to `modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/ShopifyPriceSyncAdapter.kt`
- [x] Add `X-Shopify-Access-Token` header to the fallback PUT request in `ShopifyPriceSyncAdapter.syncPrice()`
- [x] Update existing `modules/pricing/src/test/kotlin/com/autoshipper/pricing/proxy/ShopifyPriceSyncAdapterTest.kt` — add `accessToken` parameter to constructor call in `setUp()` (existing Mockito tests will break without this)
- [x] Fix `@Value` annotation in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/inventory/InventoryCheckAdapter.kt`: change `@Value("\${shopify.api.access-token}")` to `@Value("\${shopify.api.access-token:}")` (same CLAUDE.md #13 violation, audit all consumers per testing conventions)

### Test Fixtures — Catalog Module
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/product-create-201.json` — full Shopify product creation response with numeric IDs, string prices, nested variants array
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/product-update-200.json` — product update response (for pause/archive assertions)
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/variant-update-200.json` — variant update response with string-formatted price
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/shop-basic-200.json` — shop response with `plan_name: "basic"`
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/shop-professional-200.json` — shop response with `plan_name: "professional"`
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/shop-unlimited-200.json` — shop response with `plan_name: "unlimited"`
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/shop-shopify-plus-200.json` — shop response with `plan_name: "shopify_plus"`
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/error-401.json` — Shopify 401 Unauthorized error response
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/error-422.json` — Shopify 422 Unprocessable Entity error response
- [x] Create `modules/catalog/src/test/resources/wiremock/shopify/error-429.json` — Shopify 429 Rate Limited error response with `Retry-After` header note

### Test Fixtures — Pricing Module
- [x] Create `modules/pricing/src/test/resources/wiremock/shopify/variant-update-200.json` — variant update response (same shape as catalog fixture)
- [x] Create `modules/pricing/src/test/resources/wiremock/shopify/error-401.json` — Shopify 401 Unauthorized error response
- [x] Create `modules/pricing/src/test/resources/wiremock/shopify/error-404.json` — Shopify 404 Not Found error response

### Test Classes — Catalog Module
- [x] Create `modules/catalog/src/test/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyListingAdapterWireMockTest.kt` with:
  - `loadFixture()` private helper
  - `WireMockExtension` with `dynamicPort()` as `@RegisterExtension` in companion object
  - Adapter factory method creating `RestClient.builder().baseUrl(wireMock.baseUrl()).build()`
  - Test: product creation POST — stub 201, assert response parsing extracts numeric `productId` and `variantId`, verify request body contains `product.title`, `product.product_type`, `product.status`, `product.variants[0].price`/`sku`/`inventory_management`, verify `X-Shopify-Access-Token` header
  - Test: pause PUT — stub 200, verify request body contains `product.status = "draft"`, verify auth header
  - Test: archive PUT — stub 200, verify request body contains `product.status = "archived"`, verify auth header
  - Test: price update PUT — stub 200, verify request body contains `variant.price` as string, verify auth header
  - Test: 401 error — stub 401, assert exception thrown
  - Test: 422 error — stub 422, assert exception thrown
  - Test: 429 error — stub 429 with `Retry-After` header, assert exception thrown
  - Test: blank access token — assert `IllegalStateException` thrown without HTTP call
- [x] Create `modules/catalog/src/test/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyPlatformFeeProviderWireMockTest.kt` with:
  - `loadFixture()` private helper
  - `WireMockExtension` with `dynamicPort()` as `@RegisterExtension` in companion object
  - Adapter factory method creating `RestClient` and passing test `accessToken` and `estimatedOrderValue`
  - Test: `"basic"` plan — stub shop response, assert fee = 2.0% of estimated order value
  - Test: `"professional"` plan — stub shop response, assert fee = 1.0% of estimated order value
  - Test: `"unlimited"` plan — stub shop response, assert fee = 0.5% of estimated order value
  - Test: `"shopify_plus"` plan — stub shop response, assert fee = 0.0%
  - Test: unknown plan name — stub shop response with unrecognized plan, assert fallback to 2.0% default
  - Test: 401 error — stub 401, assert `ProviderUnavailableException` thrown
  - Test: blank access token — assert `ProviderUnavailableException` thrown without HTTP call
  - Test: verify `X-Shopify-Access-Token` header is sent on request

### Test Classes — Pricing Module
- [x] Create `modules/pricing/src/test/kotlin/com/autoshipper/pricing/proxy/ShopifyPriceSyncAdapterWireMockTest.kt` with:
  - `loadFixture()` private helper
  - `WireMockExtension` with `dynamicPort()` as `@RegisterExtension` in companion object
  - Adapter factory method: `RestClient` pointing at WireMock, mocked `PlatformAdapter`, mocked `PlatformListingRepository`
  - Test: delegation path — mock `PlatformListingRepository.findBySkuId()` to return entity with `externalVariantId`, verify `PlatformAdapter.updatePrice()` called, verify `shopifyRestClient` NOT used (no WireMock requests)
  - Test: delegation path updates listing entity — verify `currentPriceAmount` and `updatedAt` set, verify `platformListingRepository.save()` called
  - Test: fallback path happy path — mock `findBySkuId()` to return null, stub WireMock 200, verify `X-Shopify-Access-Token` header sent, verify request body contains `variant.price` as string
  - Test: fallback path 401 error — stub 401, assert exception
  - Test: fallback path 404 error — stub 404, assert exception

## Testing Strategy

### What each test class covers

**`ShopifyListingAdapterWireMockTest`**: Validates that the adapter constructs correct HTTP requests for all four Shopify product/variant operations (POST product, PUT product status, PUT variant price) and correctly parses the Shopify response to extract numeric `productId` and `variantId`. Error tests verify the adapter surfaces HTTP errors appropriately rather than silently swallowing them.

**`ShopifyPlatformFeeProviderWireMockTest`**: Validates the end-to-end fee calculation pipeline: HTTP request to `/admin/api/2024-01/shop.json`, response parsing to extract `shop.plan_name`, mapping through `PLAN_FEE_RATES`, and final `Money` calculation. This is the test class that exposes (and then validates the fix for) the plan_name mapping bug. The unknown-plan test ensures graceful degradation.

**`ShopifyPriceSyncAdapterWireMockTest`**: Validates both code paths in the sync adapter. The delegation path tests verify correct interaction with `PlatformAdapter` and `PlatformListingRepository`. The fallback path tests exercise the direct HTTP call and expose (then validate the fix for) the missing auth header bug.

### Request body verification

All test classes use WireMock's `verify()` API with `postRequestedFor()` / `putRequestedFor()` matchers to assert on request structure:

- **Headers**: `withHeader("X-Shopify-Access-Token", equalTo("test-access-token"))` on every request
- **Request body JSON**: `withRequestBody(matchingJsonPath("$.product.title"))`, `withRequestBody(matchingJsonPath("$.product.status", equalTo("draft")))`, `withRequestBody(matchingJsonPath("$.variant.price", equalTo("29.9900")))`, etc.
- **URI patterns**: `urlEqualTo("/admin/api/2024-01/products.json")` for POST, `urlEqualTo("/admin/api/2024-01/products/1072481062.json")` for PUT product, `urlEqualTo("/admin/api/2024-01/variants/808950810.json")` for PUT variant

This catches request-side bugs (wrong field names, missing required fields, incorrect nesting) that response-only tests miss — the core lesson from PM-014.

### Error case testing

Error tests stub WireMock with the appropriate HTTP status code and Shopify error response body:

- **401**: Verifies the adapter throws an appropriate exception (the `RestClient` will throw `HttpClientErrorException.Unauthorized`). For `ShopifyPlatformFeeProvider`, this is wrapped in `ProviderUnavailableException`.
- **422**: Verifies validation errors are surfaced (not silently ignored).
- **429**: Verifies rate limiting is surfaced. The fixture includes `Retry-After` header.
- **404**: For the price sync fallback path, verifies that a missing variant produces an appropriate error.

## Rollout Plan

### Build Verification

1. After adding WireMock dependency to catalog and pricing `build.gradle.kts`:
   ```bash
   ./gradlew :catalog:dependencies --configuration testRuntimeClasspath | grep wiremock
   ./gradlew :pricing:dependencies --configuration testRuntimeClasspath | grep wiremock
   ```
   Verify `org.wiremock:wiremock-standalone:3.4.2` appears in both outputs.

2. After implementing bug fixes (before writing tests):
   ```bash
   ./gradlew build
   ```
   Verify existing tests still pass — the plan_name key change should not break existing Mockito tests (they mock the RestClient and never exercise the mapping logic).

3. After writing all fixtures and test classes:
   ```bash
   ./gradlew test
   ```
   Verify all tests pass, including the new WireMock tests, without network access or Shopify API keys.

4. Targeted test execution:
   ```bash
   ./gradlew :catalog:test --tests "*ShopifyListingAdapterWireMockTest"
   ./gradlew :catalog:test --tests "*ShopifyPlatformFeeProviderWireMockTest"
   ./gradlew :pricing:test --tests "*ShopifyPriceSyncAdapterWireMockTest"
   ```

### Bug Fix Verification

1. **plan_name mapping fix**: The `ShopifyPlatformFeeProviderWireMockTest` tests for `"professional"` (1.0%), `"unlimited"` (0.5%), and `"shopify_plus"` (0.0%) will fail if the old keys are still in place, because the real plan names will fall through to the 2.0% default. These tests passing confirms the fix.

2. **@Value empty default fix**: The `ShopifyPlatformFeeProviderWireMockTest` blank-token test verifies the guard works. The `@Value` annotation change is also verified by running `./gradlew build` in a profile without `shopify.api.access-token` defined — the bean should instantiate successfully.

3. **Fallback auth header fix**: The `ShopifyPriceSyncAdapterWireMockTest` fallback path test uses `wireMock.verify(putRequestedFor(...).withHeader("X-Shopify-Access-Token", equalTo("test-access-token")))` — this will fail if the header is not sent, confirming the fix.
