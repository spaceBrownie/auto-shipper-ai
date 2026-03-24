# Workflow Improvement Plan

*Recommendations for reducing friction and increasing automation in the development pipeline, based on observations from the FR-023 (RAT-26) session and 15 prior feature request cycles.*

**Date:** 2026-03-23
**Status:** Proposal

---

## Current State

The feature request skill drives all significant development through a 4-phase workflow: Discovery → Specification → Planning → Implementation. Each phase has validation gates and requires human approval before proceeding. This model has proven effective — FR-001 through FR-023 shipped successfully, and the validation layers (architecture tests, automated review, CI gates) have accumulated to the point where the operator runs with full permissions, trusting the gates to catch problems.

The bottleneck has shifted. It's no longer "can we trust the code?" — the gates answer that. It's now "how fast can we get from a Linear ticket to a merged PR, and how much of that path requires a human in the seat?"

---

## Phase-Level Inefficiencies

### Phase 1 (Discovery): Redundant for well-specified tickets

**Problem:** Phase 1 explores the codebase and proposes a feature name. For tickets like RAT-26 — which already have requirements, acceptance criteria, dependencies, and a "What to Build" section — this phase re-derives information that already exists. ~80% of the discovery output restates the ticket.

**Recommendation:** Make Phase 1 conditional. If the Linear ticket has acceptance criteria + dependency list + clear scope, collapse discovery into "validate the ticket's assumptions against the codebase" (a 2-minute codebase check, not a full exploration). Reserve full discovery for vague or exploratory tickets.

**Implementation:** Add a ticket richness score at the start of the workflow. Check for: acceptance criteria present (Y/N), dependency list present (Y/N), scope bounded to identifiable modules (Y/N). If all three: skip to abbreviated discovery (validate assumptions + propose feature name). If any missing: full discovery.

### Phase 2 (Specification): Duplicates the Linear ticket

**Problem:** The spec.md reformats the Linear ticket into the spec template. For RAT-26, the spec was the ticket's description restructured with security audit findings added. The base content was reformatting, not new thinking.

**Recommendation:** Make Phase 2 additive, not duplicative. When a ticket is rich enough, the spec phase should focus on *what the ticket doesn't cover* — security analysis, edge cases, cross-module implications, non-functional requirements. The ticket IS the base spec; the phase enriches it.

**Implementation:** Update the spec template to accept a Linear ticket reference as the "base requirements" and only require sections that add information beyond the ticket: Non-Functional Requirements, Security Considerations, Cross-Module Implications, Edge Cases. Business Requirements and Success Criteria can be imported from the ticket's acceptance criteria.

### Phase 3 (Planning): No changes needed

Phase 3 is where the real architectural thinking happens — transaction boundaries, cross-module resolution, async vs sync, entity design. The implementation plan directly drives Phase 4 agents. This phase earns its cost every time.

### Phase 4 (Implementation): Three friction points

**Problem 1: Agents can't see each other's output.** Parallel agents own non-overlapping files but reference types created by other agents. Type signatures must be specified in each agent's prompt. If one agent deviates from the plan, the other's code won't compile. This session compiled on the first try, but it's fragile.

**Recommendation:** Run agents in dependency order, not all in parallel. Group 1 (foundation: entities, interfaces, config) runs first. Group 2 (domain logic, adapters) runs after Group 1 completes — agents can read Group 1's actual files instead of relying on prompt descriptions. Group 3 (controllers, integration) runs after Group 2. This is slightly slower but significantly more reliable.

**Alternative:** If parallel speed is critical, have each agent write a manifest file listing the types it created (name, package, key method signatures). Subsequent agents read manifests from earlier agents before starting.

**Problem 2: Per-file validation is pure overhead.** Running `validate-phase.py --action write --path "..."` before each of 30+ file writes in Phase 4 adds latency but catches nothing — all `src/` writes are allowed.

**Recommendation:** Replace per-file validation with a pre-phase bulk validation. At the start of Phase 4, validate the list of planned files against phase permissions once. Skip per-file checks during execution. Re-validate at the end if the agent created files not in the original plan.

**Problem 3: Checkbox updates are manual.** After agents complete, the orchestrator reads the implementation plan and flips each `- [ ]` to `- [x]`. This is bookkeeping, not engineering.

**Recommendation:** Have each agent return a structured completion report listing which tasks from the plan it completed. The orchestrator auto-updates checkboxes from the reports.

---

## The Missing Phase: Review-Fix Loop

**Problem:** The feature request skill ends at Phase 4 (implementation complete, tests pass, summary written). But the FR-023 session showed that 5 rounds of PR review found 8 bugs — all after Phase 4 was "complete." The review-fix cycle was ad-hoc, happening outside the skill's structure.

