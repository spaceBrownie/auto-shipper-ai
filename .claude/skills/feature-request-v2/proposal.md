# Proposal: Feature-Request-v2 State Machine Refactor (RAT-36)

## Context

PM-017 documents a comprehensive failure of the v2 skill's second real execution (FR-025/RAT-27):
- 65 tests produced, ~50 were theater (`assert(true)`, fixture content checks, constructor round-trips)
- NullNode data integrity bug caught only by Unblocked PR review bot
- Meta-controller recommendation ignored (recommended 4 agents deliberative; orchestrator used 1 agent pass-through)
- Auto-advance removed the human quality gate that made v1 work (FR-001 through FR-023)

**Root cause:** v2 automated away the human quality gate. The 800-line SKILL.md is lossy-summarized to subagents. Auto-advance between phases removes every checkpoint where the human caught bugs in v1.

**Goal:** Refactor the v2 skill from a monolithic 800-line SKILL.md into a state-machine architecture driven by `validate-phase.py` + `feature-workflow.yaml`.

## Source

- Linear ticket: RAT-36
- Postmortem: `docs/postmortems/PM-017-feature-request-v2-test-theater-regression.md`
- Nathan Report: `docs/nathan-reports/NR-006-feature-request-v2-skill-evaluation.md`

---

## Files to Modify

### 1. SKILL.md (~857 lines -> ~200 lines)

**Current:** Monolithic document containing the entire 6-phase workflow with per-phase instructions, permissions, examples, error handling, validation rules, and a full example session.

**Proposed:** Thin orchestrator document covering only:
- Overview of the 6-phase flow (~20 lines)
- Phase-gate model: auto-advance phases 1-3 (read-heavy, low-risk); mandatory human gate before Phase 5 (implementation) and Phase 6 (PR creation) (~15 lines)
- How to query the state machine: `validate-phase.py --phase N --instructions` — this is the single way subagents get their instructions (~15 lines)
- Subagent prompt model: "Run `validate-phase.py --phase N --instructions` and follow the output exactly" — eliminates lossy summarization (~10 lines)
- Meta-controller usage at Phase 5 boundary — run preflight, present to user at gate, follow autonomously within phase (~15 lines)
- `/unblock` integration: "Use unblock as needed" at every phase (proven v1 pattern), not prescribed hydration points (~10 lines)
- E2E test playbook integration as mandatory post-Phase 5 step: reference `@docs/e2e-test-playbook.md` (~10 lines)
- Error handling / what to do when blocked (~10 lines)
- Test quality rules (global, applies to any phase that produces tests): ban `assert(true)`, fixture-only assertions (`payload.contains(...)`), `// Phase 5:` deferred comments. If production code doesn't exist for a test, put it in test-spec.md. (~15 lines)
- Override policy: if orchestrator overrides meta-controller, must document in `decision-support/override-justification.md` with (1) the recommendation, (2) what was done instead, (3) why, (4) which state parameter the meta-controller got wrong (~10 lines)

**What to remove from SKILL.md (moves to YAML `instructions` blocks):**
- All per-phase "Actions" sections (steps 1-N for each phase)
- All per-phase permission listings (already in YAML, already served by validate-phase.py)
- All per-phase deliverable details (already in YAML)
- The entire "Example Session" section
- The "Testing the Workflow" section
- The "Critical Validation Rules" section (becomes just: "run validate-phase.py before every action")
- The "Integration with AGENTS.md" section (moves into Phase 5 instructions in YAML)
- The "Error Handling" section details (covered by validate-phase.py output)
- The sub-agent architecture table and execution order (moves into Phase 5 instructions in YAML)

**Keep in SKILL.md:**
- Metadata block (name, version, trigger_phrases, description)
- Version history

### 2. feature-workflow.yaml

**Current:** Defines per-phase permissions, deliverables, transitions, and validation rules. Does NOT contain execution instructions.

**Proposed:** Add an `instructions_file` pointer to each phase referencing an isolated `.md` file in `references/instructions/`. Each file contains ~50-80 lines of execution instructions. `validate-phase.py --phase N --instructions` resolves the pointer, reads the file, and outputs it fresh.

Architecture:
```
references/instructions/
├── phase-1-discovery.md
├── phase-2-specification.md
├── phase-3-planning.md
├── phase-4-test-specification.md
├── phase-5-implementation.md
└── phase-6-review-fix-loop.md
```

