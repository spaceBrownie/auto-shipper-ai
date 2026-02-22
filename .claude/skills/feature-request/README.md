# Feature Request Workflow

A structured 4-phase workflow system for managing feature requests in Spring Boot projects with DDD/hexagonal architecture.

## Quick Start

### Trigger the Workflow

Say any of:
- "I want to add a new feature for [description]"
- "Create a feature request for [description]"
- "Let's implement [description]"

### Example

```
User: "I want to add JWT refresh token support"

Phase 1: Discovery (Read-Only)
→ Explores codebase
→ Proposes feature name: "jwt-refresh-tokens"
→ User approves

Phase 2: Specification
→ Creates FR-001-jwt-refresh-tokens/
→ Writes spec.md with requirements
→ User approves

Phase 3: Planning
→ Writes implementation-plan.md with tasks
→ Groups tasks by layer (handler, domain, security, etc.)
→ User approves

Phase 4: Implementation
→ Spawns sub-agents for affected layers
→ Implements feature following plan
→ Updates checkboxes as tasks complete
→ Creates summary.md
→ Done!
```

## Installation Verification

### 1. Check Directory Structure

```bash
ls -la .claude/skills/feature-request/
# Should show: SKILL.md, scripts/, references/

ls -la .claude/skills/feature-request/scripts/
# Should show: validate-phase.py, meta_controller.py, evaluate_meta_controller.py

ls -la .claude/skills/feature-request/references/
# Should show: feature-workflow.yaml, workflow-guide.md, meta-controller-scenarios.json, meta-controller-explained.md

ls -la feature-requests/
# Should exist (may be empty)
```

### 2. Test YAML Configuration

```bash
python3 -c "import yaml; yaml.safe_load(open('.claude/skills/feature-request/references/feature-workflow.yaml'))"
# Should exit cleanly (no errors)
```

### 3. Test Validation Script

```bash
# Test phase info
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action info

# Test feature name validation
python3 .claude/skills/feature-request/scripts/validate-phase.py --feature-name "jwt-refresh-tokens"
# Should return: ✅ Valid feature name

python3 .claude/skills/feature-request/scripts/validate-phase.py --feature-name "INVALID_NAME"
# Should return: ❌ Feature name must be lowercase...

# Test read validation
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action read --path "README.md"
# Should return: ✅ Read permission granted

# Test write validation (should fail in Phase 1)
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action write --path "test.md"
# Should return: ❌ Phase 1 does not allow writing...

# Test bash validation
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action bash --command "ls"
# Should return: ✅ Bash command allowed

python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action bash --command "git commit"
# Should return: ❌ Phase 1 forbids command: git commit

# Test FR number generation
python3 .claude/skills/feature-request/scripts/validate-phase.py --next-fr-number
# Should return: Next FR number: FR-001 (or higher if FRs exist)

# Test meta-controller recommendation
python3 .claude/skills/feature-request/scripts/meta_controller.py --phase 4 --json --out feature-requests/FR-001-sample/decision-support/preflight-meta-controller.json
# Should return recommended execution/planning/cognition/chunking strategy

# Validate required phase-4 decision-support artifact
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --check-decision-support --feature-dir "feature-requests/FR-001-sample"
# Should return: ✅ Decision-support checks complete for phase 4

# Test evaluator with scenario fixture
python3 .claude/skills/feature-request/scripts/evaluate_meta_controller.py --fail-on-mismatch
# Should return: Failed: 0 and Mismatches: 0

# Run unit tests
python3 -m unittest discover -s .claude/skills/feature-request/tests -p "test_*.py"
# Should return: OK
```

## The 4 Phases

### Phase 1: Discovery (Read-Only)

**Purpose:** Understand the codebase without making changes

**Permissions:**
- ✅ Read source code, docs, configs
- ❌ No writes allowed
- ✅ Bash: ls, cat, grep, git log, git status
- ❌ Bash: mkdir, rm, git commit, mvn

**Deliverable:** Valid feature name (kebab-case)

**Duration:** 5-15 minutes

---

### Phase 2: Specification

**Purpose:** Document what the feature should do (not how)

**Permissions:**
- ✅ Read anything
- ✅ Write `spec.md` only
- ❌ No code changes

**Deliverable:** `feature-requests/FR-{NNN}-{name}/spec.md` with sections:
- Problem Statement
- Business Requirements
- Success Criteria
- Non-Functional Requirements
- Dependencies

