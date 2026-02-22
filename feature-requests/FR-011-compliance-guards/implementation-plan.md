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

- **All checks run in parallel**: Using Kotlin coroutines (`async/await` or `coroutineScope`), all 4 checks are launched concurrently. Total compliance check time is bounded by the slowest single check, not the sum.
- **`ComplianceCheckResult` sealed class**: `Cleared` and `Failed(reason)` — the result type is exhaustive and pattern-matchable.
- **LLM-backed analysis is configurable and optional**: `ClaimsCheckService` and `IpCheckService` can be backed by a `ClaudeComplianceAdapter` (calling Claude API) or a rule-based fallback. Toggled via `compliance.llm.enabled` in config.
- **Sanctions list cached daily**: The sourcing check uses a locally-cached sanctions list refreshed at midnight. Not a live API call per SKU (too slow and rate-limited).
- **Compliance check is a catalog listener**: The catalog module emits a `SkuReadyForComplianceCheck` event on SKU creation. `ComplianceOrchestrator` listens and runs checks automatically — no manual trigger required.

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
- `POST /api/skus/{id}/compliance-check` — manual trigger
- `GET /api/skus/{id}/compliance` — compliance status and history

## Task Breakdown

### Domain Layer
- [ ] Implement `ComplianceFailureReason` enum with 5 reasons
- [ ] Implement `ComplianceCheckResult` sealed class (`Cleared`, `Failed`)
- [ ] Implement `ComplianceAuditRecord` JPA entity (skuId, checkType, result, reason, checkedAt, payload)

### Domain Service
- [ ] Implement `IpCheckService.check(skuId, productName)` (rule-based: flag known trademarked terms)
- [ ] Implement `ClaimsCheckService.check(skuId, description)` (rule-based: flag regulated/superlative language patterns)
- [ ] Implement `ProcessorCheckService.check(skuId, category)` (validate against locally-cached Stripe prohibited categories list)
- [ ] Implement `SourcingCheckService.check(skuId, vendorId)` (validate vendor against locally-cached sanctions list)
- [ ] Implement `ComplianceOrchestrator` running all 4 checks concurrently with `coroutineScope { async {} }`
- [ ] Emit `ComplianceCleared` if all checks pass
- [ ] Emit `ComplianceFailed` with reason if any check fails
- [ ] Write audit record for every check run (both pass and fail)
- [ ] Implement `CatalogComplianceListener` in catalog module: on `ComplianceCleared` → transition SKU to `ValidationPending`; on `ComplianceFailed` → terminate SKU

### Proxy Layer
- [ ] Implement `ClaudeComplianceAdapter` calling Claude API for IP/claims analysis (toggled by `compliance.llm.enabled`)
- [ ] Implement `SanctionsListCache` loading from local file, refreshing daily at midnight

### Handler Layer
- [ ] Implement `POST /api/skus/{id}/compliance-check` manual trigger
- [ ] Implement `GET /api/skus/{id}/compliance` returning latest result and history
- [ ] Add `ComplianceStatusResponse` DTO

### Config Layer
- [ ] Define `ComplianceConfig` `@ConfigurationProperties` (`llm.enabled`, `sanctions-list.path`, `prohibited-categories.path`)
- [ ] Add compliance config to `application.yml`

### Persistence (Common Layer)
- [ ] Write `V10__compliance.sql` migration (compliance_audit table)
- [ ] Implement `ComplianceAuditRepository`

## Testing Strategy

- Unit test each check service: pass and fail cases independently
- Unit test `ComplianceOrchestrator`: any single failing check → `ComplianceFailed`; all pass → `ComplianceCleared`
- Unit test: checks run concurrently (verify via mock timing or parallel assertion)
- Integration test: `ComplianceFailed` → SKU terminated; `ComplianceCleared` → SKU advances to `ValidationPending`
- Test `SanctionsListCache` refresh at midnight

## Rollout Plan

1. Write `V10__compliance.sql`
2. Implement rule-based check services
3. Implement `ComplianceOrchestrator` with concurrent execution
4. Implement `CatalogComplianceListener` in catalog module
5. Implement `ClaudeComplianceAdapter` (optional, feature-flagged)
6. Add REST handler
