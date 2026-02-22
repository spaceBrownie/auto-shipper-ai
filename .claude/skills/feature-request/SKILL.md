# Feature Request Workflow Skill

A structured 4-phase workflow for managing feature requests in Spring Boot projects using DDD/hexagonal architecture.

## Metadata

```yaml
name: feature-request
version: 1.0.0
trigger_phrases:
  - "I want to add a new feature"
  - "Create a feature request for"
  - "Let's implement"
  - "Start a feature workflow"
  - "New feature:"
description: Manages feature development through 4 deterministic phases (Discovery, Specification, Planning, Implementation)
```

## Overview

This skill implements a phase-gated workflow that separates discovery from implementation, enforces clear deliverables at each phase, and uses layer-specific sub-agents to respect architectural boundaries.

### The 4 Phases

1. **Phase 1: Discovery** (Read-Only) - Explore codebase, understand requirements, generate feature name
2. **Phase 2: Specification** - Document requirements in spec.md
3. **Phase 3: Planning** - Design technical solution in implementation-plan.md
4. **Phase 4: Implementation** - Execute plan using sub-agents, create summary.md

### Key Principles

- **Manual approval required between phases** - User reviews each deliverable before proceeding
- **Auto-increment FR numbers** - Automatically finds next FR-XXX number
- **Deterministic validation** - Python script validates every action before execution
- **Layer-specific sub-agents** - Implementation phase spawns agents for handler, domain, proxy, security, config, common layers
- **Living implementation plan** - Plan checkboxes updated as work progresses

## Workflow

### Phase 1: Discovery (Read-Only Exploration)

**Goal:** Understand the codebase and generate a valid feature name.

**Actions:**

1. **Load workflow configuration**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action info
   ```

2. **Explore codebase** (read-only)
   - Before reading any file, validate:
     ```bash
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action read --path "src/main/java/Foo.java"
     ```
   - Use allowed bash commands (ls, cat, grep, git log, git status)
   - Before bash command, validate:
     ```bash
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action bash --command "ls"
     ```

3. **Generate feature name**
   - Create kebab-case name (e.g., "jwt-refresh-tokens")
   - Validate name:
     ```bash
     python .claude/skills/feature-request/scripts/validate-phase.py --feature-name "jwt-refresh-tokens"
     ```
   - If validation fails, generate a new name

4. **Request manual approval**
   - Present feature name to user
   - Wait for explicit approval: "I approve the feature name: [name]"
   - If user requests changes, regenerate and re-validate

**Deliverable:** Valid feature name (kebab-case, 3-50 chars, lowercase alphanumeric with hyphens)

**Permissions:**
- ✅ Read: Source code, docs, config files
- ❌ Write: None
- ✅ Bash: ls, cat, grep, git log, git status
- ❌ Bash: mkdir, touch, rm, git add, git commit, mvn

---

### Phase 2: Specification

**Goal:** Document feature requirements in spec.md.

**Actions:**

1. **Get next FR number**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --next-fr-number
   ```

2. **Create feature directory**
   ```bash
   mkdir -p feature-requests/FR-{NNN}-{feature-name}
   ```

3. **Write spec.md**
   - Before writing, validate:
     ```bash
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 2 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/spec.md"
     ```
   - Include required sections:
     - Problem Statement
     - Business Requirements
     - Success Criteria
     - Non-Functional Requirements
     - Dependencies
   - **Do NOT include:**
     - Implementation Details
     - Technical Design
     - Code Changes

