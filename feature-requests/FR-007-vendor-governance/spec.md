# FR-007: Vendor Governance

## Problem Statement

The system's fulfillment reliability depends entirely on vendor performance. Without structured governance, a single underperforming vendor can corrupt multiple SKUs' margins and customer experience simultaneously. Vendors must be pre-qualified before any SKU activation and continuously monitored thereafter, with automated consequences for SLA breaches.

## Business Requirements

- Before a vendor is activated for any SKU, all of the following must be confirmed:
  - SLA confirmed in writing (stored as an artifact)
  - Defect rate documented and within tolerance
  - Scalability ceiling confirmed
  - Fulfillment time windows confirmed
  - Replacement and refund policies confirmed
- A vendor reliability score must be calculated and maintained, factoring: on-time rate, defect rate, breach history, responsiveness
- Continuous SLA monitoring via scheduled checks against vendor-reported fulfillment data
- If a vendor's SLA breach rate exceeds the tolerance threshold, all associated SKUs are automatically paused
- A `VendorSlaBreached` domain event is emitted on breach detection
- Resolution is required before SKUs associated with that vendor can be reinstated

## Success Criteria

- `Vendor` aggregate with pre-activation checklist fields persisted in `vendors` table
- `VendorReliabilityScore` calculated from on-time rate, defect rate, breach count, responsiveness
- `VendorActivationService` enforces all 5 pre-activation requirements
- `VendorSlaMonitor` runs on a schedule (`@Scheduled`) to detect breach conditions
- On breach: emits `VendorSlaBreached` event → all linked SKUs auto-paused
- `VendorSlaBreached` consumed by catalog module to pause affected SKUs
- REST: `POST /api/vendors` (register), `GET /api/vendors/{id}`, `POST /api/vendors/{id}/activate`, `GET /api/vendors/{id}/score`
- Integration test: vendor breach triggers multi-SKU auto-pause

## Non-Functional Requirements

- SLA monitoring scheduled every 15 minutes via `@Scheduled`
- Vendor breach history stored in `vendor_breach_log` table
- Reliability score recomputed on every new data point (order fulfilled, breach logged)
- Vendor records soft-deleted only — never hard-deleted (audit requirement)

## Dependencies

- FR-001 (shared-domain-primitives) — `VendorId`, `VendorSlaBreached` event
- FR-002 (project-bootstrap) — Spring Boot, `@Scheduled`
- FR-003 (catalog-sku-lifecycle) — vendor breach → SKU auto-pause via event
