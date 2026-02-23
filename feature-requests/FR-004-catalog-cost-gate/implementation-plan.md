# FR-004: Catalog Cost Gate — Implementation Plan

## Technical Design

`CostEnvelope` is a sealed class — `Unverified` or `Verified`. `CostGateService` orchestrates verification of all 13 components by calling typed providers. External API adapters implement the `CarrierRateProvider`, `ProcessingFeeProvider`, and `PlatformFeeProvider` interfaces. Resilience4j provides circuit breakers and retry on all external calls.

```
catalog/src/main/kotlin/com/autoshipper/catalog/
├── domain/
│   └── CostEnvelope.kt           (sealed class)
├── domain/service/
│   └── CostGateService.kt        (orchestrates verification)
├── proxy/
│   ├── carrier/
│   │   ├── CarrierRateProvider.kt  (interface)
│   │   ├── UpsRateAdapter.kt
│   │   ├── FedExRateAdapter.kt
│   │   └── UspsRateAdapter.kt
│   ├── payment/
│   │   └── StripeProcessingFeeProvider.kt
│   └── platform/
│       └── ShopifyPlatformFeeProvider.kt
├── handler/
│   └── CostGateController.kt     (POST /api/skus/{id}/verify-costs)
└── config/
    └── ExternalApiConfig.kt      (RestClient beans, circuit breaker config)
```

## Architecture Decisions

- **Adapter pattern for carriers**: `CarrierRateProvider` interface decouples domain from specific carrier APIs. Swap UPS for DHL without touching domain logic.
- **Resilience4j circuit breaker**: Prevents cascade failures when a carrier API is down. Falls back to `ProviderUnavailableException` — never to hardcoded rates.
- **`CostEnvelope.Verified` is constructed only inside `CostGateService`**: No public constructor on `Verified` — the service is the only factory. Enforced via internal visibility.
- **`verified_at` timestamp on `CostEnvelope.Verified`**: Envelopes expire after 24 hours (configurable) — stale costs must be re-verified before listing.

## Layer-by-Layer Implementation

### Domain Layer
- `CostEnvelope.Unverified(skuId)` and `CostEnvelope.Verified(skuId, all 13 components, verifiedAt)`
- `CostEnvelope.Verified.fullyBurdened: Money` computed from summing all components

### Proxy Layer (External Adapters)
- `CarrierRateProvider`: `fun getRate(origin: Address, destination: Address, packageDims: PackageDimensions): Money`
- `UpsRateAdapter`, `FedExRateAdapter`, `UspsRateAdapter`: HTTP clients calling respective REST APIs
- `StripeProcessingFeeProvider`: calls Stripe API for current processing rate
- `ShopifyPlatformFeeProvider`: fetches current Shopify plan fee schedule

### Domain Service
- `CostGateService.verify(skuId, vendorQuote, packageDims, ...): CostEnvelope.Verified`
- Calls all providers, assembles `Verified` envelope, persists, publishes `CostEnvelopeVerified` event

### Handler Layer
- `POST /api/skus/{id}/verify-costs` with request body containing vendor quote, package dimensions, CAC estimate, jurisdiction

## Task Breakdown

### Domain Layer
- [x] Implement `CostEnvelope` sealed class with `Unverified` and `Verified` variants
- [x] Add `fullyBurdened: Money` computed property to `Verified`
- [x] Add `PackageDimensions` value class (length, width, height, weight in standard units)
- [x] Add `Address` data class for origin/destination
- [x] Define `CostEnvelopeExpiredException` for stale envelope detection

### Proxy Layer
- [x] Define `CarrierRateProvider` interface
- [x] Implement `UpsRateAdapter` with Spring `RestClient` and UPS API auth
- [x] Implement `FedExRateAdapter` with Spring `RestClient` and FedEx API auth
- [x] Implement `UspsRateAdapter` with Spring `RestClient` and USPS API auth
- [x] Implement `StripeProcessingFeeProvider` fetching live rate
- [x] Implement `ShopifyPlatformFeeProvider` fetching current plan fee
- [x] Add Resilience4j circuit breaker + retry (3 attempts, exponential backoff) on all adapters
- [x] Implement `DimWeightCalculator` (length × width × height / divisor, compare to actual weight) — implemented as `billableWeight()` method on `PackageDimensions`

### Domain Service
- [x] Implement `CostGateService.verify()` orchestrating all 13 components
- [x] Publish `CostEnvelopeVerified` domain event on successful verification
- [x] Persist `CostEnvelope.Verified` to `sku_cost_envelopes` table
- [x] Transition SKU state from `CostGating` → `StressTesting` on success

### Handler Layer
- [x] Implement `CostGateController` with `POST /api/skus/{id}/verify-costs`
- [x] Add `VerifyCostsRequest` DTO (vendor quote, package dims, CAC, jurisdiction)
- [x] Return `CostEnvelopeResponse` with all components and `fullyBurdened` total

### Config Layer
- [x] Create `ExternalApiConfig` with `RestClient` beans for UPS, FedEx, USPS, Stripe, Shopify
- [x] Configure Resilience4j circuit breakers in `application.yml`
- [x] Add all API credentials as environment variable references

### Persistence (Common Layer)
- [x] Write `V3__cost_envelopes.sql` migration
- [x] Implement `CostEnvelopeRepository`

## Testing Strategy

- Unit tests: `CostGateService` with all providers mocked — happy path, provider timeout, currency mismatch
- WireMock integration tests: each carrier adapter called and response parsed correctly
- WireMock test: circuit breaker opens after 3 failures
- Integration test (Testcontainers): full verify-costs flow persists envelope and transitions SKU state
- Test: stale envelope (> 24h) raises `CostEnvelopeExpiredException`

## Rollout Plan

1. Write `V3__cost_envelopes.sql`
2. Implement `CostEnvelope` sealed class
3. Implement carrier/processor adapters with WireMock stubs for testing
4. Implement `CostGateService` wiring all providers
5. Add REST handler
6. Configure circuit breakers in `application.yml`
