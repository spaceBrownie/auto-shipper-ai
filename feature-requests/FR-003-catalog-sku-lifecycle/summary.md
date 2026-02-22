# FR-003: Catalog SKU Lifecycle — Implementation Summary

## Feature Summary

Implemented the `catalog` module with full SKU lifecycle management: state machine validation, JPA persistence with optimistic locking, append-only audit log, Spring event publication, and REST endpoints.

## Changes Made

- Implemented `SkuState` sealed class with 8 variants and string discriminator mapping
- Built `SkuStateMachine` with explicit valid transition graph (all terminal-state rules enforced)
- Created `Sku` JPA entity with `@Version` optimistic locking and state stored as VARCHAR
- Implemented `SkuService` with create, transition, find-by-id, find-by-state, and find-all operations
- Every state transition publishes `SkuStateChanged`; terminations also publish `SkuTerminated`
- Implemented `SkuController` REST endpoints at `/api/skus`
- Wrote `V2__catalog_skus.sql` Flyway migration for `skus` and `sku_state_history` tables

## Files Modified

| File | Description |
|---|---|
| `catalog/src/main/kotlin/com/autoshipper/catalog/domain/TerminationReason.kt` | 7-value termination reason enum |
| `catalog/src/main/kotlin/com/autoshipper/catalog/domain/SkuState.kt` | Sealed class with 8 states and discriminator mapping |
| `catalog/src/main/kotlin/com/autoshipper/catalog/domain/SkuStateMachine.kt` | Transition graph enforcer, throws on invalid moves |
| `catalog/src/main/kotlin/com/autoshipper/catalog/domain/InvalidSkuTransitionException.kt` | Domain exception for illegal transitions |
| `catalog/src/main/kotlin/com/autoshipper/catalog/domain/Sku.kt` | JPA entity with @Version, state discriminator, applyTransition() |
| `catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/SkuService.kt` | Use cases: create, transition, findById, findByState, findAll |
| `catalog/src/main/kotlin/com/autoshipper/catalog/persistence/SkuStateHistory.kt` | Append-only audit log JPA entity |
| `catalog/src/main/kotlin/com/autoshipper/catalog/persistence/SkuRepository.kt` | JpaRepository with findByCurrentStateDiscriminator |
| `catalog/src/main/kotlin/com/autoshipper/catalog/persistence/SkuStateHistoryRepository.kt` | JpaRepository for audit writes |
| `catalog/src/main/kotlin/com/autoshipper/catalog/handler/dto/CreateSkuRequest.kt` | Request DTO with Bean Validation |
| `catalog/src/main/kotlin/com/autoshipper/catalog/handler/dto/SkuResponse.kt` | Response DTO with factory method |
| `catalog/src/main/kotlin/com/autoshipper/catalog/handler/SkuController.kt` | REST controller: POST, GET by id, GET with state filter |
| `app/src/main/resources/db/migration/V2__catalog_skus.sql` | skus + sku_state_history tables with indexes |

## Testing Completed

- Unit tests: `SkuStateMachine` — all valid transitions pass, all invalid transitions throw `InvalidSkuTransitionException`
- Integration tests (Testcontainers): create SKU → transition through full valid lifecycle → verify state persisted
- API tests: `POST /api/skus` returns 201, `GET /api/skus/{id}` returns SKU detail, `GET /api/skus?state=LISTED` returns filtered list
- Run with: `./gradlew :catalog:test` and `./gradlew :app:test`

## Deployment Notes

- Flyway V2 migration runs automatically on startup after V1 baseline
- Optimistic locking via `@Version` prevents concurrent state corruption
- `sku_state_history` is append-only — never modified after insert
- State transitions publish Spring `ApplicationEvent` — other modules subscribe without coupling to catalog
- All auto-shutdown triggers (margin floor, refund rate, etc.) will call `SkuService.transition()` with `SkuState.Terminated(reason)` in subsequent FRs
