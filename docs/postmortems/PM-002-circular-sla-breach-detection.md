# PM-002: Circular SLA Breach Detection — Monitor Could Never Trigger

**Date:** 2026-03-06
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

The `VendorSlaMonitor` was implemented with a circular dependency: it computed breach rates by counting rows in `vendor_breach_log`, but the only code that wrote to that table was the monitor itself — meaning the count would always be zero and the monitor could never trigger. A subsequent fix introduced a `vendor_fulfillment_records` table that duplicated responsibilities belonging to FR-008 (fulfillment orchestration), requiring a second refactor to extract a clean `VendorFulfillmentDataProvider` interface.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-007 implementation began — domain, services, handler, tests all created |
| +20 min | All 29 tests passing, commit pushed to `feat/FR-007-vendor-governance` |
| +25 min | PR #9 opened, unblocked-bot automated review posted 2 issues |
| +27 min | Review fetched — Issue 1: circular breach detection, Issue 2: self-reinforcing escalation |
| +30 min | Fix applied: added `vendor_fulfillment_records` table as external data source |
| +35 min | All tests passing, fix committed and pushed |
| +37 min | User identified that fulfillment records belong to FR-008, not FR-007 |
| +40 min | Refactored to `VendorFulfillmentDataProvider` interface + stub implementation |
| +45 min | Final fix committed and pushed |

## Symptom

The unblocked-bot review identified the core issue:

> The "breach rate" is computed as `breachLogRepository.countByVendorId(vendor.id)` — a raw count of `vendor_breach_log` rows. However, the **only** production code that inserts into `vendor_breach_log` is this very monitor, and it only inserts **after** the threshold is already exceeded. This is circular:
> 1. To detect a breach, breach log entries must already exist.
> 2. Breach log entries are only created when a breach is detected.
> 3. Therefore, `breachCount` will always be 0 for a new vendor and the monitor will never trigger.

Additionally, the raw count was passed to `Percentage.of(...)`, conflating an absolute count with a percentage value.

## Root Cause

Applying the 5 whys:

1. **Why** could the SLA monitor never trigger? → `breachLogRepository.countByVendorId()` always returned 0 for vendors that hadn't already been flagged.
2. **Why** was the count always 0? → The only writer to `vendor_breach_log` was `VendorSlaMonitor.runCheck()` itself, which only wrote after exceeding the threshold.
3. **Why** was the monitor reading from its own output table? → The implementation conflated two distinct concepts: *operational violations* (input data — late shipments, defects) and *breach detection events* (output data — when the monitor flags a vendor).
4. **Why** wasn't this caught in design? → The implementation plan specified "breach count / total orders in rolling 30 days" but this was implemented as a simple `countByVendorId` without an external data source or time window.
5. **Why** wasn't it caught in testing? → Unit tests mocked the repository to return preset values, so the circular dependency was invisible — mocks bypassed the real data flow.

The problematic code in `VendorSlaMonitor.kt`:

```kotlin
// The monitor reads from the breach log...
val breachCount = breachLogRepository.countByVendorId(vendor.id)
val breachRate = BigDecimal(breachCount)

if (breachRate >= breachThreshold) {
    // ...but the breach log is only written here, AFTER the threshold check
    breachLogRepository.save(
        VendorBreachLog(vendorId = vendor.id, breachRate = breachRate, threshold = breachThreshold)
    )
}
```

A secondary issue emerged during the first fix: a `vendor_fulfillment_records` table and `/fulfillments` endpoint were added to the vendor module, but FR-008 (fulfillment orchestration) owns order fulfillment data. This violated bounded-context boundaries documented in the project's cross-module boundary pattern.

## Fix Applied

**Fix 1** (reverted): Added `vendor_fulfillment_records` table as external data source with `VendorFulfillmentRecordRepository`. Breach rate calculated as `violations / total_fulfillments * 100` over a 30-day rolling window. This was correct algorithmically but wrong architecturally.

