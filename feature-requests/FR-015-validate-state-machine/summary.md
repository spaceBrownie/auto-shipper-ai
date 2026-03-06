# FR-015: Validate State Machine — Summary

## Feature Summary

Fixed 3 gaps discovered during endpoint testing of the SKU lifecycle state machine: (1) local dev stub providers so cost verification works without external API keys, (2) automatic pricing initialization when a SKU reaches LISTED state, and (3) proper HTTP error codes for domain exceptions.

## Changes Made

### Fix 1: Local Dev Stub Providers

Extracted `ProcessingFeeProvider` and `PlatformFeeProvider` interfaces from the concrete Stripe and Shopify providers. Added `@Profile("!local")` to all 5 live external provider components and `ExternalApiConfig`. Created 3 stub providers under `@Profile("local")`: `StubCarrierRateConfiguration` (FedEx $7.99, UPS $8.49, USPS $5.99), `StubProcessingFeeProvider` (2.9% + $0.30), `StubPlatformFeeProvider` (2.0% of $100). Updated `CostGateService` to depend on interfaces.

Additionally extracted `PriceSyncAdapter` interface in the `:pricing` module from `ShopifyPriceSyncAdapter` (which depends on `shopifyRestClient`), profile-gated the live adapter with `@Profile("!local")`, and created `StubPriceSyncAdapter` for local dev. Updated `PricingDecisionListener` to depend on the interface.

### Fix 2: Wire Initial Pricing on SKU Listing

Created `PricingInitializer` in the `:pricing` module with `@TransactionalEventListener(phase = AFTER_COMMIT)` and `@Transactional(propagation = REQUIRES_NEW)` for `SkuStateChanged`. When `toState == "LISTED"`, reads the latest stress test result and cost envelope, computes margin, and calls `PricingEngine.setInitialPrice()`. Idempotent — skips if price already exists.

The `REQUIRES_NEW` propagation is necessary because the `AFTER_COMMIT` listener runs outside the original transaction boundary, and the pricing persistence requires its own transaction.

### Fix 3: Map Domain Exceptions to HTTP Errors

Created `GlobalExceptionHandler` `@ControllerAdvice` mapping `InvalidSkuTransitionException` to 409 Conflict, `ProviderUnavailableException` to 502 Bad Gateway, and `NoSuchElementException` to 404 Not Found. All return structured JSON with `error` and `message` fields.

## Files Modified

### New Files (11)
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/ProcessingFeeProvider.kt` — interface for payment processing fee providers
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/PlatformFeeProvider.kt` — interface for platform fee providers
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/StubCarrierRateConfiguration.kt` — `@Profile("local")` config registering 3 stub carrier beans
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/StubProcessingFeeProvider.kt` — `@Profile("local")` Stripe fee stub
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/StubPlatformFeeProvider.kt` — `@Profile("local")` Shopify fee stub
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/PriceSyncAdapter.kt` — interface for price sync adapters
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/StubPriceSyncAdapter.kt` — `@Profile("local")` Shopify price sync stub
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt` — event listener for pricing initialization on LISTED transition
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/GlobalExceptionHandler.kt` — `@ControllerAdvice` exception handler

### Modified Files (10)
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/StripeProcessingFeeProvider.kt` — implements `ProcessingFeeProvider`, `@Profile("!local")`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyPlatformFeeProvider.kt` — implements `PlatformFeeProvider`, `@Profile("!local")`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/FedExRateAdapter.kt` — `@Profile("!local")`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UpsRateAdapter.kt` — `@Profile("!local")`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UspsRateAdapter.kt` — `@Profile("!local")`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/CostGateService.kt` — constructor uses `ProcessingFeeProvider` and `PlatformFeeProvider` interfaces
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/config/ExternalApiConfig.kt` — `@Profile("!local")`
- `modules/catalog/src/test/kotlin/com/autoshipper/catalog/domain/service/CostGateServiceTest.kt` — mocks updated to interface types
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/ShopifyPriceSyncAdapter.kt` — implements `PriceSyncAdapter`, `@Profile("!local")`
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingDecisionListener.kt` — depends on `PriceSyncAdapter` interface

## Testing Completed

### Automated Tests
- `./gradlew build` — **BUILD SUCCESSFUL**, all existing tests pass with no regressions

### Manual Smoke Test (local profile)
Full lifecycle validated end-to-end with `spring.profiles.active=local`:

| # | Test | Result |
|---|---|---|
| 1 | `POST /api/skus` — create SKU | **PASS** |
| 2 | IDEATION → VALIDATION_PENDING → COST_GATING | **PASS** |
| 3 | `POST /verify-costs` with stub providers | **PASS** — fully burdened $46.34 |
| 4 | `POST /stress-test` at $199.99 | **PASS** — 72.96% gross margin |
| 5 | SKU auto-transitions to LISTED | **PASS** |
| 6 | `GET /pricing` returns 200 with data | **PASS** — price $199.99, margin 72.96% |
| 7 | LISTED → PAUSED → LISTED (resume) | **PASS** |
| 8 | Pricing idempotent after resume | **PASS** — 1 history entry, no duplicate |
| 9 | LISTED → TERMINATED | **PASS** — MANUAL_OVERRIDE reason |
| 10 | Invalid transition (IDEATION → LISTED) | **PASS** — 409 Conflict |
| 11 | Non-existent SKU | **PASS** — 404 Not Found |
| 12 | Filter by state | **PASS** |

## Deployment Notes

- **Local development:** Set `spring.profiles.active=local` to use stub providers. No API keys required.
- **Production:** No changes — default (non-local) profile loads live providers as before.
- **Backwards compatible:** All existing API behavior unchanged. New exception handler only affects error response format (500 → 409/502/404).
