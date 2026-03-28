# 6-Phase Feature Request Workflow Guide

A comprehensive guide for using the 6-phase feature request workflow (v3) in Spring Boot projects.

## Table of Contents

- [Overview](#overview)
- [When to Use This Workflow](#when-to-use-this-workflow)
- [The 6 Phases Explained](#the-6-phases-explained)
  - [Phase 1: Discovery](#phase-1-discovery-read-only)
  - [Phase 2: Specification](#phase-2-specification)
  - [Phase 3: Implementation Planning](#phase-3-implementation-planning)
  - [Phase 4: Test Specification](#phase-4-test-specification)
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

The feature request workflow is a structured, phase-gated approach to implementing new features in Spring Boot projects that follow DDD/hexagonal architecture. It separates discovery from implementation, enforces test specification before TDD implementation, and uses layer-specific sub-agents to maintain architectural boundaries. Per-phase instructions are served from isolated `.md` files via a state machine architecture. `/unblock` is available as a standing directive throughout all phases.

### Key Benefits

- **Structured approach** - Clear phases with defined deliverables
- **Separation of concerns** - Discovery (read-only) separate from implementation (write)
- **Test specification discipline** - Acceptance criteria, boundary cases, and E2E playbook defined before implementation
- **TDD implementation** - Tests written alongside production code guided by test-spec.md
- **Strategic human gates** - Phases 1-3 auto-advance; mandatory human gates before implementation (Phase 5) and PR (Phase 6)
- **Trackable features** - Each feature gets a numbered directory with full documentation
- **Architectural compliance** - Layer-specific agents respect boundaries
- **State machine architecture** - Per-phase instructions served from isolated `.md` files via `validate-phase.py --phase N --instructions`
- **Meta-controller binding** - Recommendations followed autonomously within Phase 5; overrides require explicit justification
- **Deterministic validation** - Python script enforces permissions at every step
- **Review-fix loop** - Automated quality gate catches regressions before completion
- **`/unblock` as standing directive** - Available throughout all phases, not at prescribed hydration points

### The Workflow in a Nutshell

```
Phase 1: Discovery           -> Generate feature name
         (Read-Only)            Auto-advance
                |
                v
Phase 2: Specification       -> Write spec.md
         (Write spec)           Auto-advance
                |
                v
Phase 3: Planning            -> Write implementation-plan.md
         (Write plan)           Auto-advance
                |
                v
Phase 4: Test Specification  -> Write test-spec.md (acceptance criteria,
         (Write test spec)      boundary cases, E2E playbook, contracts)
                |               Optional contract tests for pure domain types
                v
         *** MANDATORY HUMAN GATE ***
         Meta-controller preflight presented
                |
                v
Phase 5: Implementation      -> TDD guided by test-spec.md, create summary.md
         (Write code + tests)   Mandatory E2E test playbook (@docs/e2e-test-playbook.md)
                |
         *** MANDATORY HUMAN GATE ***
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

### Phase 4: Test Specification

**Purpose:** Define what must be tested and how, without writing test code yet. Produces a `test-spec.md` that guides TDD during Phase 5. Optional contract tests may be written for pure domain types only.

**What You Can Do:**
- Read anything (including spec.md and implementation-plan.md)
- Write `test-spec.md` in the feature directory
- Optionally write contract tests for pure domain types (src/test/ only)
- Run contract tests if written

**What You Can't Do:**
- Write production code (src/main/)
- Write full test suites (deferred to Phase 5 TDD)
- Modify spec.md or implementation-plan.md
- Create summary.md

**Deliverables:**
- `test-spec.md` with all required sections
- Optional contract test files for pure domain types

**Required test-spec.md Sections:**
- **Acceptance Criteria** - Testable criteria derived from spec.md success criteria
- **Fixture Data** - Concrete test data values, builder patterns, and shared fixtures
- **Boundary Cases** - Edge cases, null handling, JSON null boundaries, error paths
- **E2E Playbook Scenarios** - End-to-end test scenarios for `@docs/e2e-test-playbook.md`
- **Contract Test Candidates** - Pure domain types suitable for contract tests (optional implementation)

**Test Quality Rules (enforced in Phase 5):**
- Ban `assert(true)` and `assertTrue(true)` — tests must assert meaningful values
- Ban fixture-only assertions (asserting test data matches itself without exercising production code)
- Ban `// Phase 5: deferred` comments — all test logic must be implemented
- JSON null boundary cases required for any API that accepts/returns JSON

**Duration:** Typically 15-30 minutes

**Example:**
```
Phase 3 auto-advances -> Phase 4 begins

Assistant writes:
- test-spec.md with acceptance criteria for 12 scenarios across 3 layers
- Fixture data for RefreshToken, User, and TokenPair
- Boundary cases: expired tokens, null claims, malformed JWTs
- E2E playbook: full refresh flow, expired refresh, concurrent refresh
- Contract test candidates: RefreshToken value object

Optionally writes:
- RefreshTokenContractTest.kt (2 contract tests for pure domain type)

Meta-controller preflight presented at Phase 4->5 gate.
User reviews test-spec.md and approves -> Proceed to Phase 5
```

---

### Phase 5: Implementation

**Purpose:** Execute the plan using layer-specific sub-agents with TDD guided by `test-spec.md`. Tests are written alongside production code, not inherited from Phase 4. Meta-controller recommendations are followed autonomously; overrides require `decision-support/override-justification.md`.

**What You Can Do:**
- Read anything
- Write code in src/ (production and test)
- Update implementation-plan.md (check boxes)
- Create summary.md at the end
- Run builds, tests, git commits
- Write E2E test playbook entries to `@docs/e2e-test-playbook.md`

**What You Can't Do:**
- Modify spec.md (immutable)
- Force push or destructive git operations
- Write outside allowed directories
- Use `assert(true)`, fixture-only assertions, or `// Phase 5: deferred` comments in tests
- Skip JSON null boundary cases for JSON-accepting APIs

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

**Meta-Controller Binding:**
- Meta-controller recommendations from the Phase 4->5 preflight are followed autonomously within Phase 5
- Sub-agents must not deviate from meta-controller recommendations without explicit justification
- If an override is necessary, document it in `decision-support/override-justification.md` within the feature directory

**Workflow:**
1. Orchestrator reads implementation-plan.md and test-spec.md
2. Spawns sub-agents for affected layers in dependency order
3. Sub-agents implement using TDD: write test from test-spec.md criteria, then make it pass
4. Orchestrator updates checkboxes as tasks complete
5. All tests run and pass
6. Orchestrator writes E2E test playbook entries to `@docs/e2e-test-playbook.md` (mandatory)
7. Orchestrator creates summary.md
8. Human gate before Phase 6

**Deliverables:**
- Updated implementation-plan.md (all boxes checked)
- summary.md with all required sections
- Code changes in src/ (production and test)
- All tests passing (written via TDD guided by test-spec.md)
- E2E test playbook entries in `@docs/e2e-test-playbook.md` (mandatory)

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
- Delete or skip tests written during Phase 5
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
Phase 5 completes -> Human gate approved -> Phase 6 begins

Review scan finds:
- 1 existing integration test regressed (NPE from new null handling)
- 1 TDD test has a flaky timing assertion

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

### 2. Phases 1-3: Auto-Advance (Discovery, Specification, Planning)

Phases 1 through 3 auto-advance without requiring manual approval:
```
Assistant explores codebase -> Proposes name: "jwt-refresh-tokens"
         Auto-advances to Phase 2

Assistant creates feature-requests/FR-001-jwt-refresh-tokens/spec.md
         Auto-advances to Phase 3

Assistant creates implementation-plan.md with 9 tasks across 4 layers
         Auto-advances to Phase 4
```

### 3. Phase 4: Test Specification (Auto-Advances to Human Gate)

Assistant writes test-spec.md with acceptance criteria and boundary cases:
```
Assistant: "Created test-spec.md with acceptance criteria for 12 scenarios,
           fixture data, boundary cases, E2E playbook scenarios,
           and contract test candidates."
         Auto-advances to Phase 4->5 human gate
```

### 4. Mandatory Human Gate (Phase 4 -> Phase 5)

Meta-controller preflight is presented for review before implementation begins:
```
Assistant: "Phase 4 complete. Meta-controller preflight:
           - 12 acceptance criteria covering 4 layers
           - 5 boundary cases including JSON null handling
           - 3 E2E playbook scenarios
           - 2 contract test candidates (pure domain types)
           Ready for implementation. Approve to proceed to Phase 5."
User: [Reviews test-spec.md and preflight]
      "Approved" or "Add boundary case for X"
```

### 5. Phase 5: TDD Implementation

Assistant spawns sub-agents and implements with TDD guided by test-spec.md:
```
Assistant: "Starting TDD implementation in dependency order...
           [Group 1] common-agent + config-agent complete
           [Group 2] domain-agent + proxy-agent complete
           [Group 3] security-agent complete
           [Group 4] handler-agent complete
           All tasks complete. Tests written and passing via TDD.
           E2E test playbook entries written. Created summary.md."
```

### 6. Mandatory Human Gate (Phase 5 -> Phase 6)

Human approval required before entering the review-fix loop:
```
Assistant: "Phase 5 complete. All tests green. E2E playbook updated.
           Ready for review-fix loop. Approve to proceed to Phase 6."
User: [Reviews changes]
      "Approved" or "Fix X before proceeding"
```

### 7. Phase 6: Review-Fix Loop

Automated review cycle until clean:
```
Assistant: "Entering review-fix loop...
           Full suite green. No findings. Workflow complete."
User: [Reviews final changes]
```

---

## Common Scenarios

### Scenario 1: Simple API Endpoint

**Feature:** Add health check endpoint

**Phases 1-3:** Auto-advance: Explore endpoints -> Name: "health-check-endpoint" -> Spec -> Plan (handler layer, 2 tasks)
**Phase 4:** Write test-spec.md with 2 acceptance criteria, fixture data, 1 E2E scenario
**Human Gate:** Approve test-spec.md and meta-controller preflight
**Phase 5:** handler-agent creates controller with TDD, writes E2E playbook entry
**Human Gate:** Approve implementation
**Phase 6:** Full suite green, no findings, auto-complete

**Total Time:** ~40 minutes

---

### Scenario 2: Cross-Layer Feature

**Feature:** JWT refresh token support

**Phases 1-3:** Auto-advance: Explore JWT -> Name: "jwt-refresh-tokens" -> Spec -> Plan (4 layers, 9 tasks)
**Phase 4:** Write test-spec.md with 12 acceptance criteria, fixture data, boundary cases, 3 E2E scenarios
**Human Gate:** Approve test-spec.md and meta-controller preflight
**Phase 5:** TDD implementation in dependency-ordered groups, E2E playbook written
**Human Gate:** Approve implementation
**Phase 6:** Review catches 1 regressed integration test, fix applied, complete

**Total Time:** ~2.5 hours

---

### Scenario 3: External Integration

**Feature:** Integrate with payment provider API

**Phases 1-3:** Auto-advance: Explore integrations -> Name: "payment-provider-integration" -> Spec -> Plan (5 layers, 15 tasks)
**Phase 4:** Write test-spec.md with 18 acceptance criteria, WireMock fixture data, JSON null boundary cases, 5 E2E scenarios
**Human Gate:** Approve test-spec.md and meta-controller preflight
**Phase 5:** TDD implementation with sub-agents, WireMock integration tests, E2E playbook written
**Human Gate:** Approve implementation
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

### Test-Spec Missing Required Section

**Error:**
```
test-spec.md missing required section: Boundary Cases
```

**Solution:**
- Add the missing section to test-spec.md
- Required sections: Acceptance Criteria, Fixture Data, Boundary Cases, E2E Playbook Scenarios, Contract Test Candidates
- JSON null boundary cases are required for any API that accepts/returns JSON
- Re-run validation

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

### Test Specification

**Good test-spec.md:**
- Every success criterion from spec.md has at least one acceptance criterion
- Fixture data uses concrete values, not placeholders
- Boundary cases include JSON null handling for all JSON APIs
- E2E playbook scenarios cover the full happy path and key error paths
- Contract test candidates are limited to pure domain types

**Bad test-spec.md:**
- Acceptance criteria that just restate the spec without testable specifics
- Missing JSON null boundary cases for JSON-accepting APIs
- No E2E playbook scenarios
- Contract test candidates for integration-heavy classes (not pure domain)

---

### Phase 5 Execution

**Good practices:**
- Let sub-agents work within their layers in dependency order
- Orchestrator coordinates, doesn't implement
- Use TDD: write test from test-spec.md criteria, then make it pass
- Update checkboxes as tasks complete
- Follow meta-controller recommendations autonomously
- Write E2E test playbook entries to `@docs/e2e-test-playbook.md`
- Create summary.md last

**Bad practices:**
- One agent doing everything (defeats purpose of layers)
- Checking all boxes before work is done
- Writing `assert(true)` or fixture-only assertions
- Using `// Phase 5: deferred` comments in test code
- Skipping JSON null boundary cases
- Ignoring meta-controller recommendations without writing `decision-support/override-justification.md`
- Creating summary.md before work complete
- Ignoring dependency group ordering
- Skipping the mandatory E2E test playbook

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

2. **Phase 4 (Test Specification):**
   - Test spec writer reads implementation-plan.md to understand what will be built
   - Writes acceptance criteria, fixture data, and boundary cases
   - Defines E2E playbook scenarios and contract test candidates
   - Per-phase instructions served via `validate-phase.py --phase 4 --instructions`

3. **Phase 5 (Implementation):**
   - Orchestrator spawns sub-agents for affected layers in dependency order
   - Each sub-agent reads:
     - `/AGENTS.md` (project-level)
     - Its layer's AGENTS.md (layer-specific)
     - Per-phase instructions via `validate-phase.py --phase 5 --instructions`
   - Sub-agents follow both sets of constraints
   - TDD guided by test-spec.md: write test, then make it pass
   - Meta-controller recommendations followed autonomously
   - E2E test playbook entries written to `@docs/e2e-test-playbook.md`

4. **Phase 6 (Review-Fix Loop):**
   - Validates that layer boundaries were respected
   - Fixes any cross-layer violations found during review

5. **Result:**
   - Code changes respect architectural boundaries
   - No layer bleeding (e.g., business logic in controllers)
   - Consistent patterns across features
   - TDD tests prove correctness alongside implementation
   - E2E test playbook provides runnable validation scenarios

---

## Advanced Usage

### Modifying a Plan Mid-Flight

If you discover issues during Phase 5:

1. **Small changes:** Update implementation-plan.md directly (allowed in Phase 5)
2. **Major changes:** Consider:
   - Stop Phase 5
   - Return to Phase 3 to revise plan
   - Update test-spec.md in Phase 4 if needed
   - Pass through the Phase 4->5 human gate again
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
- Phase 5 needs the test specification from Phase 4
- Phase 6 needs the implementation from Phase 5

Note: Phases 1-3 auto-advance, so they feel seamless, but they still execute in order.

### Q: Can I go back to a previous phase?

**A:** Yes, but with caveats:
- Can return from Phase 3 to Phase 2 (revise spec)
- Can return from Phase 4 to Phase 3 (revise plan)
- Can return from Phase 5 to Phase 4 (revise test specification)
- Cannot modify spec.md after Phase 2 (create new FR instead)
- Since Phases 1-3 auto-advance, going back requires explicit user request

### Q: What if I disagree with the plan in Phase 3?

**A:** Don't approve it! Request changes:
- "Use approach X instead of Y"
- "Split task Z into two separate tasks"
- "Add task for monitoring"

Phase 3 is the checkpoint - get it right before Phase 4.

### Q: What if the test specification in Phase 4 seems insufficient?

**A:** Don't approve the test-spec at the Phase 4->5 human gate. Request changes:
- "Add boundary case for null JSON input"
- "The spec requires X but no acceptance criterion covers it"
- "Add an E2E playbook scenario for the full flow"

The Phase 4->5 human gate is the test specification approval point -- ensure coverage before Phase 5.

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
cat feature-requests/FR-001-jwt-refresh-tokens/test-spec.md
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
- **Per-Phase Instructions:** `.claude/skills/feature-request-v2/references/instructions/` (served via `validate-phase.py --phase N --instructions`)
- **Skill Definition:** `.claude/skills/feature-request-v2/SKILL.md`
- **Project Architecture:** `/AGENTS.md`
- **E2E Test Playbook:** `@docs/e2e-test-playbook.md`
- **Override Justification:** `decision-support/override-justification.md` (per feature directory, when needed)

---

*Last Updated: 2026-03-28*
*Version: 3.0.0*
