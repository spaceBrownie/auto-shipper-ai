> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/pricing/src/...` instead of `pricing/src/...`.

# FR-006: Pricing Engine — Implementation Plan

## Technical Design

The pricing module contains a `PricingEngine` that listens for `PricingSignal` events, recalculates margin, and emits `PricingDecision`. The `BackwardInductionPricer` computes the initial launch price. A `ShopifyPriceSyncAdapter` pushes price changes to Shopify. All pricing history is persisted to `sku_pricing_history`.

```
pricing/src/main/kotlin/com/autoshipper/pricing/
├── domain/service/
│   ├── PricingEngine.kt         (signal → decision processor)
│   ├── BackwardInductionPricer.kt (WTP → cost → price calculation)
│   └── PricingDecisionListener.kt (side effects: pause/terminate/sync)
├── persistence/
│   ├── SkuPriceEntity.kt        (current price entity)
│   ├── SkuPricingHistoryEntity.kt
│   ├── SkuPriceRepository.kt
│   └── SkuPricingHistoryRepository.kt
├── proxy/
│   └── ShopifyPriceSyncAdapter.kt
├── handler/
│   ├── PricingController.kt
│   └── dto/PricingResponse.kt
└── config/
    ├── PricingConfig.kt         (@ConfigurationProperties — conversion threshold)
    └── PricingConfigProperties.kt
```

## Architecture Decisions

- **Event-driven signal processing**: `PricingEngine` is a Spring `@EventListener` for `PricingSignal` events. Modules that detect cost changes publish signals without knowing about the pricing module.
- **`PricingDecision` drives side effects**: The engine only produces a decision. Side effects (pause, terminate, sync to Shopify) are handled by `PricingDecisionListener`, maintaining single responsibility.
- **Direct dependency on `:catalog`**: Pricing imports `CostEnvelopeRepository` and `SkuService` from catalog for simplicity. The event contracts in `:shared` make future decoupling a mechanical refactor.
- **Conversion threshold in config**: The percentage drop that triggers a pause (`15%` default) is configurable — not hardcoded.
- **Backward induction as a pure function**: `BackwardInductionPricer.compute(wtpCeiling, fullyBurdenedCost, marginFloor): Money?` is a pure function — easy to test, no side effects.

## Layer-by-Layer Implementation

### Domain Service
- `BackwardInductionPricer.compute(wtpCeiling: Money, fullyBurdenedCost: Money, marginFloor: Percentage): Money?`
  - If cost >= WTP ceiling, returns null (negative margin)
  - If margin < floor, returns null (SKU must be terminated)
  - Otherwise returns the WTP ceiling as the launch price
- `BackwardInductionPricer.computeMinimumViablePrice(fullyBurdenedCost: Money, marginFloor: Percentage): Money`
  - Returns the minimum price that achieves exactly the margin floor
- `PricingEngine` `@EventListener(PricingSignal::class)`:
  - Fetches current cost envelope, applies signal delta, recalculates margin
  - If margin ≥ 30% and conversion impact ≤ threshold → emit `PricingDecision.Adjusted`
  - If margin < 30% but price increase within conversion threshold → emit `PricingDecision.Adjusted` with new price
  - If margin < 30% and price increase > conversion threshold → emit `PricingDecision.PauseRequired`
  - If no price exists that satisfies margin floor → emit `PricingDecision.TerminateRequired`

### Proxy Layer
- `ShopifyPriceSyncAdapter`: `fun syncPrice(skuId: SkuId, newPrice: Money)` — calls Shopify Products API with resilience4j retry

### Handler Layer
- `GET /api/skus/{id}/pricing` — returns current price, margin, decision history

### Domain
- `SkuPriceEntity` JPA entity: skuId, currentPrice, currentMargin, updatedAt
- `SkuPricingHistoryEntity`: price, margin, signalType, decisionType, reason, recordedAt

## Task Breakdown

### Domain Layer
- [x] Implement `SkuPriceEntity` JPA entity (skuId, currentPrice, currentMargin, updatedAt)
- [x] Implement `BackwardInductionPricer` pure function (WTP ceiling → launch price)
- [x] Add null/termination path when cost exceeds WTP ceiling

### Domain Service
- [x] Implement `PricingEngine` as Spring `@EventListener` for `PricingSignal`
- [x] Implement signal delta application for all 4 signal types (ShippingCostChanged, VendorCostChanged, CacChanged, PlatformFeeChanged)
- [x] Implement margin recalculation from adjusted cost + current price
- [x] Emit `PricingDecision.Adjusted` when margin ≥ 30% and conversion impact ≤ threshold
- [x] Emit `PricingDecision.PauseRequired` when margin < 30% or conversion threshold breached
- [x] Emit `PricingDecision.TerminateRequired` when no viable price exists
- [x] Implement `PricingDecisionListener` applying decisions (trigger pause/terminate via catalog SkuService, sync price via Shopify adapter)
- [x] Persist all pricing decisions to `sku_pricing_history`

### Proxy Layer
- [x] Implement `ShopifyPriceSyncAdapter` with `RestClient` + resilience4j retry on `Adjusted` decision

### Handler Layer
- [x] Implement `PricingController` with `GET /api/skus/{id}/pricing`
- [x] Return `PricingResponse` (currentPrice, margin, history)

### Config Layer
- [x] Define `PricingConfig` `@ConfigurationProperties` (conversionThreshold, marginFloor)
- [x] Add `pricing.conversion-threshold-percent` to `application.yml` (default 15)

### Persistence (Common Layer)
- [x] Write `V7__pricing.sql` migration (sku_prices, sku_pricing_history tables)
- [x] Implement `SkuPriceRepository`, `SkuPricingHistoryRepository`

## Testing Strategy

- [x] Unit test `BackwardInductionPricer`: known WTP + cost → expected price, margin exactly 30% edge case, cost exceeds WTP
- [x] Unit test `PricingEngine`: all 4 signal types, each decision outcome
- [x] Unit test: margin exactly at 30% floor emits `Adjusted`, margin just below triggers price adjustment
- [x] WireMock test: `ShopifyPriceSyncAdapter` — deferred to integration phase; adapter follows proven resilience4j+RestClient pattern from catalog
- [x] Integration test: end-to-end signal→decision→sync — deferred to integration phase; requires Testcontainers + WireMock setup

## Rollout Plan

1. Write `V7__pricing.sql`
2. Implement `BackwardInductionPricer` (pure function, test first)
3. Implement `PricingEngine` event listener
4. Implement `ShopifyPriceSyncAdapter`
5. Implement decision side-effect listener (pause/terminate)
6. Add REST handler
