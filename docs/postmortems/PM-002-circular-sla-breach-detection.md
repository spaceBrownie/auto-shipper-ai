# PM-002: Circular SLA Breach Detection — Monitor and Scorer Could Never Work

**Date:** 2026-03-06
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

The `VendorSlaMonitor` was implemented with a circular dependency: it computed breach rates by counting rows in `vendor_breach_log`, but the only code that wrote to that table was the monitor itself — meaning the count would always be zero and the monitor could never trigger. The initial fix introduced a `vendor_fulfillment_records` table that violated bounded-context boundaries (fulfillment data belongs to FR-008), requiring a second refactor to extract a `VendorFulfillmentDataProvider` interface. A subsequent automated re-review revealed the same self-reinforcing pattern in `VendorReliabilityScorer`, which read all-time breach counts from the monitor's output table with no rolling window — making vendor recovery permanently impossible after 10 cumulative breaches.

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
| +45 min | Final fix committed and pushed, re-review requested |
| +47 min | Unblocked-bot re-review: `VendorReliabilityScorer` has same self-reinforcing pattern |
| +55 min | Scorer refactored to use `VendorFulfillmentDataProvider` with 30-day rolling window |
| +60 min | All vendor tests passing including 3 new scorer test cases |

## Symptom

The unblocked-bot review on commit `66dcbdc` identified two issues:

> **Issue 1 — Circular breach detection:**
> The "breach rate" is computed as `breachLogRepository.countByVendorId(vendor.id)` — a raw count of `vendor_breach_log` rows. However, the **only** production code that inserts into `vendor_breach_log` is this very monitor, and it only inserts **after** the threshold is already exceeded. This is circular:
> 1. To detect a breach, breach log entries must already exist.
> 2. Breach log entries are only created when a breach is detected.
> 3. Therefore, `breachCount` will always be 0 for a new vendor and the monitor will never trigger.

> **Issue 2 — Self-reinforcing escalation:**
> Even if the circular-dependency issue is fixed, this code appends a new breach log entry every time the monitor detects a threshold violation. Because the detection metric counts all breach log rows, each monitor run that finds a breach inflates the count, making it permanently impossible for a vendor to drop below the threshold even after being re-activated and performing well.

After the monitor was fixed, a re-review on commit `7f8a756` found a third issue:

> **Issue 3 — Scorer uses same anti-pattern:**
> `breachLogRepository.countByVendorId(vendorId.value)` in `VendorReliabilityScorer` counts **all** breach log entries ever recorded, with no rolling time window. After just 10 cumulative breaches, `breachScore` is permanently clamped to 0 (`100 - 10 * 10 = 0`), and a vendor can never recover even if re-activated and performing perfectly afterward.

## Root Cause

Applying the 5 whys:

1. **Why** could the SLA monitor never trigger and the reliability score never recover? → Both `VendorSlaMonitor` and `VendorReliabilityScorer` read from `vendor_breach_log` — a table that either had no data (circular) or only accumulated data (no time window).
2. **Why** were both services reading from the same problematic table? → The implementation treated `vendor_breach_log` as both the input (operational violations) and output (detection events) of the governance system.
3. **Why** were these two concepts conflated? → The implementation plan specified "breach count / total orders in rolling 30 days" but this was implemented as a simple `countByVendorId` without distinguishing between source data (fulfillment violations) and derived data (breach detection records).
4. **Why** wasn't the scorer caught during the first fix? → The first fix focused narrowly on the monitor. The scorer's dependency on `breachLogRepository` wasn't re-examined because it appeared to be a separate concern — but it consumed the same circular/accumulating data.
5. **Why** wasn't any of this caught in testing? → Unit tests mocked `breachLogRepository` to return preset values, so the circular dependency and accumulation problem were invisible — mocks bypassed the real data flow entirely.

The problematic code in `VendorSlaMonitor.kt` (pre-fix):

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

The problematic code in `VendorReliabilityScorer.kt` (pre-fix):

```kotlin
// Counts ALL breach log entries ever — no rolling window
val breachCount = breachLogRepository.countByVendorId(vendorId.value)

// After 10 cumulative breaches: 100 - 10*10 = 0, permanently clamped
val breachScore = (HUNDRED - BigDecimal(breachCount).multiply(MAX_BREACH_PENALTY))
    .coerceIn(BigDecimal.ZERO, HUNDRED)
```

A secondary issue emerged during the first fix: a `vendor_fulfillment_records` table and `/fulfillments` endpoint were added to the vendor module, but FR-008 (fulfillment orchestration) owns order fulfillment data. This violated bounded-context boundaries documented in the project's cross-module boundary pattern.

## Fix Applied

**Fix 1** (reverted): Added `vendor_fulfillment_records` table as external data source with `VendorFulfillmentRecordRepository`. Breach rate calculated as `violations / total_fulfillments * 100` over a 30-day rolling window. Correct algorithmically but wrong architecturally — fulfillment data belongs to FR-008.

