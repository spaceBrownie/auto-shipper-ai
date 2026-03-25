# FR-024: Codebase Quality Audit — Test Manifest

## Test File Inventory

| # | File | Module | Tests | Status |
|---|------|--------|-------|--------|
| 1 | `modules/catalog/src/test/kotlin/com/autoshipper/catalog/proxy/carrier/UpsRateAdapterBlankCredentialTest.kt` | catalog | 3 | NEW — Expected FAIL |
| 2 | `modules/catalog/src/test/kotlin/com/autoshipper/catalog/proxy/carrier/FedExRateAdapterBlankCredentialTest.kt` | catalog | 3 | NEW — Expected FAIL |
| 3 | `modules/catalog/src/test/kotlin/com/autoshipper/catalog/proxy/carrier/UspsRateAdapterBlankCredentialTest.kt` | catalog | 1 | NEW — Expected FAIL |
| 4 | `modules/catalog/src/test/kotlin/com/autoshipper/catalog/proxy/payment/StripeProcessingFeeProviderBlankCredentialTest.kt` | catalog | 1 | NEW — Expected FAIL |
| 5 | `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/payment/StripeRefundAdapterBlankCredentialTest.kt` | fulfillment | 1 | NEW — Expected FAIL |
| 6 | `modules/catalog/src/test/kotlin/com/autoshipper/catalog/proxy/carrier/FedExRateAdapterUrlEncodingTest.kt` | catalog | 2 | NEW — Expected FAIL |
| 7 | `modules/portfolio/src/test/kotlin/com/autoshipper/portfolio/proxy/AmazonCreatorsApiAdapterUrlEncodingTest.kt` | portfolio | 4 | NEW — 3 PASS (contract), 1 PASS (instantiation) |
| 8 | `modules/app/src/test/kotlin/com/autoshipper/ArchitectureTest.kt` | app | 1 new (Rule 4) | MODIFIED — Expected FAIL |

**Total new tests:** 16
**Total modified test files:** 1 (ArchitectureTest — added Rule 4)

## Category Mapping

### End-to-End Flow Tests

| Test | File | What it verifies |
|------|------|------------------|
| `EventListener methods must not handle shared domain events` | `ArchitectureTest.kt` (Rule 4) | Structural scan of all production classes: flags any `@EventListener` method whose parameter type is from `com.autoshipper.shared.events.*`, with an allowlist for `PricingEngine.onPricingSignal`. This is the authoritative structural enforcement for CLAUDE.md constraint #6. |

### Boundary Condition Tests

| Test | File | What it verifies |
|------|------|------------------|
| `getRate throws ProviderUnavailableException when clientId is blank` | `UpsRateAdapterBlankCredentialTest.kt` | UPS adapter rejects blank `clientId` before making HTTP call |
| `getRate throws ProviderUnavailableException when clientSecret is blank` | `UpsRateAdapterBlankCredentialTest.kt` | UPS adapter rejects blank `clientSecret` before making HTTP call |
| `getRate throws ProviderUnavailableException when both credentials are blank` | `UpsRateAdapterBlankCredentialTest.kt` | UPS adapter rejects both blank credentials |
| `getRate throws ProviderUnavailableException when clientId is blank` | `FedExRateAdapterBlankCredentialTest.kt` | FedEx adapter rejects blank `clientId` before making HTTP call |
| `getRate throws ProviderUnavailableException when clientSecret is blank` | `FedExRateAdapterBlankCredentialTest.kt` | FedEx adapter rejects blank `clientSecret` before making HTTP call |
| `getRate throws ProviderUnavailableException when both credentials are blank` | `FedExRateAdapterBlankCredentialTest.kt` | FedEx adapter rejects both blank credentials |
| `getRate throws ProviderUnavailableException when oauthToken is blank` | `UspsRateAdapterBlankCredentialTest.kt` | USPS adapter rejects blank `oauthToken` before making HTTP call |
| `getFee throws ProviderUnavailableException when secretKey is blank` | `StripeProcessingFeeProviderBlankCredentialTest.kt` | Stripe fee provider rejects blank `secretKey` before making HTTP call |
| `refund throws IllegalStateException when secretKey is blank` | `StripeRefundAdapterBlankCredentialTest.kt` | Stripe refund adapter rejects blank `secretKey` before making HTTP call |
| `fetchBearerToken URL-encodes client credentials containing ampersand` | `FedExRateAdapterUrlEncodingTest.kt` | FedEx token request body contains `%26` for `&` in credentials |
| `fetchBearerToken URL-encodes client credentials containing equals sign` | `FedExRateAdapterUrlEncodingTest.kt` | FedEx token request body contains `%3D` for `=` in credentials |

