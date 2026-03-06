> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/vendor/src/...` instead of `vendor/src/...`.

# FR-007: Vendor Governance — Implementation Plan

## Technical Design

The `vendor` module owns the `Vendor` aggregate with a pre-activation checklist and a computed `VendorReliabilityScore`. A `VendorSlaMonitor` runs on a schedule to detect breach conditions and emit `VendorSlaBreached` events. The catalog module listens for this event and auto-pauses all associated SKUs.

```
vendor/src/main/kotlin/com/autoshipper/vendor/
├── domain/
│   ├── Vendor.kt                  (aggregate root)
│   ├── VendorActivationChecklist.kt
│   ├── VendorReliabilityScore.kt
│   └── VendorSkuAssignment.kt     (links vendors to SKUs)
├── domain/service/
│   ├── VendorActivationService.kt
│   ├── VendorSlaMonitor.kt        (@Scheduled)
│   └── VendorReliabilityScorer.kt
├── handler/
│   └── VendorController.kt
└── config/
    └── VendorMonitorConfig.kt
```

## Architecture Decisions

- **`VendorActivationChecklist` as embedded value object**: All 5 pre-activation requirements stored as fields on the vendor entity. Activation is refused if any field is null/false.
- **`VendorSlaMonitor` queries order fulfillment data**: The monitor reads from an `OrderFulfillmentSummary` view (computed from `fulfillment` module data) to calculate breach rates — no direct module coupling.
- **Soft delete only**: Vendors are never hard-deleted. Deactivated vendors retain their history for audit and reinstatement.
- **Reliability score recomputed on every data point**: Not batched. Each new order fulfillment, breach, or response event triggers a score update to keep it current.
- **`VendorSlaBreached` event consumed by catalog**: The catalog module's `VendorBreachListener` receives the event and pauses all linked SKUs — no direct call between modules.

## Layer-by-Layer Implementation

### Domain Layer
- `Vendor`: id, name, contactInfo, activationStatus, checklist, reliabilityScore, createdAt, deactivatedAt
- `VendorActivationChecklist`: slaConfirmed, defectRateDocumented, scalabilityConfirmed, fulfillmentTimesConfirmed, refundPolicyConfirmed — all must be true for activation
- `VendorReliabilityScore`: computed from onTimeRate, defectRate, breachCount, avgResponseTimeHours

### Domain Service
- `VendorActivationService.activate(vendorId)`: validates all 5 checklist items, sets status to `Active`
- `VendorReliabilityScorer.compute(vendorId): VendorReliabilityScore`: queries order and breach data, computes weighted score
- `VendorSlaMonitor.runCheck()`: queries all active vendors, calculates 30-day breach rate, emits `VendorSlaBreached` if above threshold

### Handler Layer
- `POST /api/vendors`, `GET /api/vendors/{id}`, `POST /api/vendors/{id}/activate`, `GET /api/vendors/{id}/score`

## Task Breakdown

### Domain Layer
- [x] Implement `Vendor` JPA entity (id, name, contactInfo, status: PENDING/ACTIVE/SUSPENDED, softDelete fields)
- [x] Implement `VendorActivationChecklist` as `@Embeddable` value object with 5 boolean fields
- [x] Implement `VendorReliabilityScore` value class (score 0–100, breakdown components)
- [x] Implement `VendorSkuAssignment` JPA entity (vendorId, skuId, assignedAt, status)
- [x] Define `VendorNotActivatedException` (checklist incomplete)
- [x] Define `VendorStatus` enum (PENDING, ACTIVE, SUSPENDED)

### Domain Service
- [x] Implement `VendorActivationService.activate(vendorId)` — validates checklist, transitions status
- [x] Implement `VendorReliabilityScorer.compute(vendorId)` — weighted score from onTimeRate, defectRate, breachCount, responseTime
- [x] Implement `VendorSlaMonitor` `@Scheduled(fixedRate = 900_000)` (every 15 min)
- [x] Implement breach rate calculation (breach count / total orders in rolling 30 days)
- [x] Emit `VendorSlaBreached(vendorId, affectedSkuIds, breachRate)` when threshold exceeded
- [x] Persist breach to `vendor_breach_log`
- [x] Implement `VendorBreachListener` in catalog module consuming `VendorSlaBreached` and auto-pausing linked SKUs

### Handler Layer
- [x] Implement `VendorController` with `POST /api/vendors` (register)
- [x] Implement `GET /api/vendors/{id}` returning vendor detail + checklist status + score
- [x] Implement `POST /api/vendors/{id}/activate` enforcing checklist
- [x] Implement `GET /api/vendors/{id}/score` returning `VendorReliabilityScore` breakdown
- [x] Add `RegisterVendorRequest`, `VendorResponse`, `VendorScoreResponse` DTOs

### Persistence (Common Layer)
- [x] Write `V9__vendors.sql` migration (vendors, vendor_sku_assignments, vendor_breach_log tables)
- [x] Implement `VendorRepository`, `VendorSkuAssignmentRepository`, `VendorBreachLogRepository`

## Testing Strategy

- Unit test `VendorActivationService`: activation fails if any checklist item is false
- Unit test `VendorReliabilityScorer`: known inputs produce expected score
- Unit test `VendorSlaMonitor`: breach rate above threshold emits event, below threshold does not
- Integration test: `VendorSlaBreached` event → catalog `VendorBreachListener` → linked SKUs auto-paused
- API test: all 4 vendor endpoints

## Rollout Plan

1. Write `V9__vendors.sql`
2. Implement domain entities and value objects
3. Implement activation service and reliability scorer
4. Implement SLA monitor
5. Implement breach listener in catalog module
6. Add REST handler
