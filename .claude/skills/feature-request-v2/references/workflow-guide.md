# 6-Phase Feature Request Workflow Guide

A comprehensive guide for using the 6-phase feature request workflow in Spring Boot projects.

## Table of Contents

- [Overview](#overview)
- [When to Use This Workflow](#when-to-use-this-workflow)
- [The 6 Phases Explained](#the-6-phases-explained)
  - [Phase 1: Discovery](#phase-1-discovery-read-only)
  - [Phase 2: Specification](#phase-2-specification)
  - [Phase 3: Implementation Planning](#phase-3-implementation-planning)
  - [Phase 4: Test-First Gate](#phase-4-test-first-gate)
  - [Phase 5: Implementation](#phase-5-implementation)
  - [Phase 6: Review-Fix Loop](#phase-6-review-fix-loop)
- [Getting Started](#getting-started)
- [Common Scenarios](#common-scenarios)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)
- [FR Numbering Conventions](#fr-numbering-conventions)
- [Integration with AGENTS.md](#integration-with-agentsmd)

---

## Overview

The feature request workflow is a structured, phase-gated approach to implementing new features in Spring Boot projects that follow DDD/hexagonal architecture. It separates discovery from implementation, enforces test-first discipline, and uses layer-specific sub-agents to maintain architectural boundaries.

### Key Benefits

- **Structured approach** - Clear phases with defined deliverables
- **Separation of concerns** - Discovery (read-only) separate from implementation (write)
- **Test-first discipline** - Tests written and proven red before any production code
- **Reviewable progress** - Manual approval gates between phases
- **Trackable features** - Each feature gets a numbered directory with full documentation
- **Architectural compliance** - Layer-specific agents respect boundaries
- **Deterministic validation** - Python script enforces permissions at every step
- **Review-fix loop** - Automated quality gate catches regressions before completion

### The Workflow in a Nutshell

```
Phase 1: Discovery           -> Generate feature name
         (Read-Only)            Manual approval #1
                |
                v
Phase 2: Specification       -> Write spec.md
         (Write spec)           Manual approval #2
                |
                v
Phase 3: Planning            -> Write implementation-plan.md
         (Write plan)           Manual approval #3
                |
                v
Phase 4: Test-First Gate     -> Write test-manifest.md + failing tests
         (Write tests)          Manual approval #4 (test approval)
                |
                v
Phase 5: Implementation      -> Execute plan, make tests pass, create summary.md
         (Write code)           Automatic transition to Phase 6
                |
                v
Phase 6: Review-Fix Loop     -> Validate all tests green, fix issues
         (Fix only)             Automated completion when clean
```

---

## When to Use This Workflow

### Use This Workflow For:

- **New features** that span multiple architectural layers
- **Significant enhancements** requiring design decisions
- **Features requiring coordination** across handler, domain, proxy, security, config, or common layers
- **Features that need documentation** for future reference or team collaboration
- **Complex changes** where planning ahead saves time

### Don't Use This Workflow For:

- **Bug fixes** - Use standard bug fix workflow
- **Simple refactoring** - Make direct changes
- **Documentation updates** - Edit docs directly
- **Configuration tweaks** - Modify config files directly
- **One-liner changes** - Too much overhead for trivial changes

### Gray Area - Consider Using When:

- Change touches 3+ files across multiple layers -> Probably use workflow
- Unsure about technical approach -> Use workflow for planning
- Team wants traceability -> Use workflow for documentation
- Quick experiment -> Skip workflow, use it later if experiment succeeds

---

## The 6 Phases Explained

### Phase 1: Discovery (Read-Only)

**Purpose:** Understand the codebase without making changes.

**What You Can Do:**
- Read source code, config files, documentation
- Explore existing patterns and conventions
- Run read-only git commands (log, status, diff, show)
- Understand dependencies and architecture

**What You Can't Do:**
- Write any files
- Create directories
- Run builds or tests
- Modify git state

**Deliverable:** A valid feature name in kebab-case (e.g., "jwt-refresh-tokens")

**Duration:** Typically 5-15 minutes of exploration

**Example:**
```
User: "I want to add JWT refresh token support"

Assistant explores:
- Reads existing JWT implementation
- Checks current authentication flow
- Reviews security configuration
- Identifies affected layers

Assistant proposes: "jwt-refresh-tokens"
User approves -> Proceed to Phase 2
```

---

### Phase 2: Specification

**Purpose:** Document what the feature should do (not how).

**What You Can Do:**
- Read anything
- Create feature directory (feature-requests/FR-XXX-name/)
- Write spec.md (and only spec.md)

**What You Can't Do:**
- Write code
- Write implementation plans
- Run builds or commits

**Deliverable:** `spec.md` with these sections:
- **Problem Statement** - What problem are we solving?
- **Business Requirements** - What must the feature do?
- **Success Criteria** - How do we know it's done?
- **Non-Functional Requirements** - Performance, security, scalability needs
- **Dependencies** - What does this depend on?

**Forbidden Content:**
- Implementation details (leave for Phase 3)
- Technical design decisions
- Code snippets

**Duration:** Typically 10-20 minutes

**Example spec.md:**
```markdown
# JWT Refresh Token Support

## Problem Statement
Users currently must re-authenticate every 15 minutes when their JWT expires.
This creates poor UX for active users.

## Business Requirements
- Users can refresh their JWT without re-entering credentials
- Refresh tokens are valid for 7 days
- Refresh tokens are single-use (rotate on each refresh)
- Old refresh tokens are invalidated on use

## Success Criteria
- User can call /api/auth/refresh with refresh token
- Receives new access token + new refresh token
- Old refresh token no longer works
- Failed refresh returns 401 Unauthorized

## Non-Functional Requirements
- Refresh endpoint responds in < 100ms
- Refresh tokens stored securely (encrypted at rest)
- Token rotation prevents replay attacks

## Dependencies
- Existing JWT infrastructure
- User authentication service
- Token storage mechanism
```

---

### Phase 3: Implementation Planning

**Purpose:** Design the technical solution and break it into tasks.

**What You Can Do:**
- Read anything (including spec.md)
- Write implementation-plan.md (and only this file)

**What You Can't Do:**
- Write code
- Modify spec.md (it's immutable after Phase 2)
- Run builds or commits

**Deliverable:** `implementation-plan.md` with these sections:
- **Technical Design** - Architecture overview, component interactions
- **Architecture Decisions** - Why this approach vs alternatives
- **Layer-by-Layer Implementation** - Detailed design for each affected layer
- **Task Breakdown** - GitHub-style checkboxes grouped by layer
- **Testing Strategy** - Unit, integration, e2e tests
- **Rollout Plan** - Deployment steps, rollback procedure

**Required Format:**
```markdown
## Task Breakdown

### Handler Layer
- [ ] Create RefreshTokenController with POST /api/auth/refresh endpoint
- [ ] Add RefreshTokenRequest and RefreshTokenResponse DTOs

### Domain Layer
- [ ] Create RefreshTokenService with refresh logic
- [ ] Implement token rotation in TokenRotationService
- [ ] Add RefreshToken entity with expiration

### Security Layer
- [ ] Update JwtTokenProvider to generate refresh tokens
- [ ] Create RefreshTokenFilter for endpoint security
- [ ] Add refresh token validation logic

### Config Layer
- [ ] Configure refresh token properties (expiration, secret)
```

**Duration:** Typically 20-40 minutes

---

### Phase 4: Test-First Gate

**Purpose:** Write all tests before any production code. Every test must be proven to fail (red) before Phase 5 begins. This ensures tests are meaningful validators, not rubber stamps written after the fact.

**What You Can Do:**
- Read anything (including spec.md and implementation-plan.md)
- Write test-manifest.md in the feature directory
- Write test files (src/test/ only)
- Run tests (to confirm they fail)

**What You Can't Do:**
- Write production code (src/main/)
- Modify spec.md or implementation-plan.md
- Create summary.md
- Run builds that compile production code changes

**Deliverables:**
- `test-manifest.md` with all required sections
- Test source files that compile but fail (red)

**Required test-manifest.md Sections:**
- **Test Inventory** - Table listing every test class, method, layer, and what it validates
- **Coverage Rationale** - Explanation of how these tests cover the spec's success criteria
- **Dependency Map** - Which tests must pass before others are meaningful
- **Red Confirmation** - Evidence that every test fails before implementation (timestamps, error summaries)

**Duration:** Typically 20-40 minutes

**Example:**
```
User approves implementation-plan.md -> Phase 4 begins

Assistant writes:
- test-manifest.md with inventory of 12 tests across 3 layers
- RefreshTokenControllerTest.kt (3 tests, all red)
- RefreshTokenServiceTest.kt (5 tests, all red)
- TokenRotationTest.kt (4 tests, all red)

Assistant confirms: "All 12 tests compile but fail. Red confirmation recorded."
User reviews test coverage and approves -> Proceed to Phase 5
```

---

### Phase 5: Implementation

**Purpose:** Execute the plan using layer-specific sub-agents. The primary goal is to make the failing tests from Phase 4 pass while implementing all planned tasks.

**What You Can Do:**
- Read anything
- Write code in src/
- Update implementation-plan.md (check boxes)
- Create summary.md at the end
- Run builds, tests, git commits

**What You Can't Do:**
- Modify spec.md (immutable)
- Force push or destructive git operations
- Write outside allowed directories

**Sub-Agents (dependency-ordered groups):**

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

**Workflow:**
1. Orchestrator reads implementation-plan.md and test-manifest.md
2. Spawns sub-agents for affected layers in dependency order
3. Sub-agents implement their tasks, targeting the red tests from Phase 4
4. Orchestrator updates checkboxes as tasks complete
5. All tests run and pass (red -> green)
6. Orchestrator creates summary.md
7. Automatic transition to Phase 6

**Deliverables:**
- Updated implementation-plan.md (all boxes checked)
- summary.md with all required sections
- Code changes in src/
- Passing tests (all Phase 4 tests now green)

**Duration:** Variable (30 minutes to several hours depending on complexity)

---

### Phase 6: Review-Fix Loop

**Purpose:** Validate the implementation holistically and fix any issues found during review. This phase runs an automated review cycle: check for test failures, code quality issues, and spec compliance, then fix anything that surfaces.

**What You Can Do:**
- Read anything
- Fix production code (bug fixes only, no new features)
- Fix test code (broken assertions, flaky tests)
- Run builds, tests, linters
- Update summary.md with fix notes

**What You Can't Do:**
- Add new features or tasks beyond what was planned
- Modify spec.md or implementation-plan.md
- Delete or skip tests from Phase 4
- Create new test files (only fix existing ones)

**Deliverables:**
- All tests green (including existing tests that may have regressed)
- No unresolved review findings
- Updated summary.md if fixes were applied

**Completion:** Automated. Phase 6 completes when:
1. Full test suite passes (zero failures)
2. No unresolved findings from the review scan
3. summary.md reflects any fixes applied

**Duration:** Typically 10-30 minutes (zero if Phase 5 was clean)

**Example:**
```
Phase 5 completes -> Automatic transition to Phase 6

Review scan finds:
- 1 existing integration test regressed (NPE from new null handling)
- 1 Phase 4 test has a flaky timing assertion

Assistant fixes:
- Adds null guard to preserve backward compatibility
- Replaces Thread.sleep with Awaitility in flaky test

All tests green. No further findings. Phase 6 complete.
```

---

## Getting Started

### 1. Trigger the Workflow

Use any of these phrases:
- "I want to add a new feature for [description]"
- "Create a feature request for [description]"
- "Let's implement [description]"
- "Start a feature workflow for [description]"

Example:
```
User: "I want to add a feature for JWT refresh tokens"
```

### 2. Phase 1: Approve Feature Name

Assistant will explore codebase and propose a name:
```
Assistant: "Based on exploration, I suggest: jwt-refresh-tokens"
User: "Approved" or "Let's call it refresh-token-support instead"
```

### 3. Phase 2: Review Specification

Assistant will create spec.md:
```
Assistant: "Created feature-requests/FR-001-jwt-refresh-tokens/spec.md
           Please review and approve."
User: [Reviews file]
      "Approved" or "Please add X to the requirements"
```

### 4. Phase 3: Review Implementation Plan

Assistant will create implementation-plan.md:
```
Assistant: "Created implementation plan with 9 tasks across 4 layers.
           Please review and approve."
User: [Reviews file]
      "Approved" or "Let's use approach Y instead of X"
```

### 5. Phase 4: Review Test-First Gate

Assistant will create test-manifest.md and write failing tests:
```
Assistant: "Created test-manifest.md with 12 tests across 3 layers.
           All tests confirmed red. Please review test coverage."
User: [Reviews test-manifest.md and test files]
      "Approved" or "Add a test for edge case X"
```

### 6. Phase 5-6: Monitor Implementation and Review

Assistant spawns sub-agents and begins work:
```
Assistant: "Starting implementation in dependency order...
           [Group 1] common-agent + config-agent complete
           [Group 2] domain-agent + proxy-agent complete
           [Group 3] security-agent complete
           [Group 4] handler-agent complete
           All tasks complete. 12/12 tests green. Created summary.md.
           Entering review-fix loop...
           Full suite green. No findings. Workflow complete."
User: [Reviews changes]
```

---

## Common Scenarios

### Scenario 1: Simple API Endpoint

**Feature:** Add health check endpoint

**Phase 1:** Explore existing endpoints -> Name: "health-check-endpoint"
**Phase 2:** Spec describes endpoint contract (GET /api/health, returns 200 OK)
**Phase 3:** Plan: Handler layer only, 2 tasks (controller + DTO)
**Phase 4:** Write 2 tests (endpoint returns 200, response body matches schema), confirm red
**Phase 5:** handler-agent creates controller, tests go green
**Phase 6:** Full suite green, no findings, auto-complete

**Total Time:** ~40 minutes

---

### Scenario 2: Cross-Layer Feature

**Feature:** JWT refresh token support

**Phase 1:** Explore JWT implementation -> Name: "jwt-refresh-tokens"
**Phase 2:** Spec describes refresh token requirements
**Phase 3:** Plan: 4 layers affected (handler, domain, security, config), 9 tasks
**Phase 4:** Write 12 tests across 3 layers, confirm all red
**Phase 5:** Dependency-ordered groups execute, all tests go green
**Phase 6:** Review catches 1 regressed integration test, fix applied, complete

**Total Time:** ~2.5 hours

---

### Scenario 3: External Integration

**Feature:** Integrate with payment provider API

**Phase 1:** Explore existing integrations -> Name: "payment-provider-integration"
**Phase 2:** Spec describes payment flows, error handling, webhooks
**Phase 3:** Plan: 5 layers affected (handler, domain, proxy, config, common), 15 tasks
**Phase 4:** Write 18 tests (unit + WireMock integration), confirm all red
**Phase 5:** Sub-agents implement clients, error handling, configuration
**Phase 6:** Review catches flaky WireMock timeout, fix applied, complete

**Total Time:** ~4.5 hours

---

## Troubleshooting

### Permission Denied Errors

**Error:**
```
Phase 2 does not allow writing: src/main/java/Foo.java
```

**Solution:**
- Check current phase - Phase 2 can only write spec.md
- If you need to write code, you're in the wrong phase (should be Phase 5)
- Review phase permissions with: `python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --action info`

---

### Missing Deliverable Sections

**Error:**
```
spec.md missing required section: Success Criteria
```

**Solution:**
- Add the missing section to spec.md
- Required sections are defined in feature-workflow.yaml
- Re-run validation: `python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-name"`

---

### Invalid Feature Name

**Error:**
```
Feature name must be lowercase, alphanumeric with hyphens only
```

**Solution:**
- Use kebab-case: "jwt-refresh-tokens" not "JWT_Refresh_Tokens"
- Only lowercase letters, numbers, and hyphens
- 3-50 characters
- No spaces, underscores, dots, or slashes

---

### Unchecked Tasks in Phase 5

**Error:**
```
implementation-plan.md has 3 unchecked tasks (all must be checked in phase 5)
```

**Solution:**
- Complete remaining tasks
- Update checkboxes: `- [ ]` -> `- [x]`
- Re-run validation
- If tasks are no longer needed, remove them or mark as skipped in summary.md

---

### Tests Failing

**Error:**
```
Tests must pass before workflow completion
```

**Solution:**
- Fix failing tests
- Run `./gradlew test`
- Don't proceed to summary.md until all tests pass
- If tests are flaky, investigate and fix root cause

---

### Test-Manifest Missing Red Confirmation

**Error:**
```
test-manifest.md missing required section: Red Confirmation
```

**Solution:**
- Run all Phase 4 tests and record the failures
- Add the Red Confirmation section with timestamps and error summaries
- Every test listed in the Test Inventory must have a corresponding failure entry

---

## Best Practices

### Feature Naming

**Good:**
- jwt-refresh-tokens
- rate-limiting-middleware
- payment-webhook-handler
- user-profile-export

**Bad:**
- JWTRefreshTokens (uppercase)
- jwt_refresh (underscores)
- refresh tokens (spaces)
- jwt.refresh (dots)

---

### Specification Writing

**Good spec.md:**
- Focuses on what, not how
- Clear success criteria
- Measurable requirements
- No implementation details

**Bad spec.md:**
- "Use Redis for caching" (too specific - that's for Phase 3)
- "Add a service" (vague)
- "Make it fast" (not measurable)

---

### Implementation Planning

**Good implementation-plan.md:**
- Tasks grouped by layer
- Each task is atomic and testable
- Clear dependencies between tasks
- Testing strategy defined upfront

**Bad implementation-plan.md:**
- "Implement feature" (too broad)
- Tasks not grouped by layer
- No testing strategy
- Unclear what "done" means

---

### Test-First Gate

**Good test-manifest.md:**
- Every success criterion from spec.md has at least one test
- Tests are specific (assert exact values, not just "no exception")
- Dependency map shows which tests to run first
- Red confirmation has real failure output, not placeholder text

**Bad test-manifest.md:**
- Tests that only check "not null" or "no exception"
- Missing coverage for edge cases in the spec
- No dependency map (tests run in arbitrary order)
- Red confirmation says "all tests fail" without evidence

---

### Phase 5 Execution

**Good practices:**
- Let sub-agents work within their layers in dependency order
- Orchestrator coordinates, doesn't implement
- Update checkboxes as tasks complete
- Run tests frequently - target the Phase 4 red tests
- Create summary.md last

**Bad practices:**
- One agent doing everything (defeats purpose of layers)
- Checking all boxes before work is done
- Skipping tests
- Creating summary.md before work complete
- Ignoring dependency group ordering

---

## FR Numbering Conventions

### Auto-Increment

Feature requests are numbered sequentially starting from FR-001:

```
feature-requests/
├── FR-001-jwt-refresh-tokens/
├── FR-002-rate-limiting/
├── FR-003-payment-integration/
└── FR-004-user-export/
```

The validation script automatically finds the next number:
```bash
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --next-fr-number
# Output: FR-005
```

### Directory Format

Format: `FR-{NNN}-{feature-name}`

- `FR-` prefix (uppercase)
- 3-digit zero-padded number (001, 042, 999)
- Hyphen separator
- kebab-case feature name

**Examples:**
- FR-001-jwt-refresh-tokens
- FR-042-rate-limiting
- fr-1-jwt (lowercase, not zero-padded) -- INVALID
- FR-001_jwt_tokens (underscores) -- INVALID

### Reusing Numbers

- Don't reuse FR numbers even if feature is deleted
- If FR-005 is abandoned, next FR is FR-006, not FR-005
- Gaps in numbering are OK (FR-001, FR-003, FR-005)

---

## Integration with AGENTS.md

The feature request workflow respects and enhances the existing AGENTS.md architecture:

### Project-Level AGENTS.md

Location: `/AGENTS.md`

Purpose: Provides high-level guidance for all agents

Contents:
- Project structure
- Coding standards
- Common patterns
- Global constraints

Usage: All sub-agents in Phase 5 read this first

---

### Layer-Specific AGENTS.md

Locations:
- `src/main/java/com/example/handler/AGENTS.md`
- `src/main/java/com/example/domain/AGENTS.md`
- `src/main/java/com/example/proxy/AGENTS.md`
- `src/main/java/com/example/security/AGENTS.md`
- `src/main/java/com/example/config/AGENTS.md`
- `src/main/java/com/example/common/AGENTS.md`

Purpose: Define layer-specific constraints and patterns

Usage: Sub-agents consult their layer's AGENTS.md for specific rules

Example:
```markdown
# Handler Layer Agent Guide

## Responsibilities
- REST endpoints only
- Request/response DTOs
- Input validation
- HTTP status codes

## Forbidden
- Business logic (belongs in domain)
- Direct database access (use domain services)
- External API calls (use proxy layer)
```

---

### How It Works Together

1. **Phase 3 (Planning):**
   - Planner reads /AGENTS.md to understand architecture
   - Consults layer-specific AGENTS.md to design correctly
   - Creates tasks that align with layer responsibilities

2. **Phase 4 (Test-First Gate):**
   - Test writer reads implementation-plan.md to understand what will be built
   - Writes tests that validate each layer's responsibilities
   - Confirms tests fail without production code

3. **Phase 5 (Implementation):**
   - Orchestrator spawns sub-agents for affected layers in dependency order
   - Each sub-agent reads:
     - `/AGENTS.md` (project-level)
     - Its layer's AGENTS.md (layer-specific)
   - Sub-agents follow both sets of constraints
   - Tests from Phase 4 serve as acceptance criteria

4. **Phase 6 (Review-Fix Loop):**
   - Validates that layer boundaries were respected
   - Fixes any cross-layer violations found during review

5. **Result:**
   - Code changes respect architectural boundaries
   - No layer bleeding (e.g., business logic in controllers)
   - Consistent patterns across features
   - Tests prove correctness before and after implementation

---

## Advanced Usage

### Modifying a Plan Mid-Flight

If you discover issues during Phase 5:

1. **Small changes:** Update implementation-plan.md directly (allowed in Phase 5)
2. **Major changes:** Consider:
   - Stop Phase 5
   - Return to Phase 3 to revise plan
   - Get new approval
   - Update tests in Phase 4 if needed
   - Restart Phase 5

### Partial Implementation

If you can't complete all tasks:

1. Mark completed tasks: `- [x]`
2. In summary.md, document:
   - Which tasks were completed
   - Which tasks remain
   - Why they weren't completed
   - Follow-up FR number (if creating one)

### Splitting Large Features

If feature is too large:

1. **Phase 1/2:** Create high-level spec for full feature
2. **Phase 3:** Break into multiple FRs
3. Create separate FRs for each part:
   - FR-001-jwt-refresh-phase-1 (core functionality)
   - FR-002-jwt-refresh-phase-2 (admin UI)
   - FR-003-jwt-refresh-phase-3 (monitoring)

---

## FAQ

### Q: Can I skip phases?

**A:** No. The workflow has 6 phases and each builds on the previous:
- Phase 2 needs the feature name from Phase 1
- Phase 3 needs the requirements from Phase 2
- Phase 4 needs the plan from Phase 3
- Phase 5 needs the failing tests from Phase 4
- Phase 6 needs the implementation from Phase 5

### Q: Can I go back to a previous phase?

**A:** Yes, but with caveats:
- Can return from Phase 3 to Phase 2 (revise spec)
- Can return from Phase 4 to Phase 3 (revise plan)
- Can return from Phase 5 to Phase 4 (revise tests)
- Cannot modify spec.md after Phase 2 approval (create new FR instead)

### Q: What if I disagree with the plan in Phase 3?

**A:** Don't approve it! Request changes:
- "Use approach X instead of Y"
- "Split task Z into two separate tasks"
- "Add task for monitoring"

Phase 3 is the checkpoint - get it right before Phase 4.

### Q: What if the test coverage in Phase 4 seems insufficient?

**A:** Don't approve the test-manifest. Request changes:
- "Add edge case tests for null input"
- "The spec requires X but no test covers it"
- "Add an integration test for the full flow"

Phase 4 is the test approval gate - ensure coverage before Phase 5.

### Q: Can I run the workflow manually without the skill?

**A:** Yes, but not recommended:
- Validation script can be run manually
- Deliverable structure can be created manually
- But you lose orchestration, sub-agents, and automation

### Q: How do I review an existing FR?

**A:** Just read the files:
```bash
ls feature-requests/FR-001-jwt-refresh-tokens/
cat feature-requests/FR-001-jwt-refresh-tokens/spec.md
cat feature-requests/FR-001-jwt-refresh-tokens/implementation-plan.md
cat feature-requests/FR-001-jwt-refresh-tokens/test-manifest.md
cat feature-requests/FR-001-jwt-refresh-tokens/summary.md
```

All context is in these 4 files.

---

## Next Steps

1. **Test the workflow** with a simple feature (e.g., health-check-endpoint)
2. **Review generated artifacts** in feature-requests/FR-001-*/
3. **Integrate into team process** - when to use, approval process
4. **Customize validation rules** in feature-workflow.yaml if needed
5. **Create layer-specific AGENTS.md** files for your project structure

---

## Resources

- **Workflow YAML:** `.claude/skills/feature-request-v2/references/feature-workflow.yaml`
- **Validation Script:** `.claude/skills/feature-request-v2/scripts/validate-phase.py`
- **Skill Definition:** `.claude/skills/feature-request-v2/SKILL.md`
- **Project Architecture:** `/AGENTS.md`

---

*Last Updated: 2026-03-25*
*Version: 2.0.0*
