# FR-003: Catalog SKU Lifecycle — Implementation Plan

## Technical Design

The `catalog` module owns the `Sku` aggregate. `SkuState` is a Kotlin sealed class stored via a discriminator column in PostgreSQL. State transitions go through `SkuStateMachine` which validates the transition, persists the new state, logs to the audit table, and publishes a `SkuStateChanged` event.

```
catalog/src/main/kotlin/com/autoshipper/catalog/
├── domain/
│   ├── Sku.kt                    (aggregate root)
│   ├── SkuState.kt               (sealed class)
│   ├── SkuStateMachine.kt        (transition validation + event emission)
│   ├── TerminationReason.kt      (enum)
│   └── InvalidSkuTransitionException.kt
├── handler/
│   └── SkuController.kt          (REST endpoints)
├── domain/service/
│   └── SkuService.kt             (use cases: create, transition, query)
├── proxy/
│   └── (external adapters — empty in this FR)
└── config/
    └── CatalogConfig.kt
```

## Architecture Decisions

- **`SkuState` as sealed class, stored as VARCHAR discriminator**: Readable in SQL queries, type-safe in Kotlin. Each state variant maps to a string value.
- **`SkuStateMachine` holds the transition map**: Centralizes all transition logic — no scattered `if (state == X) state = Y` throughout the codebase.
- **Optimistic locking via `@Version`**: Prevents concurrent state corruption if two threads attempt transitions simultaneously.
- **Audit table separate from entity**: `sku_state_history` is append-only and never modified, supporting full audit trail.
- **Spring `ApplicationEventPublisher` for `SkuStateChanged`**: Modules subscribe to events without direct coupling to the catalog module.

## Layer-by-Layer Implementation

### Domain Layer
- `SkuState`: sealed class with 8 variants. `Terminated` carries `TerminationReason`.
- `SkuStateMachine`: validates transition using a `Map<SkuState, Set<SkuState>>` transition table. Throws `InvalidSkuTransitionException` for invalid transitions.
- `Sku`: JPA entity with `@Version`, `skuId`, `name`, `category`, `currentState`.

### Handler Layer
- `SkuController`: `POST /api/skus`, `GET /api/skus/{id}`, `GET /api/skus?state=LISTED`

### Common Layer
- `SkuStateHistory`: JPA entity for audit log (skuId, fromState, toState, transitionedAt)
- Flyway migration: `V2__catalog_skus.sql`

## Task Breakdown

### Domain Layer
- [x] Define `TerminationReason` enum with all 7 reasons
- [x] Implement `SkuState` sealed class with 8 variants (`Terminated` data class carrying `TerminationReason`)
- [x] Build `SkuStateMachine` with full valid transition map
- [x] Add `InvalidSkuTransitionException` (extends `RuntimeException`)
- [x] Implement `Sku` JPA entity with `@Version`, `@Id SkuId`, state discriminator column
- [x] Implement `SkuService` with `create(name, category)`, `transition(skuId, newState)`, `findById`, `findByState`
- [x] Publish `SkuStateChanged` event on every valid transition via `ApplicationEventPublisher`

### Handler Layer
- [x] Implement `SkuController` with `POST /api/skus` (create SKU in Ideation)
- [x] Implement `GET /api/skus/{id}` returning SKU detail
- [x] Implement `GET /api/skus?state={state}` returning filtered list
- [x] Add request/response DTOs: `CreateSkuRequest`, `SkuResponse`

### Persistence (Common Layer)
- [x] Write `V2__catalog_skus.sql` migration (skus table, sku_state_history table)
- [x] Implement `SkuRepository` extending `JpaRepository`
- [x] Implement `SkuStateHistoryRepository` for audit writes
- [x] Write audit log entry on every state transition

## Testing Strategy

- Unit test `SkuStateMachine`: all valid transitions pass, all invalid transitions throw
- Unit test `TerminationReason` coverage in `Terminated` state
- Integration test (Testcontainers Postgres): create SKU → transition through full valid lifecycle
- Integration test: invalid transition attempt returns 422 with error detail
- API test: `POST /api/skus`, `GET /api/skus/{id}`, `GET /api/skus?state=LISTED`

## Rollout Plan

1. Write Flyway migration `V2__catalog_skus.sql`
2. Implement domain layer (state machine first, then entity)
3. Implement service layer
4. Add REST handler
5. Run integration tests with Testcontainers
