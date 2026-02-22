# FR-001: Shared Domain Primitives — Implementation Summary

## Feature Summary

Implemented the `shared` Gradle subproject containing pure Kotlin domain primitives used across all bounded contexts. No Spring dependencies; pure Kotlin stdlib only.

## Changes Made

- Created `shared/` Gradle subproject with Kotlin JVM plugin and JUnit 5
- Implemented `Money` value type with scale=4 BigDecimal arithmetic and currency enforcement
- Implemented `Percentage` inline value class with 0–100 range validation
- Created `Currency` enum (USD, EUR, GBP, CAD)
- Implemented four ID inline value classes: `SkuId`, `VendorId`, `OrderId`, `ExperimentId`
- Defined `DomainEvent` sealed interface with `occurredAt: Instant`
- Implemented 10 domain event data classes covering all inter-module communication needs
- Added unit tests for Money and Percentage

## Files Modified

| File | Description |
|---|---|
| `shared/build.gradle.kts` | Gradle subproject build — Kotlin JVM, JUnit 5, no Spring |
| `shared/src/main/kotlin/com/autoshipper/shared/money/Currency.kt` | Currency enum (USD, EUR, GBP, CAD) |
| `shared/src/main/kotlin/com/autoshipper/shared/money/Money.kt` | Money data class with arithmetic and CurrencyMismatchException |
| `shared/src/main/kotlin/com/autoshipper/shared/money/Percentage.kt` | Percentage inline class with 0–100 validation |
| `shared/src/main/kotlin/com/autoshipper/shared/identity/SkuId.kt` | SkuId inline value class |
| `shared/src/main/kotlin/com/autoshipper/shared/identity/VendorId.kt` | VendorId inline value class |
| `shared/src/main/kotlin/com/autoshipper/shared/identity/OrderId.kt` | OrderId inline value class |
| `shared/src/main/kotlin/com/autoshipper/shared/identity/ExperimentId.kt` | ExperimentId inline value class |
| `shared/src/main/kotlin/com/autoshipper/shared/events/DomainEvent.kt` | DomainEvent sealed interface |
| `shared/src/main/kotlin/com/autoshipper/shared/events/SkuStateChanged.kt` | SKU state transition event |
| `shared/src/main/kotlin/com/autoshipper/shared/events/CostEnvelopeVerified.kt` | Cost envelope verification event |
| `shared/src/main/kotlin/com/autoshipper/shared/events/SkuTerminated.kt` | SKU termination event |
| `shared/src/main/kotlin/com/autoshipper/shared/events/PricingSignal.kt` | Pricing signal sealed class |
| `shared/src/main/kotlin/com/autoshipper/shared/events/PricingDecision.kt` | Pricing decision sealed class |
| `shared/src/main/kotlin/com/autoshipper/shared/events/VendorSlaBreached.kt` | Vendor SLA breach event |
| `shared/src/main/kotlin/com/autoshipper/shared/events/OrderFulfilled.kt` | Order fulfillment event |
| `shared/src/main/kotlin/com/autoshipper/shared/events/ComplianceCleared.kt` | Compliance cleared event |
| `shared/src/main/kotlin/com/autoshipper/shared/events/ComplianceFailed.kt` | Compliance failed event |
| `shared/src/test/kotlin/com/autoshipper/shared/MoneyTest.kt` | Money arithmetic unit tests |
| `shared/src/test/kotlin/com/autoshipper/shared/PercentageTest.kt` | Percentage bounds unit tests |

## Testing Completed

- `MoneyTest`: addition, subtraction, cross-currency exception, `marginAgainst`, scale enforcement
- `PercentageTest`: valid bounds (0, 50, 100), invalid bounds (-1, 101), `toDecimalFraction()`
- Run with: `./gradlew :shared:test`

## Deployment Notes

- No database migration — pure compile-time dependency
- All bounded context modules consume `shared` via `implementation(project(":shared"))`
- No Spring context required; can be unit-tested without application startup
