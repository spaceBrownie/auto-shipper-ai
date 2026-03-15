# PM-010: Structured Workflow vs. Vibe Coding — FR-011 Comparison

**Date:** 2026-03-15
**Type:** Process Comparison (not an incident)
**Status:** Complete
**Related:** [PM-008](PM-008-vibe-coding-compliance-quality-gaps.md) (vibe coding session), [PM-009](PM-009-fr010-011-implementation-bugs.md) (structured session bugs)

## Summary

FR-011 (compliance guards) was implemented twice. The first attempt (PM-008, 2026-03-14) used unstructured "vibe coding" — no workflow skill, no context hydration, no review gates. The second attempt (this session, 2026-03-15) used the structured `/feature-request` Phase 4 workflow with parallel worktree agents and `/unblock` context. This document compares the two approaches on the same feature.

## Side-by-Side Comparison

| Dimension | Vibe Coding (PM-008) | Structured Workflow (This Session) |
|---|---|---|
| **Time** | ~30 min | ~10 min (parallel with FR-010) |
| **Files created** | 22 files, 931 lines | 16 files + 5 test files, ~800 lines |
| **Tests** | 25 (green but shallow) | 29 (behavioral, not just reflection) |
| **Bugs shipped** | 3 critical, 4 high, 7 medium | 0 critical, 2 medium (caught pre-merge) |
| **Auto-check flow** | Non-functional (event never published) | Functional (E2E verified) |
| **PM-005 pattern** | Annotations correct, no behavioral test | Annotations correct, behavioral tests |
| **Coroutine safety** | Violated (JPA inside `async {}`) | Correct (pure results in `async`, writes outside) |
| **`RESELLER_SOURCE`** | Dead code (in enum, never used) | Used by `SourcingCheckService` |
| **Audit scoping** | No `run_id` — re-checks show stale FAILED | `run_id` groups records per execution |
| **Controller quality** | 5 DTOs inline, no validation, no logging | DTOs separated, logging present |
| **Context hydration** | None — no `/unblock`, no Discovery phase | `/unblock` used for alignment check; implementation plan pre-reviewed |
| **Document precedence** | N/A (no conflicting docs consulted) | Caught spec/plan conflict (budget vs. sourceSignal) — required manual fix |
| **E2E verification** | None — "25 tests pass" was the exit criteria | Full E2E: compliance pass → auto-advance, compliance fail → auto-terminate |

## What the Structured Workflow Caught That Vibe Coding Missed

### 1. The auto-check flow actually works

PM-008's most damning finding: `SkuReadyForComplianceCheck` was never published. The `ComplianceOrchestrator` had a listener, but no code emitted the event. The structured session's E2E test proved the full chain: SKU created → compliance check → `ComplianceCleared` → `CatalogComplianceListener` → SKU auto-advances to `VALIDATION_PENDING`.

### 2. Re-check scenario handled

PM-008 never considered what happens when a SKU fails compliance, is corrected, and re-checked. The structured session didn't either — but the `unblocked-bot` PR review caught it, and the `run_id` fix was applied before merge. The vibe coding session had no PR review step.

### 3. Coroutine/transaction boundary respected

PM-008 explicitly violated the implementation plan's warning about `@Transactional` not propagating across coroutine contexts. The structured session's agent produced code where `async` blocks contain only pure check logic (no JPA calls), with audit writes happening in the `@Transactional` method outside `coroutineScope`.

### 4. Test quality preserved

PM-008 stripped behavioral tests when Mockito couldn't handle inline value classes, falling back to annotation-only reflection tests. The structured session's agent wrote behavioral tests that verify actual orchestrator behavior (event emission, audit record creation, concurrent execution).

## What the Structured Workflow Still Got Wrong

The structured approach was not flawless. PM-009 documents four bugs:

1. **Missing `kotlin("plugin.jpa")`** — The agent omitted the Gradle plugin. Unit tests passed; only the E2E test caught it. Vibe coding actually got this right (it had `plugin.jpa`).

2. **Budget fields instead of zero-capital model** — The agent followed the stale spec instead of the implementation plan. This is a new failure mode unique to the multi-document structured approach — vibe coding had no conflicting documents to choose between.

3. **Compliance status pollution** — Neither approach considered the re-check scenario. The `unblocked-bot` caught it during PR review — a step that the vibe coding session skipped entirely.

4. **Stale table name in playbook** — A rename migration created a hidden reference. Neither approach would have caught this without a post-rename grep.

## Key Takeaway

The structured workflow's advantage is not that it produces bug-free code — it produced 4 bugs. The advantage is that it produces **verifiable** code with **review gates** that catch bugs before merge:

- E2E testing caught 2 bugs
- `unblocked-bot` PR review caught 2 bugs
- 0 bugs reached the main branch

The vibe coding session produced code with **3 critical issues that were invisible to its own verification** (25 green tests, build passes, PR looks clean). The primary feature was non-functional and nobody noticed for an entire session.

The cost difference: the structured session spent ~10 minutes implementing + ~20 minutes fixing bugs caught by its own review gates. The vibe coding session spent ~30 minutes implementing and would have required a full re-implementation (which is what happened).
