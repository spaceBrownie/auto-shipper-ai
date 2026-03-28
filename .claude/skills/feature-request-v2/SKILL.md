# Feature Request Workflow Skill (v2)

A structured 6-phase workflow for managing feature requests, driven by a state machine architecture. Each phase's execution instructions are loaded fresh from isolated `.md` files via `validate-phase.py --phase N --instructions`.

## Metadata

```yaml
name: feature-request-v2
version: 3.0.0
trigger_phrases:
  - "I want to add a new feature"
  - "Create a feature request for"
  - "Let's implement"
  - "Start a feature workflow"
  - "Start a v2 feature workflow"
  - "New feature:"
description: Manages feature development through 6 deterministic phases (Discovery, Specification, Planning, Test Specification, Implementation, Review-Fix Loop)
```

## The 6 Phases

| Phase | Name | Deliverable | Human Gate? |
|-------|------|-------------|-------------|
| 1 | Discovery | Feature name | No (auto-advance) |
| 2 | Specification | spec.md | No (auto-advance) |
| 3 | Planning | implementation-plan.md | No (auto-advance) |
| 4 | Test Specification | test-spec.md | **Yes** — before implementation |
| 5 | Implementation | Code + summary.md | **Yes** — before PR |
| 6 | Review-Fix Loop | Clean PR | Automated exit |

Phases 1-3 auto-advance (read-heavy, low-risk). Human approval is mandatory before Phase 5 (implementation is irreversible) and Phase 6 (PR is visible to others).

## State Machine Architecture

Per-phase instructions live in isolated markdown files, served by `validate-phase.py`:

```bash
# Get full execution instructions for any phase
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --instructions

# JSON mode (for automation)
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --instructions --json
```

**Subagent prompt model:** Each subagent runs:
```
validate-phase.py --phase N --instructions
```
and follows the output exactly. This eliminates lossy summarization of a long skill doc.

### Files

| File | Role |
|------|------|
| `references/feature-workflow.yaml` | Phase configs: permissions, deliverables, transitions, instruction file pointers |
| `scripts/validate-phase.py` | State machine gate: validates actions, serves instructions, checks deliverables |
| `references/instructions/phase-{N}-*.md` | Per-phase execution instructions (~50-80 lines each) |
| `scripts/meta_controller.py` | Strategy recommender for Phase 5 (parallel agents, chunking, cognition mode) |

## Execution Model

### Subagent Isolation (Phases 1-4)

Each of phases 1-4 runs in its own subagent. The orchestrator:
1. Spawns subagent with: `validate-phase.py --phase N --instructions`
2. Receives result + summary
3. Presents brief summary to user
4. Auto-advances (phases 1-3) or waits for approval (phase 4)

### Phase 5: Meta-Controller Binding

Before Phase 5, run the strategy preflight:
```bash
python3 .claude/skills/feature-request-v2/scripts/meta_controller.py --phase 5 --json \
  --out feature-requests/FR-{NNN}-{name}/decision-support/preflight-meta-controller.json
```

Present the recommendation to the user at the Phase 4→5 gate. Within Phase 5, follow meta-controller recommendations autonomously (parallel agents, chunking, cognition mode).

**Override policy:** If overriding the meta-controller, document in `decision-support/override-justification.md` with: (1) recommendation, (2) what was done instead, (3) why, (4) which state parameter was wrong. If no parameter is wrong, follow the recommendation.

### /unblock Integration

Use `/unblock` as needed at every phase — this is a standing directive, not prescribed hydration points. Query `unblocked_context_engine` for organizational context: PRs, Slack, docs, code history, prior attempts, rejected approaches.

### E2E Test Playbook

After Phase 5 implementation, execute `@docs/e2e-test-playbook.md` as a mandatory step. Add new scenarios from test-spec.md's "E2E Playbook Scenarios" section.

## Test Quality Rules

These apply to any phase that produces tests (Phase 4 optional contract tests, Phase 5 TDD):

- **No `assert(true)`** — if you can't write a meaningful assertion, document it in test-spec.md
- **No fixture-only assertions** (`payload.contains(...)`) — tests must call production code
- **No `// Phase 5:` deferred comments** — if it can't be tested now, it goes in test-spec.md
- **JSON `null` boundary cases required** for every external API field extraction (CLAUDE.md #17)

## Validation Commands

```bash
# Phase info
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --action info

# Permission checks
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --action read --path "{file}"
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --action write --path "{file}"
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --action bash --command "{cmd}"

# Deliverable checks
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"

# Feature name validation
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --feature-name "{name}"

# Next FR number
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --next-fr-number
```

## Error Handling

If validation fails: do NOT proceed. Report the error and suggest allowed alternatives based on phase permissions. Run `--phase N --action info` to see what's permitted.

## References

- **Workflow Config:** `references/feature-workflow.yaml`
- **Phase Instructions:** `references/instructions/phase-{N}-*.md`
- **Validation Script:** `scripts/validate-phase.py`
- **Meta Controller:** `scripts/meta_controller.py`
- **Policy Evaluator:** `scripts/evaluate_meta_controller.py`
- **Policy Scenarios:** `references/meta-controller-scenarios.json`
- **Quick Reference:** `QUICKREF.md`
- **Usage Guide:** `references/workflow-guide.md`

## Version History

- **v1.0.0** (2026-02-09): Initial implementation with 4-phase workflow
- **v2.0.0** (2026-03-25): Evolved to 6-phase workflow — added Phase 4 (Test-First Gate), Phase 6 (Review-Fix Loop), sub-agent dependency groups, bulk validation
- **v3.0.0** (2026-03-28): State machine refactor per PM-017 — Phase 4 redesigned to Test Specification (test-spec.md), human gates before phases 5/6, per-phase instruction files, meta-controller binding with override justification, E2E test playbook integration, test quality rules
