# PM-009: FR-010/FR-011 Parallel Implementation — Four Bugs Caught Before Merge

**Date:** 2026-03-15
**Severity:** Medium
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

FR-010 (portfolio orchestration) and FR-011 (compliance guards) were implemented in parallel using worktree-isolated agents. This was the **first time the project used the parallel worktree agent pattern** — the orchestrator spawned two independent sub-agents, each in its own git worktree, implementing a full FR simultaneously. Four bugs were discovered during E2E testing and automated PR review — none reached production. Two were caught by E2E testing, two by the `unblocked-bot` PR reviewer.

## Execution Pattern: Parallel Worktree Agents (First Use)

### What we did

The feature-request skill's Phase 4 (Implementation) recommends spawning layer-specific sub-agents (domain, handler, persistence, etc.) in parallel within a single FR. Instead, we went one level higher: the orchestrator read both implementation plans, then spawned **two top-level agents** — one per FR — each in a worktree-isolated copy of the repo. After both completed, the orchestrator manually merged their outputs into the main working directory, resolved the `application.yml` overlap (both appended to the end — clean merge), and ran a combined build + E2E test.

### What worked

- **Wall-clock time:** Both modules (~60 files, ~3200 lines, 56 tests) were implemented in ~10 minutes of wall time, vs. sequential which would have been ~20 minutes.
- **Isolation:** Worktrees prevented the agents from stepping on each other's files. Migration version conflicts were avoided by pre-assigning V14 (portfolio) and V15 (compliance) in the prompts.
- **Merge was clean:** Both agents added new files to shared directories (`shared/events/`, `catalog/service/`) and appended to `application.yml`. No line-level conflicts.

### What didn't work

- **Sub-agents cannot spawn sub-agents.** The feature-request skill's Phase 4 recommends spinning up layer-specific sub-agents (e.g., one for domain layer, one for handler layer, one for tests). When a top-level agent is itself a sub-agent (spawned via the Agent tool), it cannot use the Agent tool recursively. This means each FR agent had to implement all layers sequentially within a single agent context, rather than parallelizing within the FR. The Phase 4 skill's sub-agent recommendations are effectively ignored in this pattern.
- **Agents can't validate runtime behavior.** Both agents ran `./gradlew :module:compileKotlin` and `./gradlew :module:test` successfully, but neither could start the full Spring Boot app to test HTTP endpoints. Bug 1 (missing JPA plugin) compiled fine and passed all unit tests — it only failed at runtime when Hibernate tried to instantiate an entity. The orchestrator must own the E2E verification step.
- **Conflicting source documents.** Bug 2 (budget fields) happened because the FR-010 agent had access to both the spec (pre-PM-007, still referencing "budget") and the implementation plan (post-PM-007, using "sourceSignal"). The agent chose the spec's terminology. When spawning agents with conflicting guidance, the prompt must explicitly state document precedence.
- **No cross-agent awareness.** Each agent only knew about its own FR. If FR-010 and FR-011 had shared a new domain type (beyond events), neither agent could have coordinated. This pattern works for independent modules but would break for FRs with shared implementation dependencies.

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

- **Parallel execution cut wall time in half** — two full modules (~10 min vs ~20 min sequential)
- **Worktree isolation prevented file conflicts** — each agent had its own repo copy; merge was trivial
- **Pre-assigned migration versions worked** — V14/V15 specified in prompts eliminated numbering conflicts
- E2E testing caught Bugs 1 and 2 before the PR was even created
- `unblocked-bot` automated review caught Bugs 3 and 4 — both were edge cases that the E2E happy path didn't exercise
- All fixes were straightforward — no architectural rework required

### What could be improved

- **Sub-agents can't spawn sub-agents.** The feature-request skill's Phase 4 recommends layer-specific sub-agents, but when the FR agent is itself a sub-agent, it can't use the Agent tool recursively. Phase 4's parallelization recommendations are silently ignored. Either the skill needs to detect this and adjust, or the orchestrator should spawn the layer-specific agents directly.
- **Agents don't cross-check specs against implementation plans.** Bug 2 happened because the agent had conflicting guidance (pre-PM-007 spec vs post-PM-007 implementation plan) and chose the wrong source. When specs and implementation plans diverge, the implementation plan should win — but agents need to be told this explicitly.
- **Agents can't do runtime verification.** Compilation + unit tests pass in the worktree, but the agent can't start the full Spring Boot app to hit endpoints. The orchestrator must own the E2E step — and should run it before committing, not after.
- **E2E tests only covered happy paths.** Bug 3 required a re-check scenario (fail → fix → re-check → should show CLEARED). The playbook should include negative-then-positive test flows for any workflow that supports re-runs.
- **Migration renames create hidden dependencies.** Bug 4 is a class of problem where renaming a database object requires auditing all references outside the migration itself (playbooks, scripts, documentation). A checklist for rename migrations would help.

## Recommendations for Future Parallel FR Implementation

1. **Use parallel worktree agents when FRs are independent** (no shared new types beyond events). Pre-assign migration versions and conflict zones in the prompt.
2. **Don't rely on Phase 4 sub-agent recommendations.** Sub-agents can't spawn sub-agents. If you want layer-level parallelism within an FR, the orchestrator must spawn those agents directly — not delegate to a sub-agent that then tries to spawn more.
3. **Explicitly state document precedence** in the agent prompt: "The implementation plan takes precedence over the spec for naming and field choices."
4. **The orchestrator owns E2E.** Agent worktrees can compile and unit-test, but only the orchestrator can boot the full app and hit endpoints. Run E2E before committing the merge.
5. **Post-merge grep for stale references** after any rename migration: `grep -r "old_name" --include="*.md" --include="*.sql" --include="*.yml" --include="*.kt"`

## Prevention

- [ ] Add a re-check compliance scenario to the E2E playbook: fail → re-check → verify CLEARED (already added in this PR)
- [ ] When spawning implementation agents, explicitly state which document takes precedence when spec and implementation plan conflict
- [ ] After any migration that renames a table or column, grep the entire repo for the old name
- [x] Add the `kotlin("plugin.jpa")` requirement to CLAUDE.md under Critical Engineering Constraints for any module that defines `@Entity` classes *(RAT-17: CLAUDE.md constraint #10)*
- [ ] Document the parallel worktree agent pattern limitations (no recursive sub-agents, no runtime verification) in the feature-request skill's Phase 4 instructions