### Dependency Contract Tests

| Test | File | What it verifies |
|------|------|------------------|
| `credentials with ampersands must be URL-encoded in form body` | `AmazonCreatorsApiAdapterUrlEncodingTest.kt` | URL-encoding contract: `&` encodes to `%26` |
| `credentials with equals signs must be URL-encoded in form body` | `AmazonCreatorsApiAdapterUrlEncodingTest.kt` | URL-encoding contract: `=` encodes to `%3D` |
| `credentials with plus signs must be URL-encoded in form body` | `AmazonCreatorsApiAdapterUrlEncodingTest.kt` | URL-encoding contract: `+` encodes to `%2B` |
| `adapter can be instantiated with special-character credentials` | `AmazonCreatorsApiAdapterUrlEncodingTest.kt` | Amazon adapter accepts special-character credentials without construction error |

## Spec Traceability

| BR | Requirement | Test Coverage | Notes |
|----|------------|---------------|-------|
| BR-1 | `VendorSlaBreachRefunder` annotation change | ArchUnit Rule 4 (will FAIL until annotation changed), existing 6 tests in `VendorSlaBreachRefunderTest.kt` (unchanged, should still pass) | Rule 4 detects `@EventListener` on shared events; after BR-1 fix, it passes. Rule 1 (existing) verifies the new `@TransactionalEventListener(AFTER_COMMIT)` pairs with `REQUIRES_NEW`. |
| BR-2 | `@Value` empty defaults + blank-credential guards | 9 tests across 5 new test files | Each adapter's guard is tested by instantiating with blank credentials and asserting the correct exception type. |
| BR-3 | URL-encode OAuth token bodies | 2 WireMock tests for FedEx, 4 contract tests for Amazon | FedEx tests use WireMock to capture the actual token request body. Amazon tests verify the URL-encoding contract (adapter's `getAccessToken()` has a hardcoded token URL that can't be WireMocked). |
| BR-4 | ArchUnit Rule 4 | 1 new test in `ArchitectureTest.kt` | The ArchUnit rule IS the test. It scans all production classes and flags `@EventListener` methods handling shared events. |
| BR-5 | `PricingInitializer` post-persist log | No new tests | Existing `PricingInitializerIntegrationTest` (2 tests) verifies persist behavior. The log addition is observability-only. |
| BR-6 | mockk investigation document | No tests needed | Documentation-only deliverable. |
| BR-7 | CLAUDE.md vibe coding warning | No tests needed | Documentation-only deliverable. |

## Expected Failures

All 16 new tests compile successfully (`./gradlew compileTestKotlin` passes). The following tests are expected to FAIL at runtime until Phase 5 implementation:

### Will FAIL until Phase 5

| Test | Why it fails | What Phase 5 must do |
|------|-------------|---------------------|
| All 9 blank-credential guard tests (UPS, FedEx, USPS, Stripe fee, Stripe refund) | Adapters currently lack blank-credential guards — calling `getRate()`/`getFee()`/`refund()` with blank credentials attempts an HTTP call instead of throwing early | Add `if (credential.isBlank()) throw ProviderUnavailableException(...)` guard at top of each entry-point method |
| Both FedEx URL-encoding tests | `fetchBearerToken()` currently uses raw `$clientId` interpolation without `URLEncoder.encode()` — WireMock capture shows un-encoded `&` and `=` | Wrap credentials with `URLEncoder.encode(value, StandardCharsets.UTF_8)` in form body |
| ArchUnit Rule 4 | `VendorSlaBreachRefunder.onVendorSlaBreached()` currently uses `@EventListener` to handle `VendorSlaBreached` (a shared event) | Change `@EventListener` to `@TransactionalEventListener(phase = AFTER_COMMIT)` on `VendorSlaBreachRefunder` |

### Will PASS now (contract/setup tests)

| Test | Why it passes |
|------|--------------|
| 3 Amazon URL-encoding contract tests | Test `URLEncoder.encode()` directly — validates the encoding contract, not the adapter's use of it |
| 1 Amazon adapter instantiation test | Verifies constructor and `sourceType()` — no HTTP call involved |

## Existing Tests (Unchanged)

These existing tests are NOT modified but must continue passing after Phase 5 implementation:

| Test File | Tests | BR Covered |
|-----------|-------|------------|
| `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/VendorSlaBreachRefunderTest.kt` | 6 | BR-1 |
| `modules/app/src/test/kotlin/com/autoshipper/pricing/PricingInitializerIntegrationTest.kt` | 2 | BR-5 |
| `modules/app/src/test/kotlin/com/autoshipper/ArchitectureTest.kt` (Rules 1-3) | 3 | BR-1 (Rule 1 validates new annotation pair) |