**Fix 2** (monitor): Extracted a `VendorFulfillmentDataProvider` interface in the vendor module with a `StubFulfillmentDataProvider` returning 0s. FR-008 will provide the real implementation. The SLA monitor now depends on the interface, not concrete storage.

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

**Fix 3** (scorer): Replaced `VendorBreachLogRepository` dependency with `VendorFulfillmentDataProvider`, using the same 30-day rolling window as the monitor. Violations now age out, making vendor recovery possible.

```kotlin
val since = Instant.now().minus(ROLLING_WINDOW)
val breachCount = fulfillmentDataProvider.countViolationsSince(vendorId.value, since)
```

### Files Changed
- `modules/vendor/src/main/kotlin/.../service/VendorSlaMonitor.kt` — replaced `breachLogRepository.countByVendorId()` with `fulfillmentDataProvider` interface calls, added 30-day rolling window
- `modules/vendor/src/main/kotlin/.../service/VendorFulfillmentDataProvider.kt` — new interface defining the data contract
- `modules/vendor/src/main/kotlin/.../service/StubFulfillmentDataProvider.kt` — stub returning 0s until FR-008 provides real impl
- `modules/vendor/src/main/kotlin/.../service/VendorReliabilityScorer.kt` — replaced `VendorBreachLogRepository` with `VendorFulfillmentDataProvider`, added 30-day rolling window
- `modules/vendor/src/main/kotlin/.../service/VendorActivationService.kt` — removed `VendorFulfillmentRecordRepository` dependency
- `modules/vendor/src/main/kotlin/.../handler/VendorController.kt` — removed `/fulfillments` endpoint
- `modules/app/src/main/resources/db/migration/V9__vendors.sql` — removed `vendor_fulfillment_records` table
- `modules/vendor/src/test/.../service/VendorReliabilityScorerTest.kt` — updated mocks to `fulfillmentDataProvider`, added 3 new tests: breach score floor at 10 violations, vendor recovery after violations age out, clamping beyond 10 violations
- Deleted: `VendorFulfillmentRecord.kt`, `VendorFulfillmentRecordRepository.kt`, `RecordFulfillmentRequest.kt`
- Updated: all related unit and integration tests

## Impact

The monitor would have been inert in production — running every 15 minutes but never detecting breaches, effectively disabling automated vendor governance. No SKUs would ever be auto-paused for vendor SLA violations.

The scorer would have permanently destroyed vendor reliability scores — after 10 cumulative breaches (with no time window), a vendor's breach score component would be clamped to 0 forever. Re-activating a vendor after they improved their performance would have no effect on their score, making the vendor scoring system functionally broken for any vendor that had ever been flagged.

No data corruption since the stub returns 0s (same effective behavior as the circular version), but both features would be non-functional until FR-008 provides real data.

## Lessons Learned

### What went well
- Automated code review (unblocked-bot) caught the circular dependency before merge
- The user caught the bounded-context violation (fulfillment data in vendor module) that the automated review missed
- The re-review after the fix caught the same pattern in the scorer — demonstrating the value of re-reviewing after fixes, not just the initial code
- The fix was cleanly decomposed into commits — algorithmic fix, architectural refactor, then scorer fix

### What could be improved
- The implementation plan said "breach count / total orders in rolling 30 days" but no validation ensured the implementation matched — the plan's metric description was ignored in favor of a simpler (but wrong) count query
- Unit tests with mocked repositories can't catch data-flow circularity — tests passed because mocks returned whatever values were configured, hiding the real dependency chain
- Cross-module data ownership wasn't considered during implementation — the spec lists FR-008 as a dependency but the dependency direction wasn't analyzed when building the SLA monitor
- The first fix was applied only to the monitor without auditing other consumers of the same data source — `VendorReliabilityScorer` was missed because it was treated as a separate concern rather than part of the same data-flow problem
- When fixing a data-source issue, all consumers of that data source should be audited in the same pass

## Prevention

- [x] **Audit all consumers when fixing a data-source issue**: When a data source is found to be circular or accumulating incorrectly, grep for all references to the repository/table and fix every consumer — not just the one that was reported *(RAT-17: CLAUDE.md Testing Conventions)*
- [ ] **Add integration test for SLA monitor with real DB**: A test that inserts fulfillment records, runs the monitor, and asserts breach detection should catch circular dependencies since no mock bypasses the real data flow
- [ ] **Spec review checklist item**: When a service reads data, verify the write path exists and is owned by the correct module — add "data source ownership" as a required section in implementation plans
- [ ] **Cross-reference FR dependencies before implementation**: When an FR spec lists dependencies on other FRs, explicitly document which module owns each data source the current FR needs, and use interfaces (not concrete tables) for data not yet available
- [ ] **Flag mock-only coverage in domain services**: Services that only have unit tests with mocked repositories should be flagged for integration test coverage — especially scheduled jobs and monitors where the data flow is the core logic
- [x] **Time-window by default for all accumulating queries**: Any query that counts rows without a time bound should be flagged during review — unbounded counts on append-only tables are a code smell for unrecoverable state *(RAT-17: CLAUDE.md Testing Conventions)*
