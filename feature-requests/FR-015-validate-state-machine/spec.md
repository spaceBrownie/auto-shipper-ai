# FR-015: Validate State Machine — Fix Endpoint Testing Gaps

## Problem Statement

Endpoint testing conducted on 2026-03-04 (documented in `docs/endpoint-test-results-2026-03-04.md`) validated the full SKU lifecycle state machine across 13 test scenarios. While 10 of 13 tests passed, three issues were identified that block or degrade the local development and API experience:

1. **Cost verification is untestable locally (Test #6 — BLOCKED).** `POST /api/skus/{id}/verify-costs` requires live API keys for FedEx, UPS, USPS, Stripe, and Shopify. There are no `@Profile("local")` stub implementations, and `StripeProcessingFeeProvider` and `ShopifyPlatformFeeProvider` are concrete classes injected directly into `CostGateService` — making it impossible to swap them without interface extraction. Unit tests use Mockito mocks, but integration testing and manual local testing of the cost gate flow are completely blocked without credentials.

2. **Pricing data is never initialized when a SKU reaches LISTED state (Test #12 — 404).** `PricingEngine.setInitialPrice()` exists but is never invoked. The stress test computes an estimated price and produces a `LaunchReadySku`, but no component listens for the `SkuStateChanged` event with `toState == LISTED` to bridge that data into the pricing module. As a result, `GET /api/skus/{id}/pricing` always returns 404 for listed SKUs.

3. **Domain exceptions produce generic 500 errors instead of meaningful HTTP status codes (Test #13 — 500).** `InvalidSkuTransitionException` (e.g., attempting IDEATION to LISTED) returns 500 Internal Server Error instead of 409 Conflict. `ProviderUnavailableException` returns 500 instead of 502 Bad Gateway. `NoSuchElementException` for missing SKUs returns 500 instead of 404 Not Found. There is no `@ControllerAdvice` exception handler.

These are gaps in the FR-003 through FR-006 implementations. No upcoming feature request (FR-007 through FR-012) addresses them.

## Business Requirements

### BR-1: Local Development Must Not Require External API Keys

The cost verification flow (`POST /verify-costs`) must be fully functional in a local development environment without any external API credentials. Developers must be able to walk a SKU through the entire lifecycle — from Ideation to Listed — using only a local Spring profile.

- All five external provider dependencies (FedEx, UPS, USPS, Stripe, Shopify) must have stub alternatives available under a `local` profile.
- Stripe and Shopify providers must be accessed through interfaces, not concrete classes, so that `CostGateService` is decoupled from specific implementations.
- Live provider implementations must not load when the `local` profile is active.
- Stub providers must return realistic (not zero) values so that downstream stress testing produces meaningful results.

### BR-2: Listed SKUs Must Have Initial Pricing Data

When a SKU transitions to the `LISTED` state, the system must automatically initialize its pricing record using data from the completed stress test. This ensures that `GET /api/skus/{id}/pricing` returns valid pricing data for any listed SKU.

- Price initialization must happen automatically with no manual intervention.
- The initial price, margin, and fully burdened cost must be derived from the stress test result and cost envelope — not hardcoded.
- If a pricing record already exists for the SKU, the initialization must be idempotent (no duplicate or overwrite).

### BR-3: Domain Exceptions Must Map to Appropriate HTTP Status Codes

API consumers must receive meaningful HTTP status codes and error bodies when domain exceptions occur, rather than generic 500 Internal Server Error responses.

- `InvalidSkuTransitionException` must return **409 Conflict** — the request conflicts with the current state of the resource.
- `ProviderUnavailableException` must return **502 Bad Gateway** — an upstream dependency failed.
- `NoSuchElementException` (SKU not found) must return **404 Not Found**.
- Error responses must include a structured JSON body with at minimum an `error` field (exception type or short code) and a `message` field (human-readable description).

## Success Criteria

### SC-1: Local Cost Verification
- With `spring.profiles.active=local`, `POST /api/skus/{id}/verify-costs` succeeds and produces a `CostEnvelope.Verified` without any external API keys configured.
- The stub carrier rate providers return non-zero shipping rates in USD.
- The stub Stripe provider applies the standard 2.9% + $0.30 formula without making an API call.
- The stub Shopify provider returns a non-zero platform fee without making an API call.
- `CostGateService` depends on `ProcessingFeeProvider` and `PlatformFeeProvider` interfaces, not concrete Stripe/Shopify classes.

### SC-2: Pricing Initialization on Listing
- After a SKU transitions to `LISTED`, `GET /api/skus/{id}/pricing` returns 200 with a valid pricing record.
- The pricing record's price, margin, and cost fields are consistent with the stress test result for that SKU.
- Calling the listing transition a second time (e.g., PAUSED to LISTED) does not create a duplicate pricing record.

### SC-3: HTTP Error Mapping
- `POST /api/skus/{id}/state` with an invalid transition returns **409** with a JSON error body.
- `POST /api/skus/{id}/verify-costs` when a carrier API is down returns **502** with a JSON error body.
- `GET /api/skus/{nonexistent-id}` returns **404** with a JSON error body.
- No domain exception produces a raw 500 response with a Spring default error page.

### SC-4: Existing Tests Continue to Pass
- `./gradlew build` succeeds with all existing tests passing.
- No behavioral regression in the catalog or pricing modules.

## Non-Functional Requirements

### Backwards Compatibility
- The `local` profile stubs are additive; the default (non-local) profile behavior is unchanged.
- Live provider classes retain their existing behavior and constructor signatures.
- The new interfaces (`ProcessingFeeProvider`, `PlatformFeeProvider`) must match the method signatures already used by `CostGateService`, so that no callers outside the injection point need changes.
- Error response JSON structure must not conflict with any existing error responses in the API.

### Performance
- Stub providers must return immediately (no simulated latency) to keep local test cycles fast.
- The pricing initialization event listener must not introduce observable latency into the state transition response — it runs after the transaction commits.

### Observability
- Error responses should log at WARN level (client errors: 4xx) or ERROR level (upstream failures: 502) for monitoring consistency.

### Testability
- The interface extraction for Stripe and Shopify providers must make it straightforward to inject test doubles in unit tests without Mockito (constructor injection of interface).

## Dependencies

### Modules Affected
- **`:catalog`** — stub providers, interface extraction, profile annotations on live providers, exception handler
- **`:pricing`** — event listener for pricing initialization
- **`:shared`** — domain events (`SkuStateChanged`) already exist; no changes expected

### Key Files Referenced
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/` — FedEx, UPS, USPS rate adapters
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/StripeProcessingFeeProvider.kt`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyPlatformFeeProvider.kt`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/CostGateService.kt`
- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingEngine.kt`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/config/ExternalApiConfig.kt`

### External Dependencies
- None introduced. Stubs eliminate the need for external API access in local development.

### Upstream Feature Requests
- FR-003 (SKU Lifecycle), FR-004 (Cost Gate), FR-005 (Stress Test), FR-006 (Pricing Engine) — this FR fixes gaps in their implementations.
