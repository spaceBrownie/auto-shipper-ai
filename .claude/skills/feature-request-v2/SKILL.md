# Feature Request Workflow Skill (v2)

A structured 6-phase workflow for managing feature requests in Spring Boot projects using DDD/hexagonal architecture, with test-first enforcement and automated PR review loops.

## Metadata

```yaml
name: feature-request-v2
version: 2.0.0
trigger_phrases:
  - "I want to add a new feature"
  - "Create a feature request for"
  - "Let's implement"
  - "Start a feature workflow"
  - "Start a v2 feature workflow"
  - "New feature:"
description: Manages feature development through 6 deterministic phases (Discovery, Specification, Planning, Test-First Gate, Implementation, Review-Fix Loop)
```

## Overview

This skill implements a phase-gated workflow that separates discovery from implementation, enforces clear deliverables at each phase, uses layer-specific sub-agents to respect architectural boundaries, generates tests before production code, and automates the PR review cycle.

### The 6 Phases

1. **Phase 1: Discovery** (Read-Only) - Explore codebase, understand requirements, generate feature name
2. **Phase 2: Specification** - Document requirements in spec.md
3. **Phase 3: Planning** - Design technical solution in implementation-plan.md
4. **Phase 4: Test-First Gate** - Generate runnable tests from spec + plan before implementation
5. **Phase 5: Implementation** - Execute plan using sub-agents, make Phase 4 tests pass, create summary.md
6. **Phase 6: Review-Fix Loop** - PR review cycle until clean

### Key Principles

- **Manual approval required between phases 1-5** - User reviews each deliverable before proceeding
- **Test-first enforcement** - Phase 4 generates compilable tests before any production code is written; Phase 5 makes those tests pass
- **Automated PR review loop** - Phase 6 creates a PR and iterates on review comments and CI failures until clean, with no manual approval gate
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
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action info
   ```

2. **Explore codebase** (read-only)
   - Before reading any file, validate:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action read --path "src/main/java/Foo.java"
     ```
   - Use allowed bash commands (ls, cat, grep, git log, git status)
   - Before bash command, validate:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action bash --command "ls"
     ```

3. **Generate feature name**
   - Create kebab-case name (e.g., "jwt-refresh-tokens")
   - Validate name:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --feature-name "jwt-refresh-tokens"
     ```
   - If validation fails, generate a new name

4. **Request manual approval**
   - Present feature name to user
   - Wait for explicit approval: "I approve the feature name: [name]"
   - If user requests changes, regenerate and re-validate

**Deliverable:** Valid feature name (kebab-case, 3-50 chars, lowercase alphanumeric with hyphens)

**Permissions:**
- Read: Source code, docs, config files
- Write: None
- Bash: ls, cat, grep, git log, git status
- Bash (blocked): mkdir, touch, rm, git add, git commit, mvn

---

### Phase 2: Specification

**Goal:** Document feature requirements in spec.md.

**Actions:**

1. **Get next FR number**
   ```bash
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --next-fr-number
   ```

2. **Create feature directory**
   ```bash
   mkdir -p feature-requests/FR-{NNN}-{feature-name}
   ```

3. **Write spec.md**
   - Before writing, validate:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/spec.md"
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
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"
   ```

5. **Request manual approval**
   - Present spec.md to user
   - Wait for explicit approval: "I approve the specification"
   - If user requests changes, update spec.md and re-validate
   - **Once approved, spec.md becomes immutable**

**Deliverable:** `feature-requests/FR-{NNN}-{feature-name}/spec.md`

**Permissions:**
- Read: Everything
- Write: feature-requests/FR-*/spec.md only
- Bash: mkdir (for FR directory), ls, cat, grep
- Bash (blocked): rm, git add, git commit, mvn

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
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 3 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/implementation-plan.md"
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
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 3 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"
   ```

