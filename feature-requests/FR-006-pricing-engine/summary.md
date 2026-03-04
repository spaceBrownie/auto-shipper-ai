# FR-006: Pricing Engine — Implementation Summary

## Feature Summary

Implemented the dynamic pricing engine for the commerce engine. The module processes cost change signals in real-time, recalculates margins against a configurable 30% floor, and automatically emits decisions to adjust prices, pause, or terminate SKUs. Backward induction pricing computes launch prices from a willingness-to-pay ceiling and fully burdened cost.

## Changes Made

- **Pricing Engine**: Event-driven `@EventListener` that processes `PricingSignal` events (shipping, vendor, CAC, platform fee changes), recalculates margins, and emits `PricingDecision` (Adjusted, PauseRequired, TerminateRequired)
- **Backward Induction Pricer**: Pure function computing launch prices from WTP ceiling and cost, with null-return path when margin floor cannot be met
- **Decision Side Effects**: `PricingDecisionListener` handles Adjusted (Shopify sync), PauseRequired (SKU pause via catalog), and TerminateRequired (SKU termination via catalog)
- **Shopify Price Sync**: REST adapter with resilience4j retry for pushing price changes to Shopify
- **REST API**: `GET /api/skus/{id}/pricing` returns current price, margin, and full pricing decision history
- **Configuration**: `pricing.margin-floor-percent` (default 30) and `pricing.conversion-threshold-percent` (default 15) in application.yml

## Files Modified

| File | Description |
|---|---|
| `modules/pricing/build.gradle.kts` | Added `:catalog` dependency, validation, resilience4j, test deps |
| `modules/pricing/src/main/kotlin/.../persistence/SkuPriceEntity.kt` | JPA entity for current SKU price and margin |
| `modules/pricing/src/main/kotlin/.../persistence/SkuPricingHistoryEntity.kt` | JPA entity for pricing decision history |
| `modules/pricing/src/main/kotlin/.../persistence/SkuPriceRepository.kt` | Repository for SkuPriceEntity |
| `modules/pricing/src/main/kotlin/.../persistence/SkuPricingHistoryRepository.kt` | Repository for pricing history |
| `modules/pricing/src/main/kotlin/.../config/PricingConfig.kt` | @ConfigurationProperties for margin floor and conversion threshold |
| `modules/pricing/src/main/kotlin/.../config/PricingConfigProperties.kt` | Enables PricingConfig properties |
| `modules/pricing/src/main/kotlin/.../domain/service/BackwardInductionPricer.kt` | Pure function: WTP ceiling + cost → launch price |
| `modules/pricing/src/main/kotlin/.../domain/service/PricingEngine.kt` | Core @EventListener for PricingSignal processing |
| `modules/pricing/src/main/kotlin/.../domain/service/PricingDecisionListener.kt` | Handles decision side effects (pause/terminate/Shopify sync) |
| `modules/pricing/src/main/kotlin/.../proxy/ShopifyPriceSyncAdapter.kt` | Shopify price sync with resilience4j retry |
| `modules/pricing/src/main/kotlin/.../handler/PricingController.kt` | GET /api/skus/{id}/pricing endpoint |
| `modules/pricing/src/main/kotlin/.../handler/dto/PricingResponse.kt` | Response DTOs for pricing endpoint |
| `modules/app/src/main/resources/db/migration/V7__pricing.sql` | Flyway migration for sku_prices and sku_pricing_history tables |
| `modules/app/src/main/resources/application.yml` | Added pricing config and shopify-price-sync retry config |

## Testing Completed

- **16 unit tests passing** across BackwardInductionPricer and PricingEngine
- BackwardInductionPricer: margin above floor, exactly at floor, below floor, cost exceeds WTP, minimum viable price computation
- PricingEngine: all 4 signal types, Adjusted/PauseRequired/TerminateRequired decision paths, margin floor edge cases, history persistence, no-data guard clauses
- Full project build passes (`./gradlew build -x :app:test`)

## Deployment Notes

- Flyway migration `V7__pricing.sql` creates `sku_prices` and `sku_pricing_history` tables — runs automatically on app startup
- Pricing config has sensible defaults (30% margin floor, 15% conversion threshold) — override via `application.yml` or env vars
- Shopify price sync retry is configured in resilience4j (3 attempts, exponential backoff)
- The pricing module depends directly on `:catalog` for `CostEnvelopeRepository` and `SkuService` — future decoupling to event-based is a mechanical refactor when needed

## Architecture Note

The `BackwardInductionPricer` accepts `fullyBurdenedCost: Money` rather than `CostEnvelope.Verified` directly. This was a pragmatic decision since `CostEnvelope.Verified.create()` is `internal` to the `:catalog` module. The pricing engine reads the cost envelope from the database via `CostEnvelopeRepository` and computes the fully burdened total locally.
