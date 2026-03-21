# FR-021: Shopify Admin API Contract Tests

## Problem Statement

The system has three Shopify Admin REST API adapters across two modules: `ShopifyListingAdapter` (catalog, FR-020) for product creation/lifecycle, `ShopifyPlatformFeeProvider` (catalog, FR-004) for plan-based fee lookups, and `ShopifyPriceSyncAdapter` (pricing, FR-006) for variant price syncing. None of these adapters have integration tests that exercise their HTTP request construction and response parsing against realistic Shopify API response shapes.

The existing Mockito-based unit tests mock the `RestClient` entirely, which means they validate adapter logic in isolation but never verify that the request bodies, headers, and URI patterns match what the real Shopify Admin API expects, or that response parsing handles the actual structure Shopify returns. This is exactly the class of bug that PM-014 identified: tests that validate adapter code against itself (circular validation) rather than against the real API contract.

Discovery research against the official Shopify Admin API documentation revealed three bugs in the current adapters that existing tests cannot catch:

1. **plan_name mapping bug (HIGH)**: `ShopifyPlatformFeeProvider.PLAN_FEE_RATES` maps `"shopify"`, `"advanced"`, and `"plus"` as plan name keys. The real Shopify API returns `"professional"` (not `"shopify"`), `"unlimited"` (not `"advanced"`), and `"shopify_plus"` (not `"plus"`). Only `"basic"` is correct. Every non-Basic store falls through to the 2% default rate, causing overcharges in the cost envelope.

2. **CLAUDE.md #13 violation (MEDIUM)**: `ShopifyPlatformFeeProvider` uses `@Value("${shopify.api.access-token}")` without the required `:}` empty default. This crashes bean instantiation in any profile where the property is absent, even though the bean is `@Profile("!local")`-gated -- the same bug class documented in PM-012.

3. **ShopifyPriceSyncAdapter fallback path (MEDIUM)**: The backward-compatibility fallback (for SKUs listed before FR-020) uses `skuId.value` (a UUID) as the Shopify variant ID in the URI. Shopify variant IDs are numeric int64 values, so this always produces a 404. Additionally, the fallback path does not send the `X-Shopify-Access-Token` header, so even if the ID were correct it would receive a 401.

FR-017 (RAT-15) established the WireMock contract test pattern for the demand signal adapters in the portfolio module. The same pattern must now be applied to the Shopify adapters in the catalog and pricing modules. The WireMock dependency currently exists only in the portfolio module and must be added to catalog and pricing.

## Business Requirements

### BR-1: WireMock contract tests for ShopifyListingAdapter

- Tests must exercise all four HTTP operations against a local WireMock server serving recorded Shopify API response fixtures:
  - **Product creation** (POST `/admin/api/2024-01/products.json`): assert the request body contains the correct product structure (`title`, `product_type`, `status`, `variants` array with `price`, `sku`, `inventory_management`), assert the `X-Shopify-Access-Token` header is sent, and assert the response is parsed to extract numeric `product.id` and `product.variants[0].id`.
  - **Pause** (PUT `/admin/api/2024-01/products/{id}.json`): assert the request body sets `product.status` to `"draft"`, assert the auth header is sent.
  - **Archive** (PUT `/admin/api/2024-01/products/{id}.json`): assert the request body sets `product.status` to `"archived"`, assert the auth header is sent.
  - **Price update** (PUT `/admin/api/2024-01/variants/{id}.json`): assert the request body sets `variant.price` as a string value (e.g., `"29.99"`), assert the auth header is sent.
- Error-case tests must cover:
  - 401 Unauthorized (invalid/missing access token) -- assert appropriate exception or error handling.
  - 422 Unprocessable Entity (validation errors, e.g., missing title) -- assert appropriate exception or error handling.
  - 429 Rate Limited -- assert appropriate exception or error handling.
- Fixture files must reflect the real Shopify Admin API response format: numeric int64 IDs, string-formatted prices (e.g., `"199.00"`), nested `product.variants` array, and the full set of fields Shopify returns (not minimal stubs).

### BR-2: WireMock contract tests for ShopifyPlatformFeeProvider