5. **Request manual approval**
   - Present implementation-plan.md to user
   - Wait for explicit approval: "I approve the implementation plan"
   - If user requests changes, update plan and re-validate
   - Next phase: **Phase 4: Test-First Gate**

**Deliverable:** `feature-requests/FR-{NNN}-{feature-name}/implementation-plan.md`

**Permissions:**
- Read: Everything
- Write: feature-requests/FR-*/implementation-plan.md only
- Bash: ls, cat, grep, git log, git status
- Bash (blocked): mkdir, rm, git add, mvn

---

### Phase 4: Test-First Gate

**Goal:** Generate runnable tests from spec + implementation plan before any production code is written.

**Actions:**

1. **Read spec.md and implementation-plan.md**
   - Understand requirements, success criteria, boundary conditions, and layer contracts
   - Before reading, validate:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action read --path "feature-requests/FR-001-jwt-refresh-tokens/spec.md"
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action read --path "feature-requests/FR-001-jwt-refresh-tokens/implementation-plan.md"
     ```

2. **Generate tests in 3 categories**
   - Before writing any test file, validate:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action write --path "modules/catalog/src/test/kotlin/com/example/catalog/SomeTest.kt"
     ```
   - **End-to-End Flow Tests** - Verify the complete feature workflow from entry point to final state. These tests exercise the full path through the system for the happy-path scenarios described in the spec's success criteria.
   - **Boundary Condition Tests** - Cover edge cases, error paths, and threshold behavior. Include stress-test margin boundaries, invalid state transitions, missing/malformed inputs, and any "must reject" criteria from the spec.
   - **Dependency Contract Tests** - Verify interactions with collaborating modules and external adapters. Use mocks/stubs to assert that the feature calls dependencies with correct arguments and handles their responses (including failures) properly.

3. **Verify test compilation**
   ```bash
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action bash --command "./gradlew compileTestKotlin"
   ./gradlew compileTestKotlin
   ```
   - Tests must compile successfully. They are expected to **fail** when run (since production code does not exist yet), so do NOT execute `./gradlew test`.