**Fix 2** (final): Extracted a `VendorFulfillmentDataProvider` interface in the vendor module with a `StubFulfillmentDataProvider` returning 0s. FR-008 will provide the real implementation. The SLA monitor now depends on the interface, not concrete storage.

```kotlin
// Interface owned by vendor module — shaped by what it needs
interface VendorFulfillmentDataProvider {
    fun countFulfillmentsSince(vendorId: UUID, since: Instant): Long
    fun countViolationsSince(vendorId: UUID, since: Instant): Long
}
```

The monitor now computes a proper percentage-based breach rate:

```kotlin
val totalFulfillments = fulfillmentDataProvider.countFulfillmentsSince(vendor.id, since)
if (totalFulfillments == 0L) continue

val violations = fulfillmentDataProvider.countViolationsSince(vendor.id, since)
val breachRate = BigDecimal(violations)
    .multiply(BigDecimal(100))
    .divide(BigDecimal(totalFulfillments), 2, RoundingMode.HALF_UP)
```

### Files Changed
- `modules/vendor/src/main/kotlin/.../service/VendorSlaMonitor.kt` — replaced `breachLogRepository.countByVendorId()` with `fulfillmentDataProvider` interface calls, added 30-day rolling window
- `modules/vendor/src/main/kotlin/.../service/VendorFulfillmentDataProvider.kt` — new interface defining the data contract
- `modules/vendor/src/main/kotlin/.../service/StubFulfillmentDataProvider.kt` — stub returning 0s until FR-008 provides real impl
- `modules/vendor/src/main/kotlin/.../service/VendorActivationService.kt` — removed `VendorFulfillmentRecordRepository` dependency
- `modules/vendor/src/main/kotlin/.../handler/VendorController.kt` — removed `/fulfillments` endpoint
- `modules/app/src/main/resources/db/migration/V9__vendors.sql` — removed `vendor_fulfillment_records` table
- Deleted: `VendorFulfillmentRecord.kt`, `VendorFulfillmentRecordRepository.kt`, `RecordFulfillmentRequest.kt`
- Updated: all related unit and integration tests

## Impact

The monitor would have been inert in production — it would run every 15 minutes but never detect any breaches, effectively disabling the automated vendor governance that is a core business requirement. No SKUs would ever be auto-paused for vendor SLA violations. No data corruption since the stub returns 0s (same effective behavior as the circular version), but the feature would be non-functional until FR-008 provides real data.

## Lessons Learned

### What went well
- Automated code review (unblocked-bot) caught the circular dependency before merge
- The user caught the bounded-context violation (fulfillment data in vendor module) that the automated review missed
- The fix was cleanly decomposed into two commits — algorithmic fix, then architectural refactor

### What could be improved
- The implementation plan said "breach count / total orders in rolling 30 days" but no validation ensured the implementation matched — the plan's metric description was ignored in favor of a simpler (but wrong) count query
- Unit tests with mocked repositories can't catch data-flow circularity — the tests passed because mocks returned whatever values were configured, hiding the real dependency chain
- Cross-module data ownership wasn't considered during implementation — the spec lists FR-008 as a dependency but the dependency direction wasn't analyzed when building the SLA monitor

## Prevention

- [ ] **Add integration test for SLA monitor with real DB**: A test that inserts fulfillment records, runs the monitor, and asserts breach detection should catch circular dependencies since no mock bypasses the real data flow
- [ ] **Spec review checklist item**: When a service reads data, verify the write path exists and is owned by the correct module — add "data source ownership" as a required section in implementation plans
- [ ] **Cross-reference FR dependencies before implementation**: When an FR spec lists dependencies on other FRs, explicitly document which module owns each data source the current FR needs, and use interfaces (not concrete tables) for data not yet available
- [ ] **Flag mock-only coverage in domain services**: Services that only have unit tests with mocked repositories should be flagged for integration test coverage — especially scheduled jobs and monitors where the data flow is the core logic
