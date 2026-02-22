# FR-006: Pricing Engine

## Problem Statement

Pricing must never be static. The commerce engine uses backward-induction pricing (price ceiling → cost envelope → margin buffer → launch price) and must continuously react to cost signals. If dynamic adjustment cannot maintain the 30% net margin floor or would harm conversion, the SKU must be automatically paused or terminated — not discounted below the floor.

## Business Requirements

- Initial price is derived by backward induction: validated willingness-to-pay ceiling → subtract fully burdened cost → confirm margin ≥ 30% → set launch price
- If the cost envelope cannot fit inside the validated price ceiling with required margins, the SKU is terminated — not discounted
- The pricing engine listens for `PricingSignal` events: `ShippingCostChanged`, `VendorCostChanged`, `CacChanged`, `PlatformFeeChanged`
- On receiving a signal, the engine recalculates the price and emits a `PricingDecision`: `Adjusted`, `PauseRequired`, or `TerminateRequired`
- `PauseRequired` is emitted when: adjustment would harm conversion beyond a defined threshold OR would push margin below 30%
- `TerminateRequired` is emitted when: dynamic adjustment is structurally impossible to maintain minimum margin
- Price changes must be pushed to the Shopify product listing via the Shopify API

## Success Criteria

- `PricingSignal` sealed class with all 4 signal types defined in shared module
- `PricingDecision` sealed class with `Adjusted`, `PauseRequired`, `TerminateRequired` variants
- `PricingEngine` service processes signals and emits decisions
- `BackwardInductionPricer` computes initial price from WTP ceiling and cost envelope
- Shopify price sync on `Adjusted` decision
- Auto-pause triggered on `PauseRequired` (publishes `SkuStateChanged` to `Paused`)
- Auto-terminate triggered on `TerminateRequired` (publishes `SkuTerminated`)
- `GET /api/skus/{id}/pricing` returns current price, margin, and pricing history
- Unit tests: all signal types, margin floor enforcement, conversion threshold logic

## Non-Functional Requirements

- Pricing history stored in `sku_pricing_history` table (price, margin, signal, decision, timestamp)
- Signal processing must complete within 2 seconds end-to-end
- Shopify API update on price change with retry (3 attempts, exponential backoff)
- Conversion threshold configurable via `application.yml` (default: 15% conversion drop triggers pause)

## Dependencies

- FR-001 (shared-domain-primitives) — `Money`, `Percentage`, `PricingSignal`, `PricingDecision`
- FR-003 (catalog-sku-lifecycle) — SKU state transitions (pause, terminate)
- FR-004 (catalog-cost-gate) — `CostEnvelope.Verified` used for margin recalculation
- FR-005 (catalog-stress-test) — `LaunchReadySku` is the entry point; pricing operates on listed SKUs