**Recommendation:** Add **Phase 5: Review & Harden** as a formal phase.

**How it works:**
1. Create PR after Phase 4
2. Wait for automated review comments
3. For each comment: assess validity, apply fix if valid, push, re-run tests
4. Repeat until no unresolved comments + CI green
5. Exit criteria: clean review + all tests passing

**Why it matters:** This is where the deepest bugs were found in FR-023. Making it a formal phase means it gets the same structure, validation, and documentation as the other phases — instead of being a manual follow-up.

---

## The Missing Piece: Test-First Gate

*Supersedes `docs/plans/feature-request-skill-test-first.md` — the original plan identified the right problem (FR-003/FR-004 unreachable state transitions) and proposed test specifications as a planning exercise. This section evolves that idea from pseudocode specs into runnable tests that drive implementation.*

### The problem — two incidents, same root cause

**Incident 1 (FR-003/FR-004):** FR-003 defined the full SKU state machine but didn't document what triggers intermediate transitions. FR-004 and FR-005 built API endpoints requiring the SKU to already be in a downstream state. The gap wasn't discovered until the full API flow was exercised manually with seed data. A test like "create SKU → verify costs → stress test → assert LISTED" would have surfaced the unreachable transitions immediately.

**Incident 2 (FR-023):** The implementation plan described `@TransactionalEventListener(AFTER_COMMIT)` as "async processing" — a misnomer. No test asserted "HTTP response returns within 5 seconds" because the tests validated behavior, not timing. A test written from the spec's NFR-4 ("must return within Shopify's 5-second timeout") would have forced the question: "is this actually async?" before implementation.

Both incidents share the same root cause: tests were shaped by the code, not by the requirements. They validated what was built, not what should have been built.

### The recommendation

Add **Phase 3b: Test-First Gate** between Phase 3 (Planning) and Phase 4 (Implementation).

### How it works

1. After the implementation plan is approved, generate three categories of tests from the spec and plan:

   **End-to-end flow tests** — trace the full user-facing path that exercises the feature across module boundaries. These are the tests that would have caught the FR-003/FR-004 gap. For FR-023, this would be: "Shopify sends order notification → system verifies signature → creates internal order → order appears in PENDING status with correct SKU and vendor."

   **Boundary condition tests** — enumerate the error and edge cases the spec calls out. Invalid inputs, missing prerequisites, concurrent requests, unsupported values. For FR-023: unsupported currency, malformed timestamp, duplicate event ID, unresolvable SKU, multi-line-item with partial resolution.

   **Dependency contract tests** — for any feature that depends on another feature's data or state, assert that the prerequisite is reachable through the current system. For FR-023: "platform_listings table has records for active Shopify listings" (depends on FR-020), "vendor_sku_assignments table has active assignments" (depends on FR-007).

2. Tests are real, runnable code — not pseudocode or Gherkin. They compile against existing interfaces and reference types from the implementation plan. They fail immediately because the implementation doesn't exist yet. This is expected and correct.

3. Phase 4 agents receive these tests as part of their context. Their job shifts from "implement the plan" to "make these tests pass." This is a more concrete, verifiable target.

4. When a test can't be made to pass, the question becomes: **is the test wrong, or is the approach wrong?** This forces a conversation about intent vs. reality — a far more productive debugging question than "does this code work?"

### Why this belongs inside the feature request skill

The test-first gate reads the spec (Phase 2 output) and the implementation plan (Phase 3 output) to generate tests. It produces files that Phase 4 agents consume. It has no value outside the context of a feature being planned and implemented. It's a phase of the same workflow, not a separate skill. Single responsibility still holds — the feature request skill owns the full lifecycle from requirement to tested code.

### What changes in the skill

- `.claude/skills/feature-request/SKILL.md` — add Phase 3b description between Planning and Implementation
- `.claude/skills/feature-request/scripts/validate-phase.py` — add phase 3b validation (write permissions for test files only, no source files)
- Phase 4 agent prompts — include "make these tests pass" alongside "implement the plan"

### Cost vs. payoff

The cost is ~10-15 minutes of test generation per feature. The payoff is catching two categories of bugs that the current workflow misses entirely: integration gaps between features (FR-003/FR-004 class) and spec-implementation misalignment (FR-023 async misconception class). These are the bugs that survive unit tests, pass code review, and surface only in production or during manual E2E testing.

---

## Toward Automated Dispatch: Linear → PR

The long-term goal is a pipeline where Linear tickets flow to merged PRs with minimal human involvement. The feature request skill handles *how* to build. The missing piece is *when* to build and *who* decides to start.

