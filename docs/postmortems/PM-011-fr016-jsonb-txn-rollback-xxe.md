# PM-011: FR-016 DemandScanJob — JSONB Type Mismatch, Transaction Rollback, and XXE Vulnerability

**Date:** 2026-03-18
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

Three bugs were discovered during E2E testing and PR review of FR-016 (DemandScanJob). A Hibernate JSONB column type mismatch caused 500 errors on scan trigger. A `@Transactional` annotation on the scan orchestrator silently prevented failure status from being persisted. An unhardened XML parser in the Google Trends adapter was vulnerable to XXE injection. All three were caught before merge — the JSONB issue during E2E testing, the other two by the Unblocked bot PR reviewer.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-016 implementation complete — 28 backend files, 3 frontend files, 30 unit tests passing |
| +5 min | E2E test: `POST /api/portfolio/demand-scan/trigger` returns HTTP 500 |
| +7 min | Restarted app with `--server.error.include-message=always` to get full stack trace |
| +8 min | Root cause identified: `column "demand_signals" is of type jsonb but expression is of type character varying` |
| +10 min | Fix applied: `@JdbcTypeCode(SqlTypes.JSON)` on both JSONB columns |
| +12 min | E2E re-test: scan trigger succeeds, all endpoints return correct data |
| +25 min | PR #22 opened, Unblocked bot review posted 2 comments |
| +30 min | Review comment 1 assessed: `@Transactional` rollback bug — valid, must fix |
| +30 min | Review comment 2 assessed: XXE vulnerability — valid security issue, must fix |
| +35 min | Both fixes applied, new test added, pushed |

## Symptom

### Bug 1: JSONB Type Mismatch

```
org.springframework.dao.InvalidDataAccessResourceUsageException:
  could not execute statement [ERROR: column "demand_signals" is of type jsonb
  but expression is of type character varying
  Hint: You will need to rewrite or cast the expression.]
```

`POST /api/portfolio/demand-scan/trigger` returned HTTP 500. The `demand_scan_runs` table showed zero rows — the error occurred at commit time when Hibernate flushed the `DemandCandidate` insert.

### Bug 2: Transaction Rollback on Failure

No visible symptom in testing (the happy path always succeeded). Identified by Unblocked bot code review. If the scan job threw an exception mid-pipeline, the `FAILED` status would never be persisted because Spring would roll back the wrapping transaction.

### Bug 3: XXE Vulnerability

No runtime symptom. Identified by Unblocked bot code review as a security risk: the `DocumentBuilderFactory` in `GoogleTrendsAdapter` accepted external entities and DTDs, enabling potential XXE injection via a compromised or MITM'd RSS feed.

## Root Cause

### Bug 1: JSONB — Missing Hibernate Type Hint

1. **Why** did the insert fail? PostgreSQL received a `VARCHAR` bind parameter for a `jsonb` column and refused the implicit cast.
2. **Why** did Hibernate send `VARCHAR`? The `demandSignals` field was typed as `String` with `columnDefinition = "jsonb"`, but `columnDefinition` only affects DDL generation — it does not tell Hibernate's JDBC binder to use the `JSON` SQL type.
3. **Why** wasn't this caught in unit tests? Unit tests mock the repositories and never hit a real database. The `@JdbcTypeCode` annotation is only relevant at the JDBC driver level.
4. **Why** wasn't this a known pattern? The project had no prior JSONB columns — all previous entities used scalar types. This was the first time the Hibernate-PostgreSQL JSONB handshake was exercised.

Problematic code in `DemandCandidate.kt`:
```kotlin
// Before: columnDefinition is DDL-only, doesn't affect JDBC binding
@Column(name = "demand_signals", columnDefinition = "jsonb")
val demandSignals: String? = null,
```

The same issue existed on `CandidateRejection.metadata`.

### Bug 2: @Transactional Rollback — Orchestrator Wrapped in Single Transaction

1. **Why** would the FAILED status not persist? The `run()` method was annotated `@Transactional`, wrapping the entire scan pipeline in one transaction.
2. **Why** does that prevent failure recording? In the catch block, `scanRun.status = "FAILED"` was saved, but then `throw e` re-threw the exception. Spring's `TransactionInterceptor` catches the exception at the proxy boundary and calls `rollback()`, undoing the failure save along with everything else.
3. **Why** was `@Transactional` added in the first place? Pattern copying from `KillWindowMonitor`, which is a simpler scan that processes a list of SKUs in a single batch. `DemandScanJob` is fundamentally different — it's an orchestrator that coordinates multiple independent operations (collect, score, persist), and partial progress is preferable to all-or-nothing.
4. **Why** wasn't this caught in tests? The `DemandScanJobTest` mocked repositories, so `save()` always "succeeded" regardless of transaction boundaries. No test verified that a failure mid-pipeline left the scan run in `FAILED` status in the database.

Problematic code in `DemandScanJob.kt`:
```kotlin
// Before: @Transactional wraps entire method, rollback undoes catch block saves
@Scheduled(cron = "0 0 3 * * *")
@Transactional  // <-- this is the problem
fun run() {
    // ...
    try {
        // pipeline work
    } catch (e: Exception) {
        scanRun.status = "FAILED"
        scanRunRepository.save(scanRun)  // saved, but rolled back by Spring
        throw e  // triggers rollback of entire transaction
    }
}
```

### Bug 3: XXE — Default XML Parser Configuration

