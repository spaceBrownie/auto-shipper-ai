# FR-004: Catalog Cost Gate — Summary

## Feature Summary

Implemented a fully verified cost envelope system for SKUs. Before a SKU can transition to `StressTesting` (and ultimately reach `Listed`), all 13 cost components must be verified via live external APIs. The `CostEnvelope.Verified` type is the domain's proof-of-verification — structurally impossible to construct without it.

## Changes Made

- **Domain Layer**: `CostEnvelope` sealed class with `Unverified` / `Verified` variants. `Verified` has `internal` constructor (only `CostGateService` constructs it), enforces same-currency across all 13 components in `init` block, and exposes `fullyBurdened: Money`.
- **Proxy Layer**: `CarrierRateProvider` interface + `UpsRateAdapter`, `FedExRateAdapter`, `UspsRateAdapter`. `StripeProcessingFeeProvider` and `ShopifyPlatformFeeProvider`. All use Spring `RestClient` + Resilience4j circuit breakers + retry.
- **Domain Service**: `CostGateService.verify()` — fetches cheapest carrier rate, Stripe/Shopify fees, computes allowances, constructs `CostEnvelope.Verified`, persists, transitions SKU `CostGating → StressTesting`, publishes `CostEnvelopeVerified`.
- **Persistence**: `CostEnvelopeEntity` + `CostEnvelopeRepository`.
- **Handler**: `POST /api/skus/{id}/verify-costs`.
- **Config**: 5 named `RestClient` beans; all credentials from environment variables.
- **Migration**: `V3__cost_envelopes.sql`.

## Files Modified

| File | Description |
|---|---|
| `catalog/…/domain/CostEnvelope.kt` | Sealed class |
| `catalog/…/domain/PackageDimensions.kt` | Dim weight calculation |
| `catalog/…/domain/Address.kt` | Address data class |
| `catalog/…/domain/CostEnvelopeExpiredException.kt` | Stale envelope error |
| `catalog/…/domain/ProviderUnavailableException.kt` | External API failure |
| `catalog/…/proxy/carrier/CarrierRateProvider.kt` | Interface |
| `catalog/…/proxy/carrier/UpsRateAdapter.kt` | UPS adapter |
| `catalog/…/proxy/carrier/FedExRateAdapter.kt` | FedEx adapter |
| `catalog/…/proxy/carrier/UspsRateAdapter.kt` | USPS adapter |
| `catalog/…/proxy/payment/StripeProcessingFeeProvider.kt` | Stripe provider |
| `catalog/…/proxy/platform/ShopifyPlatformFeeProvider.kt` | Shopify provider |
| `catalog/…/domain/service/CostGateService.kt` | Orchestration service |
| `catalog/…/persistence/CostEnvelopeEntity.kt` | JPA entity |
| `catalog/…/persistence/CostEnvelopeRepository.kt` | JPA repository |
| `catalog/…/handler/CostGateController.kt` | REST controller |
| `catalog/…/handler/dto/VerifyCostsRequest.kt` | Request DTO |
| `catalog/…/handler/dto/CostEnvelopeResponse.kt` | Response DTO |
| `catalog/…/config/ExternalApiConfig.kt` | RestClient beans |
| `app/…/db/migration/V3__cost_envelopes.sql` | DB migration |
| `app/…/application.yml` | Resilience4j + API config |
| `catalog/build.gradle.kts` | Resilience4j dependencies |

## Testing Completed

- `CostEnvelopeTest`: `fullyBurdened` sum; currency mismatch enforcement; exception messages.
- `CostGateServiceTest`: happy path; provider exception propagation; currency mismatch.
- **7 catalog tests pass** — `BUILD SUCCESSFUL`.

## Deployment Notes

- Set env vars: `UPS_CLIENT_ID`, `UPS_CLIENT_SECRET`, `FEDEX_CLIENT_ID`, `FEDEX_CLIENT_SECRET`, `USPS_OAUTH_TOKEN`, `STRIPE_SECRET_KEY`, `SHOPIFY_ACCESS_TOKEN`.
- Flyway runs `V3__cost_envelopes.sql` automatically on startup.