Why separate files instead of YAML inline:
- Each phase is an isolated unit — changes to Phase 3 don't touch Phase 5
- Clean markdown authoring (no YAML escaping)
- Each file is ~50-80 lines — perfect for eager chunking into subagent context
- Each phase could technically become its own skill

#### Phase-by-phase instruction content:

**Phase 1 (Discovery) instructions (~50 lines):**
- Load workflow configuration via validate-phase.py
- Use /unblock as needed — query `unblocked_context_engine` with Linear ticket and feature area for related PRs, prior attempts, rejected approaches
- Explore codebase (read-only) — validate each read via validate-phase.py
- Generate kebab-case feature name, validate via script
- Gut-check with Unblocked before returning

**Phase 2 (Specification) instructions (~60 lines):**
- Get next FR number via validate-phase.py
- Use /unblock as needed for prior specs, business requirements discussions
- Create feature directory
- Write spec.md with required sections (Problem Statement, Business Requirements, Success Criteria, Non-Functional Requirements, Dependencies)
- Do NOT include Implementation Details, Technical Design, Code Changes
- Gut-check with Unblocked before finalizing
- Validate deliverables via script

**Phase 3 (Planning) instructions (~70 lines):**
- Read spec.md for requirements
- Use /unblock as needed for team conventions, prior implementations, architectural decisions
- Design technical solution following DDD/hexagonal architecture
- Identify affected layers
- Write implementation-plan.md with required sections (Technical Design, Architecture Decisions, Layer-by-Layer Implementation, Task Breakdown with checkboxes, Testing Strategy, Rollout Plan)
- Keep task breakdown explicit (`- [ ] ...` per task) so meta-controller can infer workload state
- Gut-check with Unblocked before finalizing
- Validate deliverables via script

**Phase 4 (Test Specification) instructions (~80 lines):**
IMPORTANT: This is a redesign. Phase 4 is renamed from "Test-First Gate" to "Test Specification".

- Read spec.md and implementation-plan.md
- Use /unblock as needed for existing test patterns, fixture conventions
- Primary deliverable: `test-spec.md` (NOT test files). Contents:
  - **Acceptance Criteria** — testable assertions derived from spec success criteria
  - **Fixture Data** — JSON payloads, edge case values, null/missing/malformed inputs
  - **Boundary Cases** — explicitly including JSON null for every external API field extraction, missing fields, type mismatches, threshold values (margin boundaries, rate limits)
  - **E2E Playbook Scenarios** — new scenarios to add to `@docs/e2e-test-playbook.md` for this feature
  - **Contract Test Candidates** — ONLY for pure domain types: state machine rules, domain event structure, interface signatures. These are the ~10% that CAN compile meaningfully against stubs.
- Optional: write contract tests for domain types listed above. These must call production code (or meaningful stubs) and assert on output. Ban `assert(true)`, fixture-only assertions, `// Phase 5:` deferred comments.
- If production code doesn't exist for a test category, put it in test-spec.md instead of writing a placeholder test file.
- Validate deliverables via script

**Phase 5 (Implementation) instructions (~80 lines):**
- Run strategy preflight (required): `meta_controller.py --phase 5 --json --out ...`
- Present meta-controller recommendation to user at the Phase 4->5 human gate
- Follow meta-controller recommendations autonomously within phase (parallel agents, chunking, cognition mode). Override requires written justification in `decision-support/override-justification.md`.
- Orchestrator reads implementation-plan.md and test-spec.md
- Spawn layer-specific sub-agents in dependency-ordered groups:
  - Group 1 (Foundation): config-agent, common-agent
  - Group 2 (Domain Logic): domain-agent, proxy-agent, security-agent
  - Group 3 (Integration): handler-agent
- Each sub-agent reads its layer's AGENTS.md
- TDD alongside implementation: write test, implement, make test pass, repeat. Use test-spec.md as the test design guide.
- Use /unblock as needed throughout implementation
- After all tasks complete, run E2E test playbook: `@docs/e2e-test-playbook.md`
- Update implementation-plan.md checkboxes as tasks complete
- Run full test suite: `./gradlew test`
- Create summary.md
- Validate completion via script
- Recommended: run policy regression audit (evaluate_meta_controller.py + unit tests)

**Phase 6 (Review-Fix Loop) instructions (~60 lines):**
- Create PR using `gh pr create`
- Check CI status via `gh pr checks`
- Fetch review comments via `gh pr view --comments`
- Use /unblock to review PR before pushing (proven v1 pattern)
- Assess and fix review comments and CI failures
- Before writing fixes, validate permissions via validate-phase.py
- Run tests locally before pushing
- Push fixes and re-check
- Repeat until: all CI checks pass, all review comments resolved, no new comments raised
- No manual approval needed — loop exits automatically when criteria are met