**Duration:** 10-20 minutes

---

### Phase 3: Implementation Planning

**Purpose:** Design technical solution and create task breakdown

**Permissions:**
- ✅ Read anything
- ✅ Write `implementation-plan.md` only
- ❌ No code changes

**Deliverable:** `feature-requests/FR-{NNN}-{name}/implementation-plan.md` with:
- Technical Design
- Architecture Decisions
- Layer-by-Layer Implementation
- Task Breakdown (GitHub checkboxes grouped by layer)
- Testing Strategy
- Rollout Plan

**Duration:** 20-40 minutes

---

### Phase 4: Implementation

**Purpose:** Execute plan using layer-specific sub-agents

**Permissions:**
- ✅ Read anything
- ✅ Write code in src/
- ✅ Update implementation-plan.md (checkboxes)
- ✅ Create summary.md
- ✅ Full development workflow (mvn, git commit)

**Sub-Agents:**
- orchestrator - Coordinates work
- handler-agent - REST endpoints
- domain-agent - Business logic
- proxy-agent - External clients
- security-agent - Auth/JWT
- config-agent - Spring configuration
- common-agent - DTOs/exceptions

**Deliverables:**
- Updated implementation-plan.md (all boxes checked)
- summary.md with all required sections
- Code changes
- Passing tests

**Duration:** Variable (30 minutes to several hours)

---

## File Structure

```
springboot-wendys/
├── .claude/
│   └── skills/
│       └── feature-request/
│           ├── README.md                          # This file
│           ├── SKILL.md                           # Skill definition
│           ├── scripts/
│           │   ├── validate-phase.py              # Workflow validator
│           │   ├── meta_controller.py             # Brain-inspired strategy policy
│           │   └── evaluate_meta_controller.py    # Scenario evaluator/auditor
│           ├── references/
│               ├── feature-workflow.yaml          # Workflow config
│               ├── workflow-guide.md              # User guide
│               ├── meta-controller-scenarios.json # Policy audit fixture
│               └── meta-controller-explained.md   # Plain-language math guide
│           └── tests/
│               └── test_meta_controller.py        # Regression tests
│
└── feature-requests/                              # Feature artifacts
    ├── FR-001-{feature-name}/
    │   ├── spec.md                                # Phase 2 deliverable
    │   ├── implementation-plan.md                 # Phase 3 deliverable
    │   └── summary.md                             # Phase 4 deliverable
    └── FR-002-{feature-name}/
        ├── spec.md
        ├── implementation-plan.md
        └── summary.md
```

## Validation Script Usage

### Get Phase Information

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action info
```

Shows permissions, deliverables, and requirements for the phase.

### Validate Feature Name

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --feature-name "jwt-refresh-tokens"
```

Returns exit code 0 if valid, 1 if invalid.

### Validate Read Permission

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action read --path "src/main/java/Foo.java"
```

### Validate Write Permission

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 2 --action write --path "feature-requests/FR-001-test/spec.md"
```

### Validate Bash Command

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action bash --command "ls"
```

### Check Deliverables

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-test"
```

Validates that all required deliverables exist and have required sections.

### Get Next FR Number

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --next-fr-number
```

Returns the next available FR number (e.g., FR-003).

### JSON Output

Add `--json` flag to any command for JSON output:

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action info --json
```

## Meta-Controller Usage

### Generate Strategy Recommendation

```bash
python3 .claude/skills/feature-request/scripts/meta_controller.py --phase 4
```

### Evaluate Against Scenario Fixtures

```bash
python3 .claude/skills/feature-request/scripts/evaluate_meta_controller.py --fail-on-mismatch
```

### Run Tests for Regression Auditing

```bash
python3 -m unittest discover -s .claude/skills/feature-request/tests -p "test_*.py"
```

### Validate Decision-Support Enforcement (Phase 4)

```bash
python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --check-decision-support --feature-dir "feature-requests/FR-001-name"
```

### Read the Plain-Language Explanation

```bash
cat .claude/skills/feature-request/references/meta-controller-explained.md
```

## Feature Naming Rules

### Valid Format

- Lowercase letters, numbers, and hyphens only
- Pattern: `^[a-z0-9-]+$`
- Length: 3-50 characters
- Kebab-case (words separated by hyphens)

### Examples

