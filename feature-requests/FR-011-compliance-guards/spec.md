# FR-011: Compliance Guards

## Problem Statement

Long-term viability overrides short-term revenue in every case. The system must proactively prevent IP infringement, misleading product claims, regulatory violations, payment processor acceptable-use violations, and gray-market sourcing before a SKU is listed. Compliance checks must be integrated into the SKU lifecycle as a hard gate — not a post-launch audit.

## Business Requirements

- No SKU may advance past `Ideation` without passing compliance pre-checks
- IP infringement check: product name and description must be screened against known trademark databases
- Misleading claims check: product claims must be flagged if they contain unsubstantiated superlatives or regulated health/safety language
- Payment processor acceptable-use check: SKU category must be validated against Stripe's prohibited and restricted business list
- Sourcing compliance: vendor must be verified as not appearing on gray-market or sanctioned supplier lists
- Consumer protection: product listing must conform to jurisdiction-specific disclosure requirements
- Compliance check results must be stored and auditable
- A `ComplianceCleared` event allows the SKU to proceed; a `ComplianceFailed` event with typed reason blocks advancement

## Success Criteria

- `ComplianceCheckResult` sealed class: `Cleared`, `Failed(reason: ComplianceFailureReason)`
- `ComplianceFailureReason` enum: `IP_INFRINGEMENT`, `MISLEADING_CLAIMS`, `PROCESSOR_PROHIBITED`, `GRAY_MARKET_SOURCE`, `DISCLOSURE_VIOLATION`
- `IpCheckService`, `ClaimsCheckService`, `ProcessorCheckService`, `SourcingCheckService` implementations
- `ComplianceOrchestrator` runs all checks and emits `ComplianceCleared` or `ComplianceFailed`
- Failed compliance blocks SKU advancement and records reason in `compliance_audit` table
- `POST /api/skus/{id}/compliance-check` triggers compliance review
- `GET /api/skus/{id}/compliance` returns compliance status and history
- Unit tests: each check type passing and failing independently

## Non-Functional Requirements

- All compliance check results stored immutably in `compliance_audit` table with full payload
- Compliance checks run synchronously before any state transition out of `Ideation`
- IP and claims checks may be backed by LLM-based analysis (configurable provider) or rule-based logic
- Sanctions list checks use a cached, daily-refreshed list (not live API on every request)

## Dependencies

- FR-001 (shared-domain-primitives) — `SkuId`, `ComplianceCleared`, `ComplianceFailed` events
- FR-002 (project-bootstrap) — Spring Boot, environment config
- FR-003 (catalog-sku-lifecycle) — compliance gate integrated into `Ideation → ValidationPending` transition