- Tests must exercise the GET `/admin/api/2024-01/shop.json` endpoint against WireMock and assert:
  - The `X-Shopify-Access-Token` header is sent on the request.
  - Response parsing correctly extracts `shop.plan_name` and maps it to the appropriate fee rate.
  - Each real Shopify plan name produces the correct fee: `"basic"` -> 2.0%, `"professional"` -> 1.0%, `"unlimited"` -> 0.5%, `"shopify_plus"` -> 0.0%.
- Tests must expose the plan_name mapping bug: fixture files must use the real Shopify plan names (`"professional"`, `"unlimited"`, `"shopify_plus"`), causing the current `PLAN_FEE_RATES` map to fall through to the default rate. These tests are expected to fail against the current code and pass only after the mapping is corrected.
- Error-case tests must cover:
  - 401 Unauthorized -- assert `ProviderUnavailableException` is thrown (or equivalent error handling).
  - Unknown/unrecognized plan name -- assert graceful fallback to default rate.

### BR-3: WireMock contract tests for ShopifyPriceSyncAdapter

- Tests must exercise the variant price update for both the PlatformAdapter delegation path and the backward-compatibility fallback path.
- **Delegation path**: When a `PlatformListingEntity` exists with a valid `externalVariantId`, assert the adapter delegates to `PlatformAdapter.updatePrice()` and updates the listing's `currentPriceAmount` and `updatedAt`.
- **Fallback path**: When no `PlatformListingEntity` exists, test the direct Shopify PUT against WireMock. The test must verify:
  - The `X-Shopify-Access-Token` header is sent (exposes the missing auth header bug).
  - The variant ID in the URI is a numeric Shopify ID, not a UUID (exposes the UUID-as-variant-ID bug).
- Error-case tests must cover 401 and 404 responses on the fallback path.

### BR-4: Fix bugs discovered during contract test development

- **plan_name mapping**: Correct `ShopifyPlatformFeeProvider.PLAN_FEE_RATES` to use real Shopify plan names: `"professional"` (1.0%), `"unlimited"` (0.5%), `"shopify_plus"` (0.0%). Keep `"basic"` (2.0%) as-is.
- **CLAUDE.md #13 compliance**: Add `:}` empty default to `ShopifyPlatformFeeProvider`'s `@Value("${shopify.api.access-token}")` annotation, and add an early-return guard in `getFee()` for blank access token (log warning + throw `ProviderUnavailableException`).
- **ShopifyPriceSyncAdapter fallback auth**: Add `X-Shopify-Access-Token` header to the fallback PUT request. The fallback path should inject the access token via `@Value("${shopify.api.access-token:}")` with the required empty default.

### BR-5: WireMock test infrastructure for catalog and pricing modules

- Add the WireMock dependency (`testImplementation`) to the catalog and pricing module build files.
- Follow the FR-017 convention: `WireMockExtension` with `dynamicPort()`, fixture files under `src/test/resources/wiremock/shopify/`, and a `loadFixture()` helper function for reading fixture files.
- Fixture loading and WireMock setup should be self-contained within each test class (no shared base class across modules, consistent with bounded-context boundaries).

## Success Criteria

### ShopifyListingAdapter contract tests
- `ShopifyListingAdapterWireMockTest` (or similar) exists in the catalog module with tests for: product creation (POST), pause (PUT with `"draft"`), archive (PUT with `"archived"`), and price update (PUT variant).
- Product creation test asserts: request body structure matches Shopify's expected format, `X-Shopify-Access-Token` header is present, response parsing extracts numeric `productId` and `variantId` from the fixture.
- Error tests assert correct behavior for 401, 422, and 429 responses.

### ShopifyPlatformFeeProvider contract tests
- `ShopifyPlatformFeeProviderWireMockTest` (or similar) exists in the catalog module with tests for each real plan name: `"basic"`, `"professional"`, `"unlimited"`, `"shopify_plus"`.
- Each plan name test verifies the correct fee rate is applied to the estimated order value.
- The `PLAN_FEE_RATES` map in `ShopifyPlatformFeeProvider` uses the corrected plan names (`"professional"`, `"unlimited"`, `"shopify_plus"` instead of `"shopify"`, `"advanced"`, `"plus"`).
- Error test for 401 asserts `ProviderUnavailableException` is thrown.

