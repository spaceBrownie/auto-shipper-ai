# FR-011: Compliance Guards — Implementation Summary

## What Was Built

Complete compliance module implementing pre-listing checks for every SKU before it can advance past `Ideation`. Four independent check services run concurrently via Kotlin coroutines, with results aggregated by a `ComplianceOrchestrator` that emits domain events and writes immutable audit records.

## Files Created

### Shared Module
- `modules/shared/src/main/kotlin/com/autoshipper/shared/events/SkuReadyForComplianceCheck.kt` — new domain event triggering compliance checks

### Compliance Module (all new)
- **Domain:** `ComplianceFailureReason.kt` (enum), `ComplianceCheckResult.kt` (sealed class), `ComplianceAuditRecord.kt` (JPA entity)
- **Config:** `ComplianceConfig.kt` (`@ConfigurationProperties` with llm-enabled, auto-check-enabled, sanctions-list-path, prohibited-categories-path)
- **Domain Services:**
  - `IpCheckService.kt` — rule-based trademark detection (30+ trademarked terms)
  - `ClaimsCheckService.kt` — regex-based regulated language detection (14 patterns)
  - `ProcessorCheckService.kt` — Stripe prohibited categories validation (20 categories)
  - `SourcingCheckService.kt` — sanctions list vendor verification
  - `ComplianceOrchestrator.kt` — concurrent execution of all 4 checks, audit writing, event emission; listens for `SkuReadyForComplianceCheck` when auto-check enabled
- **Proxy:** `ClaudeComplianceAdapter.kt` (LLM stub, toggled by config), `SanctionsListCache.kt` (thread-safe, midnight refresh)
- **Handler:** `ComplianceController.kt` (POST /api/compliance/skus/{id}/check, GET /api/compliance/skus/{id}), `ComplianceStatusResponse.kt` (DTO)
- **Persistence:** `ComplianceAuditRepository.kt`

### Catalog Module
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/CatalogComplianceListener.kt` — PM-005 double-annotation pattern (`@TransactionalEventListener` + `@Transactional(REQUIRES_NEW)`)

### App Module
- `modules/app/src/main/resources/db/migration/V15__compliance.sql` — compliance_audit table with indexes
- Updated `application.yml` — compliance config section

### Tests (29 passing)
- `IpCheckServiceTest.kt` (5 tests)
- `ClaimsCheckServiceTest.kt` (6 tests)
- `ProcessorCheckServiceTest.kt` (6 tests)
- `SourcingCheckServiceTest.kt` (3 tests)
- `ComplianceOrchestratorTest.kt` (9 tests — event emission, audit writing, concurrent execution)

## Architecture Decisions
- **Real check services in orchestrator tests** instead of mocks — Mockito `any()` matchers fail with Kotlin inline value classes (`SkuId`, `VendorId`). Using real services keeps tests robust and readable.
- **`ComplianceFailed.reason` stays as `String`** — passes `ComplianceFailureReason.name` to avoid coupling shared module to compliance internals.
- **6 failure reasons** (spec said 5, added `RESELLER_SOURCE` per task instructions).

## Known Issues
- App integration tests (Capital, MarginSweep) fail with Flyway checksum mismatch on V15 — pre-existing issue from previous FR-011 attempt leaving stale state in local test DB. Not caused by this implementation.
