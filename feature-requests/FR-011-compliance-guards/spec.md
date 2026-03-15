# FR-011: Compliance Guards

## Problem Statement

Long-term viability overrides short-term revenue in every case. The system must proactively prevent IP infringement, misleading product claims, regulatory violations, payment processor acceptable-use violations, and gray-market sourcing before a SKU is listed. Compliance checks must be integrated into the SKU lifecycle as a hard gate — not a post-launch audit.

## Business Requirements

- No SKU may advance past `Ideation` without passing compliance pre-checks
- IP infringement check: product name and description must be screened against known trademark databases
- Misleading claims check: product claims must be flagged if they contain unsubstantiated superlatives or regulated health/safety language
- Payment processor acceptable-use check: SKU category must be validated against Stripe's prohibited and restricted business list
- Sourcing compliance: vendor must be verified as not appearing on gray-market or sanctioned supplier lists; must be a **direct supplier** (manufacturer, wholesaler, or authorized distributor) — never a marketplace reseller (PM-007 AD-9)
- Consumer protection: product listing must conform to jurisdiction-specific disclosure requirements
- Compliance check results must be stored immutably and be auditable, with a `run_id` grouping records from the same check execution
- A `ComplianceCleared` event allows the SKU to proceed; a `ComplianceFailed` event with typed reason blocks advancement

## Success Criteria

- `ComplianceCheckResult` sealed class: `Cleared`, `Failed(reason: ComplianceFailureReason)`
- `ComplianceFailureReason` enum: `IP_INFRINGEMENT`, `MISLEADING_CLAIMS`, `PROCESSOR_PROHIBITED`, `GRAY_MARKET_SOURCE`, `RESELLER_SOURCE`, `DISCLOSURE_VIOLATION`
- `IpCheckService`, `ClaimsCheckService`, `ProcessorCheckService`, `SourcingCheckService` implementations
- `ComplianceOrchestrator` runs all 4 checks concurrently via coroutines and emits `ComplianceCleared` or `ComplianceFailed`
- Failed compliance blocks SKU advancement and records reason in `compliance_audit` table
- `POST /api/compliance/skus/{id}/check` triggers compliance review (PM-007 AD-2: compliance is a first-class REST resource at `/api/compliance`)
- `GET /api/compliance/skus/{id}` returns compliance status (scoped to latest run) and full audit history
- `CatalogComplianceListener` uses PM-005 double-annotation pattern (`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`)
- Unit tests: each check type passing and failing independently

## Non-Functional Requirements

- All compliance check results stored immutably in `compliance_audit` table with `run_id` for batch grouping
- Compliance auto-check is feature-flagged: `compliance.auto-check.enabled=true` (production default), `false` for development (PM-007 AD-3)
- When auto-check is enabled, `ComplianceOrchestrator` listens for `SkuReadyForComplianceCheck` events and runs checks automatically on SKU creation
- All 4 checks run concurrently via `coroutineScope { async {} }` — no JPA writes inside async blocks; audit writes and event publication happen in the `@Transactional` method outside coroutine scope (PM-007 AD-6)
- IP and claims checks may be backed by LLM-based analysis (configurable via `compliance.llm.enabled`) or rule-based logic
- Sanctions list checks use a cached, daily-refreshed list (not live API on every request)

## Dependencies

- FR-001 (shared-domain-primitives) — `SkuId`, `ComplianceCleared`, `ComplianceFailed`, `SkuReadyForComplianceCheck` events
- FR-002 (project-bootstrap) — Spring Boot, environment config
- FR-003 (catalog-sku-lifecycle) — compliance gate integrated into `Ideation → ValidationPending` transition