✅ **Valid:**
- jwt-refresh-tokens
- rate-limiting
- health-check-endpoint
- user-profile-export
- payment-webhook-handler

❌ **Invalid:**
- JWTRefreshTokens (uppercase)
- jwt_refresh_tokens (underscores)
- jwt refresh tokens (spaces)
- jwt.refresh (dots)
- a (too short)

## FR Numbering

### Auto-Increment

Feature requests are numbered sequentially:

```
FR-001  (first feature)
FR-002  (second feature)
FR-003  (third feature)
...
FR-099
FR-100
```

The validation script automatically finds the next number.

### Directory Format

Format: `FR-{NNN}-{feature-name}`

- FR- prefix (uppercase)
- 3-digit zero-padded number
- Hyphen separator
- Kebab-case feature name

Examples:
- ✅ FR-001-jwt-refresh-tokens
- ✅ FR-042-rate-limiting
- ❌ fr-1-jwt (lowercase, not padded)
- ❌ FR-001_jwt (underscores)

## Integration with AGENTS.md

The feature request workflow respects the existing AGENTS.md architecture:

### Project-Level AGENTS.md

Location: `/AGENTS.md`

All agents read this for project-wide guidance.

### Layer-Specific AGENTS.md

Locations (if they exist):
- `src/main/java/**/handler/AGENTS.md`
- `src/main/java/**/domain/AGENTS.md`
- `src/main/java/**/proxy/AGENTS.md`
- `src/main/java/**/security/AGENTS.md`
- `src/main/java/**/config/AGENTS.md`
- `src/main/java/**/common/AGENTS.md`

Sub-agents consult their layer's AGENTS.md for specific constraints.

## Common Scenarios

### Simple API Endpoint

**Example:** Add health check endpoint

**Phases:**
1. Discovery → Name: "health-check-endpoint"
2. Specification → Spec describes GET /api/health
3. Planning → Handler layer only, 2 tasks
4. Implementation → handler-agent creates controller

**Time:** ~30 minutes

---

### Cross-Layer Feature

**Example:** JWT refresh token support

**Phases:**
1. Discovery → Name: "jwt-refresh-tokens"
2. Specification → Refresh token requirements
3. Planning → 4 layers (handler, domain, security, config), 9 tasks
4. Implementation → Multiple sub-agents coordinate

**Time:** ~2 hours

---

### External Integration

**Example:** Payment provider integration

**Phases:**
1. Discovery → Name: "payment-provider-integration"
2. Specification → Payment flows, webhooks
3. Planning → 5 layers, 15 tasks
4. Implementation → Clients, error handling, config

**Time:** ~4 hours

## Troubleshooting

### Permission Denied

**Error:**
```
❌ Phase 2 does not allow writing: src/main/java/Foo.java
```

**Solution:**
- Check current phase - Phase 2 can only write spec.md
- Code changes belong in Phase 4

---

### Missing Deliverable Sections

**Error:**
```
❌ spec.md missing required section: Success Criteria
```

**Solution:**
- Add the missing section
- Required sections defined in feature-workflow.yaml
- Re-run validation

---

### Invalid Feature Name

**Error:**
```
❌ Feature name must be lowercase, alphanumeric with hyphens only
```

**Solution:**
- Use kebab-case: "jwt-refresh-tokens" not "JWT_Tokens"
- No spaces, underscores, dots
- 3-50 characters

---

### Unchecked Tasks in Phase 4

**Error:**
```
❌ implementation-plan.md has 3 unchecked tasks
```

**Solution:**
- Complete remaining tasks
- Update checkboxes: `- [ ]` → `- [x]`
- Re-run validation

## Dependencies

### Required

- Python 3.7+
- PyYAML (`pip3 install pyyaml`)

### Optional

- Git (for version control)
- Maven or Gradle (for building/testing)

## Documentation

- **This README** - Quick reference
- **SKILL.md** - Detailed skill definition and workflow
- **workflow-guide.md** - Comprehensive user guide with examples
- **feature-workflow.yaml** - Complete workflow configuration

## Version

- **Version:** 1.1.0
- **Last Updated:** 2026-02-10
- **Compatibility:** Claude Code CLI

## Support

For issues or questions:
1. Check the workflow-guide.md for detailed examples
2. Review the SKILL.md for workflow details
3. Inspect feature-workflow.yaml for configuration

## License

Part of the springboot-wendys project.
