# PM-009: FR-010/FR-011 Parallel Implementation — Four Bugs Caught Before Merge

**Date:** 2026-03-15
**Severity:** Medium
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

FR-010 (portfolio orchestration) and FR-011 (compliance guards) were implemented in parallel using worktree-isolated agents. Four bugs were discovered during E2E testing and automated PR review — none reached production. Two were caught by E2E testing, two by the `unblocked-bot` PR reviewer. All four share a root cause: delegating implementation to parallel agents without a post-merge integration verification step that exercises the full runtime behavior.

## Bugs Found

### Bug 1: Missing `kotlin("plugin.jpa")` in Portfolio Module

**Discovered by:** E2E test (experiment creation returned HTTP 500)

**Symptom:** `POST /api/portfolio/experiments` returned `500 Internal Server Error`. Hibernate logged `HHH000182: No default (no-argument) constructor for class: com.autoshipper.portfolio.domain.Experiment` during startup — a warning, not a fatal error — then failed at runtime when JPA tried to instantiate the entity.

**Root cause:** The portfolio module's `build.gradle.kts` had `kotlin("plugin.spring")` but not `kotlin("plugin.jpa")`. The JPA plugin generates synthetic no-arg constructors for `@Entity` classes at compile time. Without it, Hibernate falls back to reflection-based instantiation, which fails for Kotlin classes with required constructor parameters. The compliance module had `kotlin("plugin.jpa")` (added by its agent), but the portfolio agent omitted it.

**Fix:** Added `kotlin("plugin.jpa")` to `modules/portfolio/build.gradle.kts`.

**Why it wasn't caught earlier:** Unit tests mock repositories and never instantiate entities via Hibernate. The `HHH000182` warning during startup was visible in logs but non-fatal — the app started successfully. The bug only manifested when a REST endpoint triggered a JPA `save()`.

### Bug 2: Budget Fields Instead of Zero-Capital Model

**Discovered by:** Manual review during E2E testing (user caught the deviation)

**Symptom:** The `Experiment` entity had `budgetAmount` and `budgetCurrency` fields. The `CreateExperimentRequest` DTO required `budgetAmount` and `budgetCurrency`. The `PortfolioSummaryResponse` exposed `capitalDeployed`. All three concepts are explicitly prohibited by PM-007 AD-8 (zero-capital model).

**Root cause:** The FR-010 agent was given the implementation plan as context, which correctly specified `sourceSignal` and `estimatedMarginPerUnit` instead of budget fields. However, the agent also had access to the FR-010 spec (on `main`), which still contained pre-PM-007 language about "budget" and "capital deployed." The agent followed the spec's terminology instead of the implementation plan's.

**Fix:** Replaced `budgetAmount`/`budgetCurrency` with `sourceSignal`/`estimatedMarginPerUnit`/`estimatedMarginCurrency` in the entity, service, controller, DTOs, tests, and migration (V16). Renamed `capitalDeployed` to `totalProfit` in `PortfolioReporter` and `PortfolioSummaryResponse`. Renamed `capital_reallocation_log` table to `priority_ranking_log`.

**Why it wasn't caught earlier:** The agent's unit tests validated behavior (create, validate, fail) but not field semantics. The tests passed with budget fields just as well as with source signal fields. Only a human reviewing the API response against the business model caught the deviation.

### Bug 3: Compliance Status Polluted by Historical Audit Records

**Discovered by:** `unblocked-bot` PR review ([PR #15 comment](https://github.com/spaceBrownie/auto-shipper-ai/pull/15))

**Symptom:** `getComplianceStatus` would return `FAILED` even after a successful re-check. A SKU that failed IP check, was corrected, and re-checked would still show `FAILED` because the old `FAILED` records persisted in the audit table and `records.any { it.result == "FAILED" }` scanned ALL historical records.

**Root cause:** The `ComplianceController.getComplianceStatus()` method queried all audit records for a SKU (`findBySkuIdOrderByCheckedAtDesc`) and determined the latest result by scanning the entire history. The audit table is immutable by design (FR-011 spec requirement), so old FAILED records are never deleted. Without a way to distinguish which records belong to which check execution, the status was always contaminated by prior failures.

**Fix:** Added a `run_id` (UUID) column to `compliance_audit` (V17 migration). `ComplianceOrchestrator.runChecks()` generates a `run_id` per execution and tags all 4 audit records with it. `getComplianceStatus()` now filters by the latest `run_id` to determine current status, while still returning the full audit history.

**Why it wasn't caught earlier:** The E2E test only tested a single compliance run per SKU — it never exercised the re-check scenario. The unit tests mocked the repository and didn't test the controller's status aggregation logic.

### Bug 4: Stale Table Name in E2E Playbook Truncate

**Discovered by:** `unblocked-bot` PR review ([PR #15 comment](https://github.com/spaceBrownie/auto-shipper-ai/pull/15))

**Symptom:** The E2E playbook's reset script would fail with `relation "capital_reallocation_log" does not exist` after V16 migration runs.

**Root cause:** V16 migration renamed `capital_reallocation_log` to `priority_ranking_log`, but the E2E playbook's TRUNCATE statement still referenced the old name. The playbook was updated to fix a similar issue (`stress_test_results` → `sku_stress_test_results`) but the `capital_reallocation_log` rename was missed because V16 was created after the initial playbook update.

**Fix:** Updated the TRUNCATE statement in `docs/e2e-test-playbook.md`.

**Why it wasn't caught earlier:** The E2E TRUNCATE was run before V16 existed (the table was still named `capital_reallocation_log` at that point). After V16 was applied, the reset script was not re-run.

## Impact

All four bugs were caught before merge. No data loss, no production impact. Bug 3 (compliance status pollution) would have been the most impactful in production — it would have permanently blocked SKUs from re-passing compliance after a correction.

## Lessons Learned

### What went well

- Parallel agent implementation completed both modules in ~10 minutes of wall time
- E2E testing caught Bugs 1 and 2 before the PR was even created
- `unblocked-bot` automated review caught Bugs 3 and 4 — both were edge cases that the E2E happy path didn't exercise
- All fixes were straightforward — no architectural rework required

### What could be improved

- **Agents don't cross-check specs against implementation plans.** Bug 2 happened because the agent had conflicting guidance (pre-PM-007 spec vs post-PM-007 implementation plan) and chose the wrong source. When specs and implementation plans diverge, the implementation plan should win — but agents need to be told this explicitly.
- **E2E tests only covered happy paths.** Bug 3 required a re-check scenario (fail → fix → re-check → should show CLEARED). The playbook should include negative-then-positive test flows for any workflow that supports re-runs.
- **Migration renames create hidden dependencies.** Bug 4 is a class of problem where renaming a database object requires auditing all references outside the migration itself (playbooks, scripts, documentation). A checklist for rename migrations would help.

## Prevention

- [ ] Add a re-check compliance scenario to the E2E playbook: fail → re-check → verify CLEARED (already added in this PR)
- [ ] When spawning implementation agents, explicitly state which document takes precedence when spec and implementation plan conflict
- [ ] After any migration that renames a table or column, grep the entire repo for the old name: `grep -r "old_table_name" --include="*.md" --include="*.sql" --include="*.yml"`
- [ ] Add the `kotlin("plugin.jpa")` requirement to CLAUDE.md under Critical Engineering Constraints for any module that defines `@Entity` classes