4. **Validate deliverables**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"
   ```

5. **Request manual approval**
   - Present spec.md to user
   - Wait for explicit approval: "I approve the specification"
   - If user requests changes, update spec.md and re-validate
   - **Once approved, spec.md becomes immutable**

**Deliverable:** `feature-requests/FR-{NNN}-{feature-name}/spec.md`

**Permissions:**
- ✅ Read: Everything
- ✅ Write: feature-requests/FR-*/spec.md only
- ✅ Bash: mkdir (for FR directory), ls, cat, grep
- ❌ Bash: rm, git add, git commit, mvn

---

### Phase 3: Implementation Planning

**Goal:** Design technical solution and create task breakdown.

**Actions:**

1. **Read spec.md** for requirements

2. **Design technical solution**
   - Follow DDD/hexagonal architecture
   - Identify affected layers: handler, domain, proxy, security, config, common
   - Consult layer-specific AGENTS.md files for constraints

3. **Write implementation-plan.md**
   - Before writing, validate:
     ```bash
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 3 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/implementation-plan.md"
     ```
   - Include required sections:
     - **Technical Design** - Architecture overview, design decisions
     - **Architecture Decisions** - Why this approach over alternatives
     - **Layer-by-Layer Implementation** - Detailed design for each layer
     - **Task Breakdown** - GitHub-style checkboxes grouped by layer
       ```markdown
       ## Task Breakdown

       ### Handler Layer
       - [ ] Create RefreshTokenController endpoint
       - [ ] Add request/response DTOs

       ### Domain Layer
       - [ ] Implement RefreshTokenService
       - [ ] Add token validation logic

       ### Security Layer
       - [ ] Update JwtTokenProvider for refresh tokens
       - [ ] Add refresh token filter
       ```
     - **Testing Strategy** - Unit, integration, e2e tests
     - **Rollout Plan** - Deployment steps, rollback procedure

4. **Validate deliverables**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --phase 3 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"
   ```

5. **Request manual approval**
   - Present implementation-plan.md to user
   - Wait for explicit approval: "I approve the implementation plan"
   - If user requests changes, update plan and re-validate

**Deliverable:** `feature-requests/FR-{NNN}-{feature-name}/implementation-plan.md`

**Permissions:**
- ✅ Read: Everything
- ✅ Write: feature-requests/FR-*/implementation-plan.md only
- ✅ Bash: ls, cat, grep, git log, git status
- ❌ Bash: mkdir, rm, git add, mvn

---

### Phase 4: Implementation

**Goal:** Execute implementation plan using layer-specific sub-agents.

**Sub-Agent Architecture:**

- **orchestrator**: Coordinates work, updates checkboxes in implementation-plan.md, generates summary.md
- **handler-agent**: REST endpoints (src/main/java/**/handler/**/*.java)
- **domain-agent**: Business logic (src/main/java/**/domain/**/*.java)
- **proxy-agent**: External clients (src/main/java/**/proxy/**/*.java)
- **security-agent**: Auth/JWT (src/main/java/**/security/**/*.java)
- **config-agent**: Spring beans (src/main/java/**/config/**/*.java)
- **common-agent**: Shared DTOs/exceptions (src/main/java/**/common/**/*.java)

**Actions:**

1. **Run strategy preflight (required)**
   - Generate strategy recommendation before orchestration:
     ```bash
     python3 .claude/skills/feature-request/scripts/meta_controller.py --phase 4 --json --out feature-requests/FR-{NNN}-{feature-name}/decision-support/preflight-meta-controller.json
     python3 .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --check-decision-support --feature-dir "feature-requests/FR-{NNN}-{feature-name}"
     ```
   - `meta_controller.py` now auto-infers workload state from `implementation-plan.md` when `--out` points to `.../decision-support/preflight-meta-controller.json`.
   - Keep `implementation-plan.md` task breakdown explicit (`- [ ] ...` or `- ...` per task) so inferred `task_count` and parallelism are accurate.
   - Optional override when needed:
     ```bash
     python3 .claude/skills/feature-request/scripts/meta_controller.py --phase 4 --implementation-plan feature-requests/FR-{NNN}-{feature-name}/implementation-plan.md --json
     ```
   - `--state-file` / `--state-json` still take precedence for manual tuning experiments.
   - Use output to set:
     - single vs parallel execution
     - decomposition depth
     - cognition mode (instinctual vs deliberative)
     - batch/chunk sizing

2. **Spawn orchestrator agent**
   - Orchestrator reads implementation-plan.md
   - Identifies which layers need changes
   - Spawns appropriate layer-specific sub-agents

3. **Execute tasks**
   - Sub-agents work on their designated layers
   - Before ANY action, validate permissions:
     ```bash
     # Before writing code
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --action write --path "src/main/java/com/example/handler/RefreshTokenController.java"

     # Before bash command
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --action bash --command "mvn test"
     ```
   - If validation fails, do NOT proceed - report error to orchestrator