4. **Write test-manifest.md**
   - Before writing, validate:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/test-manifest.md"
     ```
   - Include:
     - **Test File Inventory** - List of all test files created with their paths
     - **Category Mapping** - Which tests cover end-to-end flow, boundary conditions, and dependency contracts
     - **Spec Traceability** - Map each test back to the spec requirement or success criterion it validates
     - **Expected Failures** - Confirm that all tests are expected to fail until Phase 5 implementation

5. **Validate deliverables**
   ```bash
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"
   ```

6. **Request manual approval**
   - Present test-manifest.md and test file summary to user
   - Wait for explicit approval: "I approve the test-first gate"
   - If user requests changes, update tests and re-validate

**Deliverables:**
- Test files that compile (under `src/test/` or `modules/**/src/test/`)
- `feature-requests/FR-{NNN}-{feature-name}/test-manifest.md`

**Permissions:**
- Read: Everything
- Write: src/test/**/*.kt, src/test/**/*.java, src/test/resources/**, modules/**/src/test/**/*.kt, modules/**/src/test/**/*.java, modules/**/src/test/resources/**, feature-requests/FR-*/test-manifest.md
- Bash: ./gradlew compileTestKotlin, ls, cat, grep, git log, git status
- Bash (blocked): ./gradlew test (tests are expected to fail), rm, git add, git commit, git push

---

### Phase 5: Implementation

**Goal:** Execute implementation plan using layer-specific sub-agents. Make the Phase 4 tests pass.

**Sub-Agent Architecture:**

- **orchestrator**: Coordinates work, updates checkboxes in implementation-plan.md, generates summary.md
- **handler-agent**: REST endpoints (src/main/java/**/handler/**/*.java, modules/**/src/main/kotlin/**/handler/**/*.kt)
- **domain-agent**: Business logic (src/main/java/**/domain/**/*.java, modules/**/src/main/kotlin/**/domain/**/*.kt)
- **proxy-agent**: External clients (src/main/java/**/proxy/**/*.java, modules/**/src/main/kotlin/**/proxy/**/*.kt)
- **security-agent**: Auth/JWT (src/main/java/**/security/**/*.java, modules/**/src/main/kotlin/**/security/**/*.kt)
- **config-agent**: Spring beans (src/main/java/**/config/**/*.java, modules/**/src/main/kotlin/**/config/**/*.kt)
- **common-agent**: Shared DTOs/exceptions (src/main/java/**/common/**/*.java, modules/**/src/main/kotlin/**/common/**/*.kt)

**Sub-Agent Execution Order:**

Sub-agents are executed in dependency groups. Each group must complete before the next begins.

| Group | Name | Agents | Depends On |
|-------|------|--------|------------|
| 1 | Foundation | config-agent, common-agent | None |
| 2 | Domain Logic | domain-agent, proxy-agent, security-agent | Group 1 |
| 3 | Integration | handler-agent | Group 2 |

Within each group, agents may run in parallel if there are no intra-group dependencies.

**Actions:**

1. **Run strategy preflight (required)**
   - Generate strategy recommendation before orchestration:
     ```bash
     python3 .claude/skills/feature-request-v2/scripts/meta_controller.py --phase 5 --json --out feature-requests/FR-{NNN}-{feature-name}/decision-support/preflight-meta-controller.json
     python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --check-decision-support --feature-dir "feature-requests/FR-{NNN}-{feature-name}"
     ```
   - `meta_controller.py` now auto-infers workload state from `implementation-plan.md` when `--out` points to `.../decision-support/preflight-meta-controller.json`.
   - Keep `implementation-plan.md` task breakdown explicit (`- [ ] ...` or `- ...` per task) so inferred `task_count` and parallelism are accurate.
   - Optional override when needed:
     ```bash
     python3 .claude/skills/feature-request-v2/scripts/meta_controller.py --phase 5 --implementation-plan feature-requests/FR-{NNN}-{feature-name}/implementation-plan.md --json
     ```
   - `--state-file` / `--state-json` still take precedence for manual tuning experiments.
   - Use output to set:
     - single vs parallel execution
     - decomposition depth
     - cognition mode (instinctual vs deliberative)
     - batch/chunk sizing

2. **Spawn orchestrator agent**
   - Orchestrator reads implementation-plan.md and test-manifest.md
   - Identifies which layers need changes
   - Spawns appropriate layer-specific sub-agents following the execution order above

3. **Execute tasks**
   - Sub-agents work on their designated layers in dependency group order
   - Before ANY action, validate permissions:
     ```bash
     # Before writing code
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action write --path "modules/catalog/src/main/kotlin/com/example/catalog/domain/SomeService.kt"

     # Before bash command
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action bash --command "./gradlew test"
     ```
   - If validation fails, do NOT proceed - report error to orchestrator

4. **Update implementation plan**
   - As tasks complete, orchestrator updates implementation-plan.md
   - Change `- [ ]` to `- [x]` for completed tasks (auto-checkbox from agent completion reports)
   - Validate write permission before updating:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/implementation-plan.md"
     ```

5. **Bulk validation**
   - After all sub-agents complete, run bulk validation:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --bulk-validate --feature-dir "feature-requests/FR-{NNN}-{feature-name}"
     ```

6. **Run tests**
   - Execute tests: `./gradlew test`
   - All Phase 4 tests must pass
   - All other existing tests must continue to pass
   - If tests fail, diagnose and fix until green

7. **Create summary.md**
   - After all checkboxes complete and tests pass
   - Validate write permission:
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action write --path "feature-requests/FR-001-jwt-refresh-tokens/summary.md"
     ```
   - Include required sections:
     - **Feature Summary** - Brief overview of what was implemented
     - **Changes Made** - High-level description of changes
     - **Files Modified** - List of all changed files with descriptions
     - **Testing Completed** - Test results and coverage
     - **Deployment Notes** - Any special deployment considerations