#### Transition changes:

Update `requires_manual_approval` in YAML:
- Phases 1-3: `requires_manual_approval: false` (auto-advance, low-risk read-heavy phases)
- Phase 4: `requires_manual_approval: true` (human reviews test-spec.md before implementation)
- Phase 5: `requires_manual_approval: true` (human gate — implementation is irreversible. Meta-controller preflight is presented at this gate.)
- Phase 6: `requires_manual_approval: true` (human gate — PR is visible to others)

#### Phase 4 deliverable changes:

- Rename phase key: `phase_4_test_first_gate` -> `phase_4_test_specification`
- Rename: `name: "Test-First Gate"` -> `name: "Test Specification"`
- Primary deliverable: `test-spec.md` (replace `test-manifest.md`)
- Required sections for test-spec.md:
  - "Acceptance Criteria"
  - "Fixture Data"
  - "Boundary Cases"
  - "E2E Playbook Scenarios"
  - "Contract Test Candidates"
- Optional deliverable: contract test files (domain types only)
- Remove: `"./gradlew compileTestKotlin"` from required bash (tests are optional in Phase 4)
- Keep: `"./gradlew compileTestKotlin"` in allowed bash (for optional contract tests)

#### Phase 5 deliverable changes:

- Add `decision-support/override-justification.md` as an optional artifact (required only when overriding meta-controller)
- Add E2E test playbook execution as a validation step

### 3. validate-phase.py

**Current:** Validates actions (read/write/bash), feature names, deliverables, decision support artifacts. Outputs phase info via `--phase N --action info`.

**Proposed additions:**

Add `--phase N --instructions` flag that:
1. Loads the `instructions` block from the YAML for the given phase
2. Outputs the full execution instructions as formatted text (~50-80 lines)
3. This becomes the primary interface for subagents to get their marching orders

Implementation:
- Add `--instructions` flag to argparse (boolean, mutually exclusive with `--action`)
- In `PhaseValidator`, add `get_phase_instructions(phase_number)` method that returns the `instructions` string from YAML
- Format output with phase name header, instructions body, and a footer reminding about validation commands
- JSON mode (`--json`) wraps instructions in `{"phase": N, "name": "...", "instructions": "..."}`

Also update:
- Phase 4 deliverable checking: look for `test-spec.md` instead of `test-manifest.md`
- Phase 4 required sections: update to match new test-spec.md sections
- Update any hardcoded references to "Test-First Gate" -> "Test Specification"

### 4. meta_controller.py

**No functional changes needed.** The meta-controller's recommendations were correct in PM-017 — the problem was the orchestrator ignoring them. The binding is enforced via SKILL.md and YAML instructions, not via script changes.

### 5. evaluate_meta_controller.py

**No functional changes needed.** Scenarios should still pass after YAML restructuring since the meta-controller logic is unchanged.

### 6. meta-controller-scenarios.json

**No changes needed.** Existing scenarios test the meta-controller's decision logic, which is not changing.

### 7. test_meta_controller.py

**Minor update:** After YAML restructuring, verify that the new YAML structure doesn't break existing tests. The tests reference `WORKFLOW_PATH` which points to the same file. As long as the existing `phases` structure is preserved (which it will be — we're adding `instructions` blocks, not removing existing config), tests should pass without changes.

Add new test: verify `validate-phase.py --phase N --instructions` returns non-empty output for all 6 phases.

### 8. QUICKREF.md

**Update to reflect:**
- Phase 4 rename: "Test-First Gate" -> "Test Specification"
- Primary Phase 4 deliverable: `test-spec.md` (not `test-manifest.md`)
- New validation command: `validate-phase.py --phase N --instructions`
- Updated FR directory structure (test-spec.md replaces test-manifest.md)
- Updated manual approval points (human gates before 5 and 6)

### 9. workflow-guide.md

**Update to reflect:**
- Phase 4 redesign (Test Specification)
- Human gate model (auto-advance 1-3, gates before 5 and 6)
- State machine architecture (query validate-phase.py for instructions)
- E2E test playbook integration
- Meta-controller binding and override policy

### 10. CLAUDE.md (project root)