4. **Update implementation plan**
   - As tasks complete, orchestrator updates implementation-plan.md
   - Change `- [ ]` to `- [x]` for completed tasks
   - Validate write permission before updating:
     ```bash
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/implementation-plan.md"
     ```

5. **Run tests**
   - Execute unit tests: `mvn test`
   - Execute integration tests if applicable
   - All tests must pass before proceeding

6. **Create summary.md**
   - After all checkboxes complete and tests pass
   - Validate write permission:
     ```bash
     python .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/summary.md"
     ```
   - Include required sections:
     - **Feature Summary** - Brief overview of what was implemented
     - **Changes Made** - High-level description of changes
     - **Files Modified** - List of all changed files with descriptions
     - **Testing Completed** - Test results and coverage
     - **Deployment Notes** - Any special deployment considerations

7. **Validate completion**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --phase 4 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"
   ```
   - Verifies all checkboxes checked
   - Verifies exactly 1 summary.md exists
   - Verifies at least one code file modified

8. **Run policy regression audit (recommended)**
   ```bash
   python3 .claude/skills/feature-request/scripts/evaluate_meta_controller.py --fail-on-mismatch
   python3 -m unittest discover -s .claude/skills/feature-request/tests -p "test_*.py"
   ```

**Deliverables:**
- Updated `implementation-plan.md` (all checkboxes checked)
- `summary.md` (exactly 1)
- Code changes in src/
- Passing tests

**Permissions:**
- ✅ Read: Everything
- ✅ Write: src/**/*.java, feature-requests/FR-*/implementation-plan.md, feature-requests/FR-*/summary.md, pom.xml
- ✅ Bash: Full development workflow (mvn, git add, git commit, mkdir, touch)
- ❌ Bash: rm -rf, git push --force, git reset --hard

---

## Critical Validation Rules

### BEFORE EVERY ACTION

1. **Before reading a file:**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --phase {N} --action read --path "{file}"
   ```

2. **Before writing a file:**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --phase {N} --action write --path "{file}"
   ```

3. **Before bash command:**
   ```bash
   python .claude/skills/feature-request/scripts/validate-phase.py --phase {N} --action bash --command "{cmd}"
   ```

4. **If validation fails:**
   - ❌ DO NOT proceed with the action
   - ✅ Report error to user with validation output
   - ✅ Suggest allowed alternatives

### Feature Name Validation

- Pattern: `^[a-z0-9-]+$` (lowercase alphanumeric with hyphens)
- Length: 3-50 characters
- Forbidden: uppercase, underscores, spaces, dots, slashes

**Valid examples:**
- jwt-refresh-tokens
- rate-limiting
- health-check-endpoint

**Invalid examples:**
- JWT_Tokens (uppercase, underscores)
- rate limiting (spaces)
- feature.name (dots)

### Deliverable Validation

After creating any deliverable, run:
```bash
python .claude/skills/feature-request/scripts/validate-phase.py --phase {N} --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"
```

If validation fails, fix errors before requesting manual approval.

---

## Integration with AGENTS.md

This skill respects the existing AGENTS.md architecture:

- **Root /AGENTS.md**: Provides project-level guidance for all agents
- **Layer-specific AGENTS.md**: Defines constraints for handler, domain, proxy, security, config, common layers
- **Phase 4 sub-agents**: Each sub-agent consults its layer's AGENTS.md for specific rules

Example: When `handler-agent` implements REST endpoints, it reads `/AGENTS.md` and any handler-specific guidance to ensure proper patterns.

---

## Error Handling

### Permission Denied

If validation script returns permission denied:

```
❌ Phase 2 does not allow writing: src/main/java/Foo.java
```

**Response:**
1. Do NOT attempt the action
2. Report error to user
3. Suggest valid alternatives based on phase permissions

### Missing Deliverables

If deliverable validation fails:

```
❌ Deliverable errors for phase 2:
  - Missing required deliverable: spec.md
  - spec.md missing required section: Success Criteria
```

**Response:**
1. Fix errors (create missing file or add missing sections)
2. Re-run validation
3. Only proceed to manual approval after validation passes

### Invalid Feature Name

If feature name validation fails:

```
❌ Feature name must be lowercase, alphanumeric with hyphens only (e.g., 'jwt-refresh-tokens')
```

**Response:**
1. Generate new name following pattern
2. Re-validate
3. Present to user for approval

---

## Example Session

### User Request
> "I want to add a feature for JWT refresh tokens"

### Phase 1: Discovery

**Assistant:**
```
Starting feature request workflow - Phase 1: Discovery

