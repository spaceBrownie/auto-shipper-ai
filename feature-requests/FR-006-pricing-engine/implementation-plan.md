# FR-006: Pricing Engine — Implementation Plan

## Technical Design

The pricing module contains a `PricingEngine` that listens for `PricingSignal` events, recalculates margin, and emits `PricingDecision`. The `BackwardInductionPricer` computes the initial launch price. A `ShopifyPriceSyncAdapter` pushes price changes to Shopify. All pricing history is persisted to `sku_pricing_history`.

```
pricing/src/main/kotlin/com/autoshipper/pricing/
├── domain/
│   └── SkuPrice.kt              (current price entity)
├── domain/service/
│   ├── PricingEngine.kt         (signal → decision processor)
│   └── BackwardInductionPricer.kt (WTP → cost → price calculation)
├── proxy/
│   └── ShopifyPriceSyncAdapter.kt
├── handler/
│   └── PricingController.kt
└── config/
    └── PricingConfig.kt         (@ConfigurationProperties — conversion threshold)
```

## Architecture Decisions

- **Event-driven signal processing**: `PricingEngine` is a Spring `@EventListener` for `PricingSignal` events. Modules that detect cost changes publish signals without knowing about the pricing module.
- **`PricingDecision` drives side effects**: The engine only produces a decision. Side effects (pause, terminate, sync to Shopify) are handled by separate listeners, maintaining single responsibility.
- **Conversion threshold in config**: The percentage drop that triggers a pause (`15%` default) is configurable — not hardcoded.
- **Backward induction as a pure function**: `BackwardInductionPricer.compute(wtpCeiling, costEnvelope): Money` is a pure function — easy to test, no side effects.

## Layer-by-Layer Implementation

### Domain Service
- `BackwardInductionPricer.compute(wtpCeiling: Money, envelope: CostEnvelope.Verified): Money`
  - If `(wtpCeiling - envelope.fullyBurdened) / wtpCeiling < 30%`, returns null (SKU must be terminated)
  - Otherwise returns the price that achieves exactly the required margin buffer
- `PricingEngine` `@EventListener(PricingSignal::class)`:
  - Fetches current cost envelope, applies signal delta, recalculates margin
  - If margin ≥ 30% and conversion impact ≤ threshold → emit `PricingDecision.Adjusted`
  - If margin < 30% or conversion impact > threshold → emit `PricingDecision.PauseRequired`
  - If no price exists that satisfies margin floor → emit `PricingDecision.TerminateRequired`

### Proxy Layer
- `ShopifyPriceSyncAdapter`: `fun syncPrice(skuId: SkuId, newPrice: Money)` — calls Shopify Products API with retry

### Handler Layer
- `GET /api/skus/{id}/pricing` — returns current price, margin, last signal, decision history

### Domain
- `SkuPrice` JPA entity: skuId, currentPrice, currentMargin, lastSignal, lastDecision, updatedAt

## Task Breakdown

### Domain Layer
- [ ] Implement `SkuPrice` JPA entity (skuId, currentPrice, currentMargin, lastUpdated)
- [ ] Implement `BackwardInductionPricer` pure function (WTP ceiling → launch price)
- [ ] Add null/termination path when cost envelope cannot fit in WTP ceiling

### Domain Service
- [ ] Implement `PricingEngine` as Spring `@EventListener` for `PricingSignal`
- [ ] Implement signal delta application for all 4 signal types (ShippingCostChanged, VendorCostChanged, CacChanged, PlatformFeeChanged)
- [ ] Implement margin recalculation from adjusted cost + current price
- [ ] Emit `PricingDecision.Adjusted` when margin ≥ 30% and conversion impact ≤ threshold
- [ ] Emit `PricingDecision.PauseRequired` when margin < 30% or conversion threshold breached
- [ ] Emit `PricingDecision.TerminateRequired` when no viable price exists
- [ ] Implement `PricingDecisionListener` applying decisions (trigger pause/terminate via catalog events, sync price via Shopify adapter)
- [ ] Persist all pricing decisions to `sku_pricing_history`

### Proxy Layer
- [ ] Implement `ShopifyPriceSyncAdapter` with `RestClient` + retry on `Adjusted` decision

### Handler Layer
- [ ] Implement `PricingController` with `GET /api/skus/{id}/pricing`
- [ ] Return `PricingResponse` (currentPrice, margin, history)

### Config Layer
- [ ] Define `PricingConfig` `@ConfigurationProperties` (conversionThreshold, marginFloor)
- [ ] Add `conversion.threshold.percent` to `application.yml` (default 15)

### Persistence (Common Layer)
- [ ] Write `V5__pricing.sql` migration (sku_prices, sku_pricing_history tables)
- [ ] Implement `SkuPriceRepository`, `SkuPricingHistoryRepository`

## Testing Strategy

- Unit test `BackwardInductionPricer`: known WTP + cost → expected price, margin exactly 30% edge case
- Unit test `PricingEngine`: all 4 signal types, each decision outcome
- Unit test: margin exactly at 30% floor emits `Adjusted`, 29.9% emits `PauseRequired`
- WireMock test: `ShopifyPriceSyncAdapter` calls correct endpoint with correct price payload
- Integration test: `PricingSignal` event published → `PricingDecision` emitted → Shopify sync called

## Rollout Plan

1. Write `V5__pricing.sql`
2. Implement `BackwardInductionPricer` (pure function, test first)
3. Implement `PricingEngine` event listener
4. Implement `ShopifyPriceSyncAdapter`
5. Implement decision side-effect listener (pause/terminate)
6. Add REST handler
