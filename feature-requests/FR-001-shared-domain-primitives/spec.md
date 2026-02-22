# FR-001: Shared Domain Primitives

## Problem Statement

The commerce engine requires a set of foundational value types and domain events that must be shared across all bounded contexts. Without a well-defined shared kernel, modules will independently define monetary values using raw primitives (`Double`, `BigDecimal`), leading to currency bugs, precision errors, and type-unsafe domain logic. Every other module depends on this foundation before any business logic can be implemented.

## Business Requirements

- All monetary values in the system must carry both an amount and an explicit currency — raw `Double` or `BigDecimal` are prohibited in domain models
- Arithmetic on `Money` must enforce same-currency invariants and throw on currency mismatch
- Margin calculations must be expressible as typed `Percentage` values, not bare numbers
- All domain identifiers (`SkuId`, `VendorId`, `OrderId`) must be typed value classes — not raw strings or UUIDs
- Domain events must be defined in the shared module and published via Spring `ApplicationEventPublisher` so modules remain decoupled
- The `shared` module must have zero dependencies on any other bounded context

## Success Criteria

- `Money` value type exists with `plus`, `minus`, `times`, `marginAgainst` operations and currency-enforcement
- `Percentage` value type exists with range validation and arithmetic
- `SkuId`, `VendorId`, `OrderId`, `ExperimentId` value classes exist
- A `DomainEvent` sealed interface (or abstract class) exists with a `occurredAt: Instant` field
- All core domain events are defined: `SkuStateChanged`, `CostEnvelopeVerified`, `SkuTerminated`, `PricingSignalReceived`, `VendorSlaBreached`, `OrderFulfilled`
- Zero compilation errors when imported by other modules
- Unit tests cover currency mismatch, precision rounding, percentage bounds

## Non-Functional Requirements

- No external library dependencies beyond Kotlin stdlib and `java.math`
- All types must be `data class` or `@JvmInline value class` for structural equality
- Thread-safe (immutable value semantics)
- Kotlin `BigDecimal` scale must be fixed at 4 decimal places for monetary amounts

## Dependencies

- None — this module is the root of the dependency graph
