# FR-001: Shared Domain Primitives — Implementation Plan

## Technical Design

The `shared` module is a Gradle subproject with zero external dependencies beyond the Kotlin stdlib and `java.math`. All types are immutable value types (`data class` or `@JvmInline value class`). Domain events are a sealed hierarchy with a common `occurredAt: Instant` field.

```
src/main/kotlin/com/autoshipper/shared/
├── money/
│   ├── Money.kt
│   ├── Currency.kt
│   └── Percentage.kt
├── identity/
│   ├── SkuId.kt
│   ├── VendorId.kt
│   ├── OrderId.kt
│   └── ExperimentId.kt
└── events/
    ├── DomainEvent.kt
    ├── SkuStateChanged.kt
    ├── CostEnvelopeVerified.kt
    ├── SkuTerminated.kt
    ├── PricingSignal.kt
    ├── PricingDecision.kt
    ├── VendorSlaBreached.kt
    ├── OrderFulfilled.kt
    ├── ComplianceCleared.kt
    └── ComplianceFailed.kt
```

## Architecture Decisions

- **`@JvmInline value class` for IDs**: Zero runtime overhead; type-safe identity without wrapping cost.
- **`BigDecimal` with fixed scale=4 for `Money`**: Avoids floating-point rounding errors in financial calculations. Scale is enforced in the `Money` constructor.
- **`sealed interface DomainEvent`**: Exhaustive pattern matching in when-expressions; all events carry `occurredAt: Instant`.
- **`Percentage` as `@JvmInline value class` wrapping `BigDecimal`**: Enforces 0–100 range and provides typed arithmetic without primitive confusion.
- **No Spring dependency in shared**: The module is pure Kotlin — no framework coupling at the domain primitive level.

## Layer-by-Layer Implementation

### Domain Layer (`shared/`)
- `Money`: amount (`BigDecimal` scale=4), currency (`Currency` enum). `plus`, `minus`, `times(factor)`, `marginAgainst(revenue)`. Throws `CurrencyMismatchException` on cross-currency ops.
- `Percentage`: wraps `BigDecimal`, enforces 0–100 in `init`. `of(value)` factory, `toDecimalFraction()` helper.
- `Currency`: `enum class Currency { USD, EUR, GBP, CAD }` — extend as needed.
- ID value classes: `SkuId(val value: UUID)`, `VendorId`, `OrderId`, `ExperimentId` — all `@JvmInline`.
- `DomainEvent`: `sealed interface` with `val occurredAt: Instant`.
- Event data classes implementing `DomainEvent`.

## Task Breakdown

### Domain Layer
- [x] Create `Currency` enum class (USD, EUR, GBP, CAD)
- [x] Implement `Money` data class with amount (`BigDecimal` scale=4) and `Currency`
- [x] Add `Money.plus`, `minus`, `times` with currency enforcement
- [x] Add `Money.marginAgainst(revenue: Money): Percentage`
- [x] Add `CurrencyMismatchException`
- [x] Implement `Percentage` `@JvmInline value class` with 0–100 range validation
- [x] Add `Percentage` arithmetic: `plus`, `minus`, `times`
- [x] Implement `SkuId` `@JvmInline value class` wrapping `UUID`
- [x] Implement `VendorId` `@JvmInline value class` wrapping `UUID`
- [x] Implement `OrderId` `@JvmInline value class` wrapping `UUID`
- [x] Implement `ExperimentId` `@JvmInline value class` wrapping `UUID`
- [x] Define `DomainEvent` sealed interface with `occurredAt: Instant`
- [x] Implement `SkuStateChanged` event (skuId, fromState, toState, occurredAt)
- [x] Implement `CostEnvelopeVerified` event (skuId, fullyBurdenedCost, occurredAt)
- [x] Implement `SkuTerminated` event (skuId, reason, occurredAt)
- [x] Implement `PricingSignal` sealed class (ShippingCostChanged, VendorCostChanged, CacChanged, PlatformFeeChanged)
- [x] Implement `PricingDecision` sealed class (Adjusted, PauseRequired, TerminateRequired)
- [x] Implement `VendorSlaBreached` event (vendorId, skuIds, breachRate, occurredAt)
- [x] Implement `OrderFulfilled` event (orderId, skuId, occurredAt)
- [x] Implement `ComplianceCleared` event (skuId, occurredAt)
- [x] Implement `ComplianceFailed` event (skuId, reason, occurredAt)

### Common Layer (Gradle config)
- [x] Create `shared` Gradle subproject with `build.gradle.kts`
- [x] Configure Kotlin stdlib dependency only (no Spring)
- [x] Export `shared` as an API dependency for all other modules

## Testing Strategy

- Unit tests for all `Money` arithmetic (same currency, cross-currency exception, margin calculation)
- Unit tests for `Percentage` bounds (0, 100, below 0 throws, above 100 throws)
- Unit tests for ID value class equality and toString
- Property-based tests for `Money.marginAgainst` with known inputs/outputs
- All tests run with `./gradlew :shared:test`

## Rollout Plan

1. Create Gradle subproject skeleton
2. Implement and test all types
3. Publish to local Maven or Gradle composite build for other modules to consume
4. No database migration required (pure domain types)
5. No deployment artifact — consumed as a compile-time dependency
