# Feature Request Workflow v3 - Quick Reference

## Trigger Phrases

```
"I want to add a new feature for [description]"
"Create a feature request for [description]"
"Let's implement [description]"
```

## The 6 Phases

| Phase | Name | Purpose | Can Read | Can Write | Deliverable |
|-------|------|---------|----------|-----------|-------------|
| 1 | Discovery | Explore codebase | All files | Nothing | Feature name |
| 2 | Specification | Document requirements | All files | spec.md only | spec.md |
| 3 | Planning | Design solution | All files | implementation-plan.md only | implementation-plan.md |
| 4 | Test Specification | Define test strategy before code | All files | test-spec.md only | test-spec.md |
| 5 | Implementation | Write code, make tests pass | All files | Code + plan + summary | Code + summary.md |
| 6 | Review-Fix Loop | Validate and fix issues | All files | Code + test fixes only | All tests green + final sign-off |

## Validation Commands

### Feature Name
```bash
# Validate name
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --feature-name "jwt-refresh-tokens"
```

### Phase Permissions
```bash
# Get phase info
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action info

# Get full execution instructions for a phase
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase N --instructions

# Check read permission
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action read --path "src/Foo.java"

# Check write permission
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --action write --path "feature-requests/FR-001/spec.md"

# Check bash command
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action bash --command "ls"

# Check test specification permissions (Phase 4)
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action write --path "feature-requests/FR-001-name/test-spec.md"
```

### Deliverables
```bash
# Check deliverables complete
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-name"

# Check test-spec deliverable
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --check-deliverables --feature-dir "feature-requests/FR-001-name"

# Get next FR number
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --next-fr-number
```

### Strategy Policy (Meta-Controller)
```bash
# Recommend strategy for current state/phase
python3 .claude/skills/feature-request-v2/scripts/meta_controller.py --phase 5 --json --out feature-requests/FR-001-name/decision-support/preflight-meta-controller.json

# JSON output for automation
python3 .claude/skills/feature-request-v2/scripts/meta_controller.py --phase 5 --json
```

### Decision-Support Enforcement
```bash
# Validate required preflight decision artifact in phase 5
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --check-decision-support --feature-dir "feature-requests/FR-001-name"
```

### Audit/Evaluation
```bash
# Evaluate policy across reference scenarios
python3 .claude/skills/feature-request-v2/scripts/evaluate_meta_controller.py --fail-on-mismatch

# Run regression tests
python3 -m unittest discover -s .claude/skills/feature-request-v2/tests -p "test_*.py"
```

## Feature Naming Rules

Valid: `jwt-refresh-tokens`, `rate-limiting`, `health-check`
Invalid: `JWTTokens` (uppercase), `jwt_tokens` (underscores), `jwt tokens` (spaces)

**Pattern:** `^[a-z0-9-]+$` (3-50 chars, kebab-case)

## FR Directory Structure

```
feature-requests/FR-{NNN}-{feature-name}/
├── spec.md                    # Phase 2 - Requirements
├── implementation-plan.md     # Phase 3 - Technical design + tasks
├── test-spec.md               # Phase 4 - Test specification: acceptance criteria + boundary cases
└── summary.md                 # Phase 5 - Implementation summary
```

## Required Sections

### spec.md (Phase 2)
- Problem Statement
- Business Requirements
- Success Criteria
- Non-Functional Requirements
- Dependencies

### implementation-plan.md (Phase 3)
- Technical Design
- Architecture Decisions
- Layer-by-Layer Implementation
- Task Breakdown (with checkboxes)
- Testing Strategy
- Rollout Plan

### test-spec.md (Phase 4)
- Acceptance Criteria
- Fixture Data
- Boundary Cases
- E2E Playbook Scenarios
- Contract Test Candidates

### summary.md (Phase 5)
- Feature Summary
- Changes Made
- Files Modified
- Testing Completed
- Deployment Notes

## Phase 5 Sub-Agents

Sub-agents execute in dependency-ordered groups. Earlier groups must complete before later groups begin.

| Group | Agent | Layer | Responsibility |
|-------|-------|-------|----------------|
| - | orchestrator | - | Coordinates work, updates plan, creates summary |
| 1 | common-agent | common | DTOs/exceptions (no internal deps) |
| 1 | config-agent | config | Spring beans, properties (no internal deps) |
| 2 | domain-agent | domain | Business logic (depends on common) |
| 2 | proxy-agent | proxy | External clients (depends on common, config) |
| 3 | security-agent | security | Auth/JWT (depends on domain, config) |
| 4 | handler-agent | handler | REST endpoints (depends on domain, security) |

Within each group, agents may run in parallel. The orchestrator sequences groups and verifies inter-group contracts before advancing.

## Manual Approval Points

1. **Phase 1 -> 2:** Auto-advance
2. **Phase 2 -> 3:** Auto-advance
3. **Phase 3 -> 4:** Auto-advance
4. **Phase 4 -> 5:** Human gate — user approves test-spec.md before implementation begins
5. **Phase 5 -> 6:** Human gate — user approves implementation before review-fix loop
6. **Phase 6 complete:** Automated — all tests green, no unresolved review findings

## Common Errors

| Error | Cause | Solution |
|-------|-------|----------|
| `Phase X does not allow writing: Y` | Wrong phase for action | Move to correct phase |
| `Missing required section: X` | Incomplete deliverable | Add missing section |
| `Invalid feature name` | Name not kebab-case | Use lowercase + hyphens only |
| `Unchecked tasks` | Tasks not complete | Finish tasks or update plan |
| `Test-spec missing required section` | Incomplete test specification | Add missing section to test-spec.md |

## Exit Codes

- `0` = Success/Allowed
- `1` = Failure/Denied

## Quick Test

```bash
# Test installation
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action info

# Expected: Shows Phase 1 permissions and deliverables
```

## Documentation

- **Full Guide:** references/workflow-guide.md
- **Skill Definition:** SKILL.md
- **Configuration:** references/feature-workflow.yaml
- **Policy Scenarios:** references/meta-controller-scenarios.json
- **Policy Explainer:** references/meta-controller-explained.md

---

**Version:** 3.0.0 | **Updated:** 2026-03-28