1. **Why** was the parser vulnerable? `DocumentBuilderFactory.newInstance()` returns a parser with external entity processing enabled by default (Java's historical default for backward compatibility).
2. **Why** wasn't it hardened? The adapter was written to parse a known, trusted RSS feed from Google Trends. The developer's mental model was "I'm parsing Google's feed" rather than "I'm parsing untrusted XML from the network."
3. **Why** does it matter for an RSS feed? The feed URL traverses the public internet. A MITM attack, DNS poisoning, or compromised CDN could inject a malicious XML payload that reads local files (`file:///etc/passwd`) or performs SSRF against internal services.

Problematic code in `GoogleTrendsAdapter.kt`:
```kotlin
// Before: default factory accepts external entities and DTDs
val factory = DocumentBuilderFactory.newInstance()
factory.isNamespaceAware = true
// no hardening — vulnerable to XXE
val builder = factory.newDocumentBuilder()
val document = builder.parse(URI(feedUrl).toURL().openStream())
```

## Fix Applied

### Bug 1: JSONB Type Hint
Added `@JdbcTypeCode(SqlTypes.JSON)` to both JSONB fields. This tells Hibernate to use the `JSON` SQL type when binding the JDBC parameter, which PostgreSQL accepts for `jsonb` columns.

```kotlin
// After
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "demand_signals", columnDefinition = "jsonb")
val demandSignals: String? = null,
```

### Bug 2: Remove Wrapping Transaction
Removed `@Transactional` from `run()`. Each `repository.save()` call commits independently via Spring Data's default transactional behavior. The catch block's failure save now persists because it's not wrapped in a transaction that gets rolled back. Added a test to verify.

```kotlin
// After: no @Transactional — orchestrator, not a unit of work
@Scheduled(cron = "0 0 3 * * *")
fun run() {
```

### Bug 3: OWASP XML Hardening
Added three security features to the `DocumentBuilderFactory`:

```kotlin
// After: OWASP-hardened
val factory = DocumentBuilderFactory.newInstance()
factory.isNamespaceAware = true
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
```

### Files Changed
- `modules/portfolio/.../domain/DemandCandidate.kt` — Added `@JdbcTypeCode(SqlTypes.JSON)` on `demandSignals` field
- `modules/portfolio/.../domain/CandidateRejection.kt` — Added `@JdbcTypeCode(SqlTypes.JSON)` on `metadata` field
- `modules/portfolio/.../domain/service/DemandScanJob.kt` — Removed `@Transactional` from `run()`
- `modules/portfolio/.../proxy/GoogleTrendsAdapter.kt` — Added XXE hardening features to `DocumentBuilderFactory`
- `modules/portfolio/.../domain/service/DemandScanJobTest.kt` — Added test for failure status persistence

## Impact

- **Bug 1 (JSONB):** Blocked all demand scan functionality. Any trigger attempt returned 500. Caught during E2E testing before merge.
- **Bug 2 (Transaction rollback):** Silent data loss — scan failures would appear as if they never happened (no `FAILED` row in `demand_scan_runs`). Caught by PR review before merge. Would have made production debugging very difficult since failed scans would leave no trace.
- **Bug 3 (XXE):** Latent security vulnerability. No exploitation path in local/stub mode, but real adapters parse XML from the public internet. Caught by PR review before merge. OWASP Top 10 category A05:2021 (Security Misconfiguration).

## Lessons Learned

### What went well
- E2E testing caught the JSONB bug immediately — the investment in the E2E playbook paid off
- Restarting with `--server.error.include-message=always` gave the full stack trace quickly
- Unblocked bot PR review caught both the transaction semantics bug and the XXE vulnerability — neither would have been caught by unit tests alone
- All three bugs were fixed before merge to main

### What could be improved
- No prior JSONB usage in the project meant no established pattern to follow — the first usage hit a known Hibernate/PostgreSQL impedance mismatch
- Unit tests with mocked repositories cannot catch transaction boundary bugs — this is a fundamental limitation of the testing strategy
- XML security hardening should be a default, not something added after review — the default `DocumentBuilderFactory` configuration is insecure by design (Java backward compatibility)
- The `@Transactional` was added by pattern-copying from `KillWindowMonitor` without considering that an orchestrator has different transaction semantics than a batch processor

## Prevention

- [ ] **Architectural rule: JSONB columns require `@JdbcTypeCode(SqlTypes.JSON)`** — Add to CLAUDE.md under "Critical Engineering Constraints" so every JSONB column gets the annotation. Consider a custom `@JsonbColumn` composed annotation that bundles both.
- [ ] **Architectural rule: scheduled job orchestrators should NOT be `@Transactional`** — Orchestrators that coordinate multiple independent writes should let each write commit independently. Only batch processors operating on a single logical unit of work should use method-level `@Transactional`. Add to CLAUDE.md.
- [ ] **XML parser hardening utility** — Create a `SecureXmlFactory.newDocumentBuilder()` helper in the `shared` module that returns an OWASP-hardened `DocumentBuilder`. Any future XML parsing should use this instead of raw `DocumentBuilderFactory`. Eliminates the class of XXE bugs by default.
- [ ] **Integration test for JSONB persistence** — The WireMock integration tests planned in RAT-15 will exercise the full persistence path including JSONB serialization. This would have caught Bug 1 in CI.
- [ ] **Transaction boundary test pattern** — For scheduled jobs that write to the database, add a test that verifies failure states are persisted after exceptions. The new `DemandScanJobTest` test serves as the template for this pattern.