Let me explore the codebase to understand the current authentication implementation.

[Validates and reads JWT-related files]

Based on my exploration, I suggest the feature name: jwt-refresh-tokens

[Runs: python validate-phase.py --feature-name "jwt-refresh-tokens"]
✅ Valid feature name

Shall I proceed with this feature name?
```

**User:** "Yes, approved"

### Phase 2: Specification

**Assistant:**
```
Phase 2: Specification

[Runs: python validate-phase.py --next-fr-number]
Next FR number: FR-001

Creating feature-requests/FR-001-jwt-refresh-tokens/spec.md

[Validates write permission before creating spec.md]
✅ Write permission granted

[Creates spec.md with all required sections]

[Runs: python validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"]
✅ All deliverables complete for phase 2

Please review spec.md and approve to proceed to Phase 3.
```

**User:** "Looks good, approved"

### Phase 3: Planning

**Assistant:**
```
Phase 3: Implementation Planning

[Validates write permission for implementation-plan.md]
✅ Write permission granted

Creating technical implementation plan...

[Creates implementation-plan.md with task breakdown by layer]

Task Breakdown:
- Handler Layer: 2 tasks
- Domain Layer: 3 tasks
- Security Layer: 4 tasks
- Config Layer: 1 task

[Runs: python validate-phase.py --phase 3 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"]
✅ All deliverables complete for phase 3

Please review implementation-plan.md and approve to proceed to Phase 4.
```

**User:** "Approved"

### Phase 4: Implementation

**Assistant:**
```
Phase 4: Implementation

Spawning orchestrator and layer-specific sub-agents...

[Orchestrator coordinates work across sub-agents]

handler-agent: ✅ Created RefreshTokenController
domain-agent: ✅ Implemented RefreshTokenService
security-agent: ✅ Updated JwtTokenProvider
config-agent: ✅ Added refresh token configuration

[Orchestrator updates checkboxes in implementation-plan.md]

All tasks complete. Running tests...

[Runs: mvn test]
✅ All tests pass

Creating summary.md...

[Validates completion]
✅ All deliverables complete for phase 4

Feature request FR-001-jwt-refresh-tokens complete!

Summary:
- 8 files modified
- 12 tests added
- All tests passing
- Ready for deployment
```

---

## Testing the Workflow

### Verify Installation

1. **Check directory structure:**
   ```bash
   ls -la .claude/skills/feature-request/
   ls -la .claude/skills/feature-request/scripts/
   ls -la .claude/skills/feature-request/references/
   ```

2. **Test validation script:**
   ```bash
   # Test phase info
   python .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action info

   # Test feature name validation
   python .claude/skills/feature-request/scripts/validate-phase.py --feature-name "test-feature"
   python .claude/skills/feature-request/scripts/validate-phase.py --feature-name "INVALID_NAME"

   # Test permission validation
   python .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action read --path "src/main/java/Foo.java"
   python .claude/skills/feature-request/scripts/validate-phase.py --phase 1 --action write --path "test.md"
   ```

3. **Test YAML parsing:**
   ```bash
   python -c "import yaml; yaml.safe_load(open('.claude/skills/feature-request/references/feature-workflow.yaml'))"
   ```

### End-to-End Test

Create a simple test feature (e.g., "health-check-endpoint") and verify:
- Phase 1: Feature name validated
- Phase 2: FR-001 directory created, spec.md has required sections
- Phase 3: implementation-plan.md has checkboxes grouped by layer
- Phase 4: Code changes made, checkboxes updated, summary.md created

---

## References

- **Workflow Configuration:** `.claude/skills/feature-request/references/feature-workflow.yaml`
- **Validation Script:** `.claude/skills/feature-request/scripts/validate-phase.py`
- **Usage Guide:** `.claude/skills/feature-request/references/workflow-guide.md`
- **Project Architecture:** `/AGENTS.md`

---

## Version History

- **v1.0.0** (2026-02-09): Initial implementation with 4-phase workflow