### What's needed

**1. Ticket triage gate.** Not every ticket should be auto-processed. Score each ticket on:
- **Specificity:** Does it have acceptance criteria? (Y/N)
- **Blast radius:** How many modules does it touch? (1 = low, 3+ = high)
- **Novelty:** Does it introduce new patterns or just follow existing ones? (meta_controller.py already computes this)
- **Risk:** Does it touch financial logic, security, or data models? (high = human review)

High confidence + low blast radius + low risk = auto-dispatch. Otherwise = notify human.

**2. Persistent dispatch process.** Claude Code runs in terminal sessions, not as a daemon. Options:
- **GitHub Action on a schedule** — runs every N hours, checks Linear for "Todo" tickets that meet the triage threshold, dispatches a Claude Code session for each
- **Linear webhook → GitHub Action** — triggers on ticket status change, more responsive than polling
- **Cron job** — simplest, invokes `claude` CLI with the ticket context

**3. Configurable phase approvals.** The feature request skill currently hardcodes human approval between every phase. Make this configurable:
- **Auto-approve:** Phase deliverables pass validation + ticket meets confidence threshold → proceed without human input
- **Human-approve:** Low confidence, high risk, or novel patterns → wait for explicit approval
- **Hybrid:** Auto-approve phases 1-3 (they're read-only or doc-only), require human approval before Phase 4 (implementation)

**4. Review-fix loop as Phase 5.** Already described above. This closes the gap between "code written" and "PR mergeable."

**5. Merge gate.** Even with full automation, the final merge should be a conscious decision. Options:
- Auto-merge if: CI green + no unresolved review comments + blast radius below threshold
- Notify human for merge approval if: blast radius high, touches financial logic, or modifies shared infrastructure

### Architecture

```
Linear (Todo tickets)
  │
  ▼
Triage Gate
  ├── High confidence → auto-dispatch
  └── Low confidence  → notify human, wait for approval
        │
        ▼
  Feature Request Skill
  ├── Phase 1 (Discovery)     — skip or abbreviate for rich tickets
  ├── Phase 2 (Specification) — enrich ticket, don't duplicate it
  ├── Phase 3 (Planning)      — full architectural design (always)
  ├── Phase 3b (Test-First)   — generate tests from spec (new)
  ├── Phase 4 (Implementation)— agents make tests pass
  └── Phase 5 (Review+Harden) — PR review loop until clean (new)
        │
        ▼
  Merge Gate
  ├── Low risk  → auto-merge
  └── High risk → notify human for final approval
```

### Incremental rollout

These improvements don't need to ship together. Recommended order:

1. **Phase 5 (Review-Fix Loop)** — Highest immediate value. This is where the bugs are found. Formalizing it captures the pattern that already works.
2. **Conditional Phase 1-2** — Reduces time-to-implementation for well-specified tickets. Low risk since it's skipping read/write-only phases, not implementation.
3. **Test-first gate** — Changes the implementation dynamic. Needs experimentation to calibrate how much spec detail is needed for useful test generation.
4. **Phase 4 agent coordination** — Dependency-ordered groups instead of fully parallel. Trades some speed for reliability.
5. **Triage gate + dispatch** — The automation layer. Depends on the other improvements being stable first.
6. **Merge gate** — The final piece. Only safe after the review-fix loop is proven reliable.

---

## Summary

| Improvement | Type | Impact | Effort |
|---|---|---|---|
| Phase 5: Review-Fix Loop | New phase | High — catches the deepest bugs | Medium |
| Conditional Phase 1-2 | Optimization | Medium — saves 15-30 min per ticket | Low |
| Test-first gate (Phase 3b) | New gate | High — catches spec-implementation misalignment | Medium |
| Phase 4 agent dependency ordering | Reliability | Medium — prevents cross-agent compilation failures | Low |
| Phase 4 bulk validation | Optimization | Low — removes per-file overhead | Low |
| Phase 4 auto-checkbox updates | Optimization | Low — removes manual bookkeeping | Low |
| Ticket triage gate | Automation | High — enables unattended dispatch | Medium |
| Persistent dispatch process | Infrastructure | High — enables Linear → PR pipeline | Medium |
| Configurable phase approvals | Automation | High — removes human bottleneck for routine work | Medium |
| Merge gate | Automation | Medium — closes the full loop | Low |

The north star: a well-written Linear ticket becomes a merged PR without a human touching the keyboard — unless the system encounters something it's not confident about, in which case it stops and asks. Every improvement on this list is a step toward that goal, and each one is independently valuable.
