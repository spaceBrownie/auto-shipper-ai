# FR-003: Catalog SKU Lifecycle

## Problem Statement

The catalog module must enforce a strict, auditable SKU lifecycle. SKUs must move through well-defined states — and the system must make invalid transitions structurally impossible, not merely convention-based. State changes must emit domain events consumed by downstream modules.

## Business Requirements

- A SKU must exist in exactly one state at any time: `Ideation`, `ValidationPending`, `CostGating`, `StressTesting`, `Listed`, `Paused`, `Scaled`, or `Terminated`
- State transitions must be validated — invalid transitions throw domain exceptions and are never silently ignored
- Every state transition must emit a `SkuStateChanged` domain event
- Termination must carry a typed reason: `MARGIN_BELOW_THRESHOLD`, `COST_EXCEEDS_PRICE_CEILING`, `HIGH_REFUND_RATE`, `HIGH_CHARGEBACK_RATE`, `VENDOR_SLA_BREACH`, `CAC_INSTABILITY`, `DYNAMIC_PRICING_INEFFECTIVE`
- SKUs in `Terminated` state cannot be reactivated — termination is permanent
- The `Listed` state requires a `CostEnvelope.Verified` — a SKU cannot reach `Listed` without one (enforced at the type level, not runtime check)
- SKU metadata (name, category, description, target market) must be storable and retrievable

## Success Criteria

- `SkuState` sealed class with all 8 states is defined and persisted
- Valid transition map is codified and enforced
- Attempting an invalid transition throws `InvalidSkuTransitionException`
- Every valid transition publishes a `SkuStateChanged` event via `ApplicationEventPublisher`
- `SkuRepository` persists and retrieves SKUs with their current state
- REST endpoints: `POST /api/skus` (create), `GET /api/skus/{id}`, `GET /api/skus` (list by state)
- Integration test verifies full lifecycle from Ideation → Listed → Terminated

## Non-Functional Requirements

- SKU state stored as a discriminated column in PostgreSQL — not serialized JSON
- State transition history logged to an audit table (`sku_state_history`)
- Optimistic locking on SKU entity to prevent concurrent state corruption

## Dependencies

- FR-001 (shared-domain-primitives) — `SkuId`, `DomainEvent`, `SkuStateChanged`
- FR-002 (project-bootstrap) — Spring Boot + JPA + Flyway running