8. **Validate completion**
   ```bash
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"
   ```
   - Verifies all checkboxes checked
   - Verifies exactly 1 summary.md exists
   - Verifies at least one code file modified
   - Verifies Phase 4 tests pass

9. **Run policy regression audit (recommended)**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/evaluate_meta_controller.py --fail-on-mismatch
   python3 -m unittest discover -s .claude/skills/feature-request-v2/tests -p "test_*.py"
   ```

**Deliverables:**
- Updated `implementation-plan.md` (all checkboxes checked)
- `summary.md` (exactly 1)
- Code changes in src/ or modules/
- All tests passing (including Phase 4 tests)

**Permissions:**
- Read: Everything
- Write: src/**/*.java, src/**/*.kt, modules/**/src/**/*.kt, modules/**/src/**/*.java, feature-requests/FR-*/implementation-plan.md, feature-requests/FR-*/summary.md, feature-requests/FR-*/decision-support/*, build.gradle.kts, modules/**/build.gradle.kts, src/main/resources/**, modules/**/src/main/resources/**
- Bash: Full development workflow (./gradlew build, ./gradlew test, git add, git commit, mkdir, touch)
- Bash (blocked): rm -rf, git push --force, git reset --hard

---

### Phase 6: Review-Fix Loop

**Goal:** Create a pull request and iterate until review is clean and CI is green.

**Actions:**

1. **Create pull request**
   ```bash
   gh pr create --title "FR-{NNN}: {feature-name}" --body "$(cat <<'EOF'
   ## Summary
   <generated from summary.md>

   ## Test Plan
   <generated from test-manifest.md>

   ## Spec
   See `feature-requests/FR-{NNN}-{feature-name}/spec.md`
   EOF
   )"
   ```

2. **Check PR status**
   - Monitor CI checks:
     ```bash
     gh pr checks
     ```
   - Fetch review comments:
     ```bash
     gh pr view --comments
     ```

3. **Assess review comments and CI failures**
   - Read each review comment and CI failure log
   - Categorize issues: code correctness, style/convention, test gaps, CI configuration

4. **Fix issues**
   - Address each review comment and CI failure
   - Before writing fixes, validate permissions (same as Phase 5):
     ```bash
     python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 6 --action write --path "{file}"
     ```
   - Run tests locally before pushing:
     ```bash
     ./gradlew test
     ```

5. **Push fixes and re-check**
   ```bash
   git add -A && git commit -m "address review feedback" && git push
   ```
   - Re-check CI status:
     ```bash
     gh pr checks
     ```

6. **Repeat until clean**
   - Continue the loop (steps 2-5) until:
     - All CI checks pass (green)
     - All review comments are resolved
     - No new review comments are raised

**Exit Criteria:**
- CI checks: all green
- Review comments: all resolved
- No manual approval needed - the loop exits automatically when criteria are met

**Permissions:**
- Read: Everything
- Write: Same as Phase 5 (src/**/*.java, src/**/*.kt, modules/**/src/**/*.kt, modules/**/src/**/*.java, feature-requests/FR-*/implementation-plan.md, feature-requests/FR-*/summary.md, build.gradle.kts, modules/**/build.gradle.kts, src/main/resources/**, modules/**/src/main/resources/**, src/test/**/*.kt, src/test/**/*.java, modules/**/src/test/**/*.kt, modules/**/src/test/**/*.java)
- Bash: ./gradlew build, ./gradlew test, git add, git commit, git push, gh pr create, gh pr checks, gh pr view
- Bash (blocked): rm -rf, git push --force, git reset --hard

---

## Critical Validation Rules

### BEFORE EVERY ACTION

1. **Before reading a file:**
   ```bash
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase {N} --action read --path "{file}"
   ```

2. **Before writing a file:**
   ```bash
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase {N} --action write --path "{file}"
   ```

3. **Before bash command:**
   ```bash
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase {N} --action bash --command "{cmd}"
   ```

4. **If validation fails:**
   - DO NOT proceed with the action
   - Report error to user with validation output
   - Suggest allowed alternatives

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
python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase {N} --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"
```