**Add constraint #19:**
```
19. **All Jackson `get()?.asText()` calls on external JSON payloads must use the NullNode guard** — `?.let { if (!it.isNull) it.asText() else null }`. Plain `?.asText()` returns `"null"` (the string) for JSON `null` values (`NullNode.asText()` returns `"null"`, not Kotlin null). Apply the guard consistently — if one field in a block uses it, all fields must.
```

### 11. meta-controller-explained.md

**No changes needed.** This is a reference doc explaining the math behind the meta-controller.

---

## Acceptance Criteria (from RAT-36)

- [ ] SKILL.md reduced to ~200 lines (orchestrator doc)
- [ ] `validate-phase.py --phase N --instructions` outputs full phase execution instructions
- [ ] Phase 4 deliverable is `test-spec.md`, not test files
- [ ] Human gates before Phase 5 and Phase 6 in workflow YAML transitions
- [ ] Meta-controller override requires `override-justification.md`
- [ ] E2E test playbook integrated as mandatory post-Phase 5 step
- [ ] CLAUDE.md constraint #19 added (NullNode guard)
- [ ] `evaluate_meta_controller.py` passes all scenarios after changes
- [ ] `validate-phase.py` unit tests pass after changes

---

## Implementation Order

These changes have dependencies — order matters:

1. **CLAUDE.md constraint #19** — standalone, no dependencies
2. **feature-workflow.yaml** — add `instructions` blocks to all 6 phases, update Phase 4 deliverables/naming, update `requires_manual_approval` flags, update transition rules
3. **validate-phase.py** — add `--instructions` flag, update Phase 4 deliverable checking for `test-spec.md`
4. **SKILL.md** — rewrite as thin orchestrator referencing `validate-phase.py --phase N --instructions`
5. **QUICKREF.md** — update to match new structure
6. **workflow-guide.md** — update to match new structure
7. **test_meta_controller.py** — add test for `--instructions` flag, verify existing tests pass
8. **Run evaluator + tests** — `evaluate_meta_controller.py --fail-on-mismatch` + `python3 -m unittest discover`

Steps 1-3 can be parallelized. Step 4 depends on 2-3. Steps 5-6 depend on 4. Step 7 depends on 2-3. Step 8 depends on all.

---

## What NOT to Change

- **meta_controller.py** — decision logic is correct per PM-017 analysis
- **evaluate_meta_controller.py** — evaluator logic is correct
- **meta-controller-scenarios.json** — scenario expectations unchanged
- **meta-controller-explained.md** — reference doc still accurate
- **The v1 skill** (`.claude/skills/feature-request/`) — retained as fallback per MEMORY.md

---

## Key Design Decisions

### Why state machine via validate-phase.py?

From PM-017's architectural insight:
- No ambiguity about current state (phase number is explicit)
- Instructions per state loaded fresh from YAML (not summarized from 800-line doc)
- Each phase's instructions are ~50-80 lines (fits in subagent context without loss)
- Script is single source of truth — SKILL.md, YAML, and script cannot drift
- State machine ontology is structurally hard to fail
- Subagent prompt becomes: "Run `validate-phase.py --phase N --instructions` and follow the output exactly"

### Why human gates before Phase 5 and 6 (not all phases)?

From PM-017's regression analysis:
- v1 had human gates between every phase — this is what made it work
- Phases 1-3 are read-heavy and low-risk — auto-advance is safe
- Phase 5 (implementation) is irreversible — human should review plan + test-spec + meta-controller recommendation before committing
- Phase 6 (PR creation) is visible to others — human should approve before creating PR
- Within Phase 5, meta-controller recommendations are followed autonomously (same as v1's Phase 4)

### Why test-spec.md instead of test files in Phase 4?

From PM-017's Phase 4 structural problem analysis:
- Behavioral tests against code that doesn't exist can only be placeholders
- This isn't TDD — TDD is iterative (write one test, make it pass)
- Phase 4 wrote 65 tests that all fail, hoping Phase 5 makes them pass at once
- test-spec.md defines WHAT to test in testable terms; Phase 5 writes the actual tests alongside implementation in TDD style
- Optional contract tests for pure domain types (state machines, events, interfaces) are the ~10% that CAN compile meaningfully

### Why "/unblock as needed" instead of prescribed hydration points?

From PM-017's regression analysis:
- v1 pattern: user said "use unblock as needed" — proved effective across FR-001 to FR-023
- v2 prescribed specific hydration points ("before drafting", "before finalizing") — not enforced, not used
- The simpler instruction works better because it's a standing directive, not a checklist item
