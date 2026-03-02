# Plan: Feature Request Skill — Test-First Gate

## Problem

FR-003 (catalog-sku-lifecycle) defined the full state machine but didn't document what
triggers intermediate state transitions (IDEATION → VALIDATION_PENDING → COST_GATING).
FR-004 and FR-005 built API endpoints that require the SKU to already be in a downstream
state, but neither flagged the gap. The missing transition endpoint wasn't discovered
until seed data was created and the full API flow was exercised manually.

If the implementation plan had required test definitions *before* implementation, an
integration test like "create SKU → verify costs → stress test → assert LISTED" would
have immediately surfaced the unreachable state transitions.

## Proposed Change

Add a **Phase 2.5: Test Specification** step to the feature-request skill workflow
(between Planning and Implementation). This phase would require:

1. **End-to-end flow tests** — describe the full user-facing flow that exercises the
   feature across API boundaries. These act as smoke tests for cross-FR integration gaps.

2. **Boundary condition tests** — enumerate the error/edge cases the state machine or
   domain logic must handle (invalid transitions, missing prerequisites, currency
   mismatches, etc.).

3. **Dependency contract tests** — for any FR that depends on another FR's state or data,
   define a test that asserts the prerequisite is reachable through the current API surface.

The test specs don't need to be runnable code at this stage — structured pseudocode or
Gherkin-style scenarios are sufficient. The goal is to force the planner to trace the
full path before writing implementation tasks.

## Files to Modify

- `.claude/skills/feature-request/SKILL.md` — add Phase 2.5 description
- `.claude/skills/feature-request/scripts/validate-phase.py` — add phase 2.5 validation
- `.claude/skills/feature-request/QUICKREF.md` — update phase list
- `.claude/skills/feature-request/tests/` — add example test spec template

## Impact

This is a process improvement to the AI-assisted planning workflow. No runtime code changes.
The cost is ~10 minutes of additional planning per FR. The payoff is catching integration
gaps before any implementation code is written, avoiding rework on migrations, seed data,
and test suites.
