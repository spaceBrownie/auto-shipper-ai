# FR-004: Catalog Cost Gate

## Problem Statement

No SKU may be listed without a fully verified, fully burdened cost envelope — no exceptions. The cost gate is the primary financial control mechanism preventing unprofitable launches. All 13 cost components must be verified against live data sources (not estimates), and the `CostEnvelope` must be a sealed type that structurally prevents bypassing the gate.

## Business Requirements

- A `CostEnvelope` is either `Unverified` or `Verified` — the type system enforces this distinction
- All 13 components must be resolved before the envelope transitions to `Verified`:
  1. Unit production cost (vendor quote)
  2. Packaging cost (supplier confirmation)
  3. Freight / inbound logistics (confirmed carrier rate)
  4. Last-mile shipping (live UPS/FedEx/USPS API)
  5. Dimensional weight surcharge (calculated from package dimensions)
  6. Payment processing fee (live Stripe API)
  7. Platform fee (Shopify published rate)
  8. Modeled CAC (channel benchmark or historical data)
  9. Refund reserve (percentage)
  10. Chargeback reserve (percentage)
  11. Sales tax / VAT handling (jurisdiction calculation)
  12. Currency risk buffer (for non-USD sourcing)
  13. Returns + reverse logistics + customer support allocation
- No component may be hardcoded — carrier, processor, and platform rates fetched from live APIs
- `CostEnvelope.Verified` must expose a `fullyBurdened: Money` computed property summing all components
- The `CarrierRateProvider` interface must be implemented for UPS, FedEx, and USPS with the adapter pattern

## Success Criteria

- `CostEnvelope` sealed class exists with `Unverified` and `Verified` variants
- `CarrierRateProvider` interface with `UpsRateAdapter`, `FedExRateAdapter`, `UspsRateAdapter` implementations
- `StripeProcessingFeeProvider` fetches live rate from Stripe API
- `ShopifyPlatformFeeProvider` fetches live rate from Shopify API
- `CostGateService` orchestrates verification of all 13 components and returns a `Verified` envelope
- `POST /api/skus/{id}/verify-costs` triggers cost verification
- `CostEnvelopeVerified` domain event published on success
- Unit tests mock all external APIs; integration test uses WireMock stubs

## Non-Functional Requirements

- All external API calls must time out within 5 seconds with retry (max 3 attempts, exponential backoff)
- `CostEnvelope.Verified` stored in PostgreSQL with `verified_at` timestamp
- API credentials for UPS, FedEx, USPS, Stripe, Shopify sourced from environment variables only
- Circuit breaker pattern on all external carrier/processor API calls

## Dependencies

- FR-001 (shared-domain-primitives) — `Money`, `Percentage`, `SkuId`, `CostEnvelopeVerified` event
- FR-002 (project-bootstrap) — Spring Boot, environment config
- FR-003 (catalog-sku-lifecycle) — SKU must be in `CostGating` state to trigger verification