If validation fails, fix errors before requesting manual approval.

---

## Integration with AGENTS.md

This skill respects the existing AGENTS.md architecture:

- **Root /AGENTS.md**: Provides project-level guidance for all agents
- **Layer-specific AGENTS.md**: Defines constraints for handler, domain, proxy, security, config, common layers
- **Phase 5 sub-agents**: Each sub-agent consults its layer's AGENTS.md for specific rules

Example: When `handler-agent` implements REST endpoints, it reads `/AGENTS.md` and any handler-specific guidance to ensure proper patterns.

---

## Error Handling

### Permission Denied

If validation script returns permission denied:

```
Phase 2 does not allow writing: src/main/java/Foo.java
```

**Response:**
1. Do NOT attempt the action
2. Report error to user
3. Suggest valid alternatives based on phase permissions

### Missing Deliverables

If deliverable validation fails:

```
Deliverable errors for phase 2:
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
Feature name must be lowercase, alphanumeric with hyphens only (e.g., 'jwt-refresh-tokens')
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
Starting feature request workflow (v2) - Phase 1: Discovery

Let me explore the codebase to understand the current authentication implementation.

[Validates and reads JWT-related files]

Based on my exploration, I suggest the feature name: jwt-refresh-tokens

[Runs: python validate-phase.py --feature-name "jwt-refresh-tokens"]
Valid feature name

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
Write permission granted

[Creates spec.md with all required sections]

[Runs: python validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"]
All deliverables complete for phase 2

Please review spec.md and approve to proceed to Phase 3.
```

**User:** "Looks good, approved"

### Phase 3: Planning

**Assistant:**
```
Phase 3: Implementation Planning

[Validates write permission for implementation-plan.md]
Write permission granted

Creating technical implementation plan...

[Creates implementation-plan.md with task breakdown by layer]

Task Breakdown:
- Handler Layer: 2 tasks
- Domain Layer: 3 tasks
- Security Layer: 4 tasks
- Config Layer: 1 task

[Runs: python validate-phase.py --phase 3 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"]
All deliverables complete for phase 3

Please review implementation-plan.md and approve to proceed to Phase 4: Test-First Gate.
```

**User:** "Approved"

### Phase 4: Test-First Gate

**Assistant:**
```
Phase 4: Test-First Gate

Reading spec.md and implementation-plan.md to generate tests...

Generating 3 categories of tests:
- End-to-End Flow: 4 tests
- Boundary Conditions: 6 tests
- Dependency Contracts: 3 tests

[Validates write permission for each test file]
[Creates test files under modules/**/src/test/]

Verifying compilation...
[Runs: ./gradlew compileTestKotlin]
All tests compile successfully (expected to fail at runtime until Phase 5)

Creating test-manifest.md with traceability to spec requirements...

[Runs: python validate-phase.py --phase 4 --check-deliverables --feature-dir "feature-requests/FR-001-jwt-refresh-tokens"]
All deliverables complete for phase 4

Please review the test-manifest.md and test files, then approve to proceed to Phase 5: Implementation.
```

**User:** "Approved"

### Phase 5: Implementation

**Assistant:**
```
Phase 5: Implementation

Running strategy preflight...
[Runs: python3 meta_controller.py --phase 5 ...]
Strategy: parallel execution, deliberative cognition

Spawning orchestrator and layer-specific sub-agents...
Execution order: Group 1 (Foundation) -> Group 2 (Domain Logic) -> Group 3 (Integration)

Group 1 - Foundation:
  config-agent: Created refresh token configuration
  common-agent: Added shared DTOs

Group 2 - Domain Logic:
  domain-agent: Implemented RefreshTokenService
  proxy-agent: (no changes needed)
  security-agent: Updated JwtTokenProvider

Group 3 - Integration:
  handler-agent: Created RefreshTokenController

[Orchestrator updates checkboxes in implementation-plan.md]

All tasks complete. Running tests...

[Runs: ./gradlew test]
All 13 Phase 4 tests pass + existing tests green

Creating summary.md...

[Validates completion]
All deliverables complete for phase 5

Feature implementation complete! Proceeding to Phase 6: Review-Fix Loop.
```