### ShopifyPriceSyncAdapter contract tests
- `ShopifyPriceSyncAdapterWireMockTest` (or similar) exists in the pricing module with tests for both the delegation path and the fallback path.
- Fallback path test verifies `X-Shopify-Access-Token` header is sent.
- After bug fix, the fallback path is either corrected to use a numeric variant ID or documented/removed as unreachable.

### Bug fixes
- `ShopifyPlatformFeeProvider.PLAN_FEE_RATES` uses corrected keys: `"basic"`, `"professional"`, `"unlimited"`, `"shopify_plus"`.
- `ShopifyPlatformFeeProvider` `@Value` annotation includes `:}` empty default.
- `ShopifyPriceSyncAdapter` fallback path sends `X-Shopify-Access-Token` header.

### Fixture files
- Recorded response fixtures exist under `src/test/resources/wiremock/shopify/` in the catalog and pricing modules.
- Fixtures contain realistic Shopify Admin API response shapes with numeric int64 IDs, string-formatted prices, nested object structures, and representative field sets -- not minimal stubs.
- Zero real credentials, tokens, store names, or PII in any committed fixture file. All sensitive values use synthetic placeholders.

### Infrastructure
- WireMock dependency (`testImplementation`) is present in catalog and pricing module `build.gradle.kts` files.
- All new tests pass in CI (`./gradlew test`) without Shopify API keys or network access.

## Non-Functional Requirements

### NFR-1: Fixture authenticity
- All fixture files must be authored from the official Shopify Admin REST API documentation (https://shopify.dev/docs/api/admin-rest), not reverse-engineered from adapter code. This is the core lesson from PM-014: fixtures that mirror adapter assumptions instead of real API behavior provide zero contract validation.

### NFR-2: WireMock lifecycle
- WireMock servers must start and stop per test class (not per test method) using `WireMockExtension` with `dynamicPort()` to keep test execution fast and avoid port conflicts.
- WireMock dependency must be `testImplementation` only and must not appear on the production classpath.

### NFR-3: Test isolation
- Each test must be independent -- no shared mutable state between tests within a class. WireMock stub mappings should be reset between test methods.
- Tests must not require any Spring context (`@SpringBootTest`). They should instantiate adapters directly with the WireMock server's base URL, following the FR-017 pattern of lightweight integration tests.

### NFR-4: Request body assertions
- Tests must assert on the exact request body structure sent to WireMock, not just the response parsing. This catches request-side bugs (wrong field names, missing required fields, incorrect nesting) that response-only tests miss.

### NFR-5: CLAUDE.md constraint compliance
- All adapter code touched by bug fixes must comply with CLAUDE.md constraints, specifically:
  - Constraint #12: URL-encode user-supplied values in form-encoded request bodies.
  - Constraint #13: `@Value` annotations with `:}` empty defaults.
  - Constraint #14: No `internal constructor` on `@Component` classes.
  - Constraint #15: Use `get()` instead of `path()` when absence should trigger `?:` null-coalescing.

## Dependencies

- **FR-017 (RAT-15)** -- WireMock test infrastructure patterns and conventions. COMPLETE.
- **FR-020 (RAT-13)** -- `ShopifyListingAdapter`, `PlatformAdapter` interface, `PlatformListingEntity`/`PlatformListingRepository`. COMPLETE.
- **FR-004 (catalog-cost-gate)** -- `ShopifyPlatformFeeProvider`, `PlatformFeeProvider` interface. COMPLETE.
- **FR-006 (pricing-engine)** -- `ShopifyPriceSyncAdapter`, `PriceSyncAdapter` interface. COMPLETE.
- **FR-001 (shared-domain-primitives)** -- `Money`, `Currency`, `SkuId` value types used in adapter input/output assertions. COMPLETE.
- **FR-019 (PM-012 prevention constraints)** -- CLAUDE.md constraints #13 and #15 that this feature enforces via tests. COMPLETE.
