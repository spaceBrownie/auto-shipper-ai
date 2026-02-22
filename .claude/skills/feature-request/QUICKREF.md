# Feature Request Workflow - Quick Reference

## Trigger Phrases

```
"I want to add a new feature for [description]"
"Create a feature request for [description]"
"Let's implement [description]"
```

## The 4 Phases

| Phase | Name | Purpose | Can Read | Can Write | Deliverable |
|-------|------|---------|----------|-----------|-------------|
| 1 | Discovery | Explore codebase | ✅ All files | ❌ Nothing | Feature name |
| 2 | Specification | Document requirements | ✅ All files | ✅ spec.md only | spec.md |
| 3 | Planning | Design solution | ✅ All files | ✅ implementation-plan.md only | implementation-plan.md |
| 4 | Implementation | Write code | ✅ All files | ✅ Code + plan + summary | Code + summary.md |

## Validation Commands

### Feature Name
```bash
# Validate name
python3 .claude/skills/feature-request/scripts/validate-phase.py --feature-name "jwt-refresh-tokens"
```

### Phase Permissions
```bash
# Get phase info
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action info

# Check read permission
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action read --path "src/Foo.java"

# Check write permission
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 2 --action write --path "feature-requests/FR-001/spec.md"

# Check bash command
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action bash --command "ls"
```

### Deliverables
```bash
# Check deliverables complete
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-name"

# Get next FR number
python3 .claude/skills/feature-request/scripts/validate-phase.py --next-fr-number
```

### Strategy Policy (Meta-Controller)
```bash
# Recommend strategy for current state/phase
python3 .claude/skills/feature-request/scripts/meta_controller.py --phase 4 --json --out feature-requests/FR-001-name/decision-support/preflight-meta-controller.json

# JSON output for automation
python3 .claude/skills/feature-request/scripts/meta_controller.py --phase 4 --json
```

### Decision-Support Enforcement
```bash
# Validate required preflight decision artifact in phase 4
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --check-decision-support --feature-dir "feature-requests/FR-001-name"
```

### Audit/Evaluation
```bash
# Evaluate policy across reference scenarios
python3 .claude/skills/feature-request/scripts/evaluate_meta_controller.py --fail-on-mismatch

# Run regression tests
python3 -m unittest discover -s .claude/skills/feature-request/tests -p "test_*.py"
```

## Feature Naming Rules

✅ **Valid:** `jwt-refresh-tokens`, `rate-limiting`, `health-check`
❌ **Invalid:** `JWTTokens` (uppercase), `jwt_tokens` (underscores), `jwt tokens` (spaces)

**Pattern:** `^[a-z0-9-]+$` (3-50 chars, kebab-case)

## FR Directory Structure

```
feature-requests/FR-{NNN}-{feature-name}/
├── spec.md                    # Phase 2 - Requirements
├── implementation-plan.md     # Phase 3 - Technical design + tasks
└── summary.md                 # Phase 4 - Implementation summary
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

### summary.md (Phase 4)
- Feature Summary
- Changes Made
- Files Modified
- Testing Completed
- Deployment Notes

## Phase 4 Sub-Agents

| Agent | Layer | Responsibility |
|-------|-------|----------------|
| orchestrator | - | Coordinates work, updates plan |
| handler-agent | handler | REST endpoints |
| domain-agent | domain | Business logic |
| proxy-agent | proxy | External clients |
| security-agent | security | Auth/JWT |
| config-agent | config | Spring beans |
| common-agent | common | DTOs/exceptions |

## Manual Approval Points

1. **Phase 1 → 2:** User approves feature name
2. **Phase 2 → 3:** User approves spec.md
3. **Phase 3 → 4:** User approves implementation-plan.md
4. **Phase 4 complete:** Tests pass, summary.md created

## Common Errors

| Error | Cause | Solution |
|-------|-------|----------|
| `❌ Phase X does not allow writing: Y` | Wrong phase for action | Move to correct phase |
| `❌ Missing required section: X` | Incomplete deliverable | Add missing section |
| `❌ Invalid feature name` | Name not kebab-case | Use lowercase + hyphens only |
| `❌ Unchecked tasks` | Tasks not complete | Finish tasks or update plan |

## Exit Codes

- `0` = Success/Allowed
- `1` = Failure/Denied

## Quick Test

```bash
# Test installation
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action info

# Expected: Shows Phase 1 permissions and deliverables
```

## Documentation

- **Quick Start:** README.md
- **Full Guide:** references/workflow-guide.md
- **Skill Definition:** SKILL.md
- **Configuration:** references/feature-workflow.yaml
- **Policy Scenarios:** references/meta-controller-scenarios.json
- **Policy Explainer:** references/meta-controller-explained.md

---

**Version:** 1.1.0 | **Updated:** 2026-02-10