### Phase 6: Review-Fix Loop

**Assistant:**
```
Phase 6: Review-Fix Loop

Creating pull request...
[Runs: gh pr create --title "FR-001: jwt-refresh-tokens" ...]
PR #42 created: https://github.com/org/repo/pull/42

Checking CI status...
[Runs: gh pr checks]
CI: 1 failing check (lint warning on unused import)

Fixing lint issue...
[Removes unused import, commits, pushes]

Re-checking CI...
[Runs: gh pr checks]
CI: All checks passing

Checking review comments...
[Runs: gh pr view --comments]
1 comment: "Consider adding rate limiting to the refresh endpoint"

Addressing review feedback...
[Adds rate limiting, commits, pushes]

Re-checking...
CI: All checks passing
Review comments: All resolved, no new comments

PR #42 is clean and ready to merge.
Feature request FR-001-jwt-refresh-tokens workflow complete!
```

---

## Testing the Workflow

### Verify Installation

1. **Check directory structure:**
   ```bash
   ls -la .claude/skills/feature-request-v2/
   ls -la .claude/skills/feature-request-v2/scripts/
   ls -la .claude/skills/feature-request-v2/references/
   ```

2. **Test validation script:**
   ```bash
   # Test phase info
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action info

   # Test feature name validation
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --feature-name "test-feature"
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --feature-name "INVALID_NAME"

   # Test permission validation
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action read --path "src/main/java/Foo.java"
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action write --path "test.md"

   # Test Phase 4 compilation validation
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action bash --command "./gradlew compileTestKotlin"

   # Test Phase 5 bulk validation
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --bulk-validate --feature-dir "feature-requests/FR-001-test-feature"

   # Test Phase 6 permissions
   python .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 6 --action bash --command "gh pr create"
   ```

3. **Test YAML parsing:**
   ```bash
   python -c "import yaml; yaml.safe_load(open('.claude/skills/feature-request-v2/references/feature-workflow.yaml'))"
   ```

### End-to-End Test

Create a simple test feature (e.g., "health-check-endpoint") and verify:
- Phase 1: Feature name validated
- Phase 2: FR-001 directory created, spec.md has required sections
- Phase 3: implementation-plan.md has checkboxes grouped by layer
- Phase 4: Test files compile, test-manifest.md created with traceability
- Phase 5: Code changes made, Phase 4 tests pass, checkboxes updated, summary.md created
- Phase 6: PR created, review comments addressed, CI green

---

## References

- **Workflow Configuration:** `.claude/skills/feature-request-v2/references/feature-workflow.yaml`
- **Validation Script:** `.claude/skills/feature-request-v2/scripts/validate-phase.py`
- **Meta Controller:** `.claude/skills/feature-request-v2/scripts/meta_controller.py`
- **Policy Evaluator:** `.claude/skills/feature-request-v2/scripts/evaluate_meta_controller.py`
- **Usage Guide:** `.claude/skills/feature-request-v2/references/workflow-guide.md`
- **Project Architecture:** `/AGENTS.md`

---

## Version History

- **v1.0.0** (2026-02-09): Initial implementation with 4-phase workflow
- **v2.0.0** (2026-03-25): Evolved to 6-phase workflow — added Phase 4 (Test-First Gate) for pre-implementation test generation, added Phase 6 (Review-Fix Loop) for automated PR review cycling, added sub-agent execution order with dependency groups, added bulk validation, updated script paths to feature-request-v2
