> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/compliance/src/...` instead of `compliance/src/...`.

# FR-011: Compliance Guards — Implementation Plan

## Technical Design

The `compliance` module runs pre-listing checks on every SKU before it can leave `Ideation`. `ComplianceOrchestrator` calls all 4 check services in parallel, aggregates results, and emits `ComplianceCleared` or `ComplianceFailed`. All results are immutably stored in an audit table.

```
compliance/src/main/kotlin/com/autoshipper/compliance/
├── domain/
│   ├── ComplianceCheckResult.kt  (sealed class)
│   └── ComplianceFailureReason.kt (enum)
├── domain/service/
│   ├── ComplianceOrchestrator.kt (runs all checks, emits event)
│   ├── IpCheckService.kt
│   ├── ClaimsCheckService.kt
│   ├── ProcessorCheckService.kt
│   └── SourcingCheckService.kt
├── proxy/
│   └── ClaudeComplianceAdapter.kt (optional LLM-backed claims/IP analysis)
├── handler/
│   └── ComplianceController.kt
└── config/
    └── ComplianceConfig.kt
```

## Architecture Decisions

- **All checks run in parallel**: Using Kotlin coroutines (`coroutineScope { async {} }`), all 4 checks are launched concurrently. Total compliance check time is bounded by the slowest single check, not the sum. **First use of coroutines in this codebase** — Spring's `@Transactional` does NOT propagate across coroutine context switches. The `ComplianceOrchestrator` must therefore structure its work so that (a) the parallel checks produce pure results (no JPA writes inside `async` blocks), and (b) the audit write and event publication happen outside the coroutine scope in a normal `@Transactional` method. The project already has `spring.threads.virtual.enabled=true` (virtual threads) — for the rule-based checks (no I/O) this is sufficient; coroutines add value primarily when the LLM-backed adapter (`ClaudeComplianceAdapter`) is enabled.
- **`ComplianceCheckResult` sealed class**: `Cleared` and `Failed(reason)` — the result type is exhaustive and pattern-matchable.
- **LLM-backed analysis is configurable and optional**: `ClaimsCheckService` and `IpCheckService` can be backed by a `ClaudeComplianceAdapter` (calling Claude API) or a rule-based fallback. Toggled via `compliance.llm.enabled` in config.
- **Sanctions list cached daily**: The sourcing check uses a locally-cached sanctions list refreshed at midnight. Not a live API call per SKU (too slow and rate-limited).
- **Compliance check is triggered by a catalog event, feature-flagged**: The catalog module emits a `SkuReadyForComplianceCheck` event on SKU creation. `ComplianceOrchestrator` listens and runs checks automatically when `compliance.auto-check.enabled=true` (default). When false, the check must be triggered via `POST /api/compliance/skus/{id}/check`. The flag exists to allow manual stepping during development; in production it should always be `true`.
- **Compliance is a first-class REST resource at `/api/compliance`**: Compliance check endpoints live under `/api/compliance/skus/{id}` rather than nested under `/api/skus`. This matches the module-level convention used by `/api/capital`, `/api/portfolio`, and `/api/vendor`.

## Layer-by-Layer Implementation

### Domain Layer
- `ComplianceCheckResult`: sealed class — `Cleared(skuId, checkedAt)` and `Failed(skuId, reason, checkedAt)`
- `ComplianceFailureReason`: enum — IP_INFRINGEMENT, MISLEADING_CLAIMS, PROCESSOR_PROHIBITED, GRAY_MARKET_SOURCE, DISCLOSURE_VIOLATION

### Domain Service
- `ComplianceOrchestrator`: runs 4 checks concurrently, combines results, emits `ComplianceCleared` or `ComplianceFailed`, writes audit record
- `IpCheckService.check(skuId, productName)`: rule-based or LLM — returns `ComplianceCheckResult`
- `ClaimsCheckService.check(skuId, productDescription)`: rule-based or LLM
- `ProcessorCheckService.check(skuId, category)`: validates against Stripe prohibited list (locally cached)
- `SourcingCheckService.check(skuId, vendorId)`: checks vendor against sanctions list (locally cached, daily refresh)

### Proxy Layer
- `ClaudeComplianceAdapter`: calls Claude API for nuanced IP/claims analysis (enabled via config)

### Handler Layer
- `POST /api/compliance/skus/{id}/check` — manual trigger (used when `compliance.auto-check.enabled=false`)
- `GET /api/compliance/skus/{id}` — compliance status and audit history for a SKU

## Task Breakdown

### Shared Module (prerequisite)
- [x] Add `SkuReadyForComplianceCheck` event to `modules/shared/src/main/kotlin/com/autoshipper/shared/events/` (skuId, productName, productDescription, category, vendorId, occurredAt)
- [x] Update `ComplianceFailed` event in `modules/shared/src/main/kotlin/com/autoshipper/shared/events/ComplianceFailed.kt`: change `reason: String` → `reason: ComplianceFailureReason` — requires `ComplianceFailureReason` to be defined in `shared` (move enum there) or keep in `compliance` and use `.name` string serialisation. Recommended: keep enum in `compliance` and store `.name` in the event for now (avoids shared coupling to compliance internals)

### Config Layer (prerequisite)
- [x] Add `kotlinx-coroutines-core` dependency to `modules/compliance/build.gradle.kts` (required for `coroutineScope { async {} }` parallel check execution)

### Domain Layer
- [x] Implement `ComplianceFailureReason` enum with 5 reasons
- [x] Implement `ComplianceCheckResult` sealed class (`Cleared`, `Failed`)
- [x] Implement `ComplianceAuditRecord` JPA entity (skuId, checkType, result, reason, checkedAt, payload)

### Domain Service
- [x] Implement `IpCheckService.check(skuId, productName)` (rule-based: flag known trademarked terms)
- [x] Implement `ClaimsCheckService.check(skuId, description)` (rule-based: flag regulated/superlative language patterns)
- [x] Implement `ProcessorCheckService.check(skuId, category)` (validate against locally-cached Stripe prohibited categories list)
- [x] Implement `SourcingCheckService.check(skuId, vendorId)` (validate vendor against locally-cached sanctions list)
- [x] Implement `ComplianceOrchestrator` running all 4 checks concurrently with `coroutineScope { async {} }`
- [x] `ComplianceOrchestrator` listens for `SkuReadyForComplianceCheck` only when `compliance.auto-check.enabled=true`; otherwise the same `runChecks(skuId)` method is called by the REST handler directly
- [x] Emit `ComplianceCleared` if all checks pass
- [x] Emit `ComplianceFailed` with reason if any check fails
- [x] Write audit record for every check run (both pass and fail)
- [x] Implement `CatalogComplianceListener` in `modules/catalog` — **MANDATORY: use the established double-annotation pattern from PM-005**:
  ```kotlin
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun onComplianceCleared(event: ComplianceCleared) { ... }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun onComplianceFailed(event: ComplianceFailed) { ... }
  ```
  - `ComplianceCleared` → transition SKU to `ValidationPending`
  - `ComplianceFailed` → terminate SKU with `TerminationReason.COMPLIANCE_VIOLATION`
  - Omitting either annotation causes silent data loss or transaction poisoning (see [PM-005](../../docs/postmortems/PM-005-cross-module-listener-transaction-safety.md))
  - Note: `POST /api/skus/{id}/state` currently allows manual state transitions as a development escape hatch. Once `CatalogComplianceListener` is wired, evaluate whether to restrict or remove the manual transition to `VALIDATION_PENDING` so the compliance gate cannot be bypassed in production.

### Proxy Layer
- [x] Implement `ClaudeComplianceAdapter` calling Claude API for IP/claims analysis (toggled by `compliance.llm.enabled`)
- [x] Implement `SanctionsListCache` loading from local file, refreshing daily at midnight

### Handler Layer
- [x] Implement `POST /api/compliance/skus/{id}/check` — manual trigger; calls `ComplianceOrchestrator.runChecks(skuId)` directly
- [x] Implement `GET /api/compliance/skus/{id}` — returning latest `ComplianceCheckResult` and full audit history
- [x] Add `ComplianceStatusResponse` DTO (latestResult, auditHistory: List<AuditEntry>)

### Config Layer
- [x] Define `ComplianceConfig` `@ConfigurationProperties` (`llm.enabled`, `auto-check.enabled=true`, `sanctions-list.path`, `prohibited-categories.path`)
- [x] Add compliance config to `application.yml` with comment: `auto-check.enabled=false` disables automatic trigger on SKU creation (development use only)

### Persistence (Common Layer)
- [x] Write `V15__compliance.sql` migration (compliance_audit table)
- [x] Implement `ComplianceAuditRepository`

## Testing Strategy

- Unit test each check service: pass and fail cases independently
- Unit test `ComplianceOrchestrator`: any single failing check → `ComplianceFailed`; all pass → `ComplianceCleared`
- Unit test: checks run concurrently (verify via mock timing or parallel assertion)
- Integration test: `ComplianceFailed` → SKU terminated; `ComplianceCleared` → SKU advances to `ValidationPending`
- Integration test: `CatalogComplianceListener` transaction isolation — if `skuService.transition()` throws, verify audit record is NOT lost (tests the `AFTER_COMMIT` + `REQUIRES_NEW` pattern; this is the failure scenario from PM-005)
- Test `SanctionsListCache` refresh at midnight

## Rollout Plan

1. Add `SkuReadyForComplianceCheck` event to `shared` module; update `ComplianceFailed.reason` type
2. Add `kotlinx-coroutines-core` dependency to `modules/compliance/build.gradle.kts`
3. Write `V15__compliance.sql`
4. Implement rule-based check services
5. Implement `ComplianceOrchestrator` with concurrent execution; wire auto-check flag (`compliance.auto-check.enabled=false` during development)
6. Add REST handler (`POST /api/compliance/skus/{id}/check`, `GET /api/compliance/skus/{id}`)
7. Implement `CatalogComplianceListener` in catalog module; flip `compliance.auto-check.enabled=true`; evaluate restricting manual `VALIDATION_PENDING` transition
8. Implement `ClaudeComplianceAdapter` (optional, feature-flagged via `compliance.llm.enabled`)
