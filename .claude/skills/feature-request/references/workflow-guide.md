# Feature Request Workflow Guide

A comprehensive guide for using the 4-phase feature request workflow in Spring Boot projects.

## Table of Contents

- [Overview](#overview)
- [When to Use This Workflow](#when-to-use-this-workflow)
- [The 4 Phases Explained](#the-4-phases-explained)
- [Getting Started](#getting-started)
- [Common Scenarios](#common-scenarios)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)
- [FR Numbering Conventions](#fr-numbering-conventions)
- [Integration with AGENTS.md](#integration-with-agentsmd)

---

## Overview

The feature request workflow is a structured, phase-gated approach to implementing new features in Spring Boot projects that follow DDD/hexagonal architecture. It separates discovery from implementation, enforces clear deliverables, and uses layer-specific sub-agents to maintain architectural boundaries.

### Key Benefits

- ✅ **Structured approach** - Clear phases with defined deliverables
- ✅ **Separation of concerns** - Discovery (read-only) separate from implementation (write)
- ✅ **Reviewable progress** - Manual approval gates between phases
- ✅ **Trackable features** - Each feature gets a numbered directory with full documentation
- ✅ **Architectural compliance** - Layer-specific agents respect boundaries
- ✅ **Deterministic validation** - Python script enforces permissions at every step

### The Workflow in a Nutshell

```
Phase 1: Discovery        → Generate feature name
         (Read-Only)         Manual approval ✓
                ↓
Phase 2: Specification    → Write spec.md
         (Write spec)        Manual approval ✓
                ↓
Phase 3: Planning         → Write implementation-plan.md
         (Write plan)        Manual approval ✓
                ↓
Phase 4: Implementation   → Execute plan, write code, create summary.md
         (Write code)        Tests pass, workflow complete ✓
```

---

## When to Use This Workflow

### ✅ Use This Workflow For:

- **New features** that span multiple architectural layers
- **Significant enhancements** requiring design decisions
- **Features requiring coordination** across handler, domain, proxy, security, config, or common layers
- **Features that need documentation** for future reference or team collaboration
- **Complex changes** where planning ahead saves time

### ❌ Don't Use This Workflow For:

- **Bug fixes** - Use standard bug fix workflow
- **Simple refactoring** - Make direct changes
- **Documentation updates** - Edit docs directly
- **Configuration tweaks** - Modify config files directly
- **One-liner changes** - Too much overhead for trivial changes

### Gray Area - Consider Using When:

- Change touches 3+ files across multiple layers → Probably use workflow
- Unsure about technical approach → Use workflow for planning
- Team wants traceability → Use workflow for documentation
- Quick experiment → Skip workflow, use it later if experiment succeeds

---

## The 4 Phases Explained

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
User approves → Proceed to Phase 2
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

### Phase 4: Implementation

**Purpose:** Execute the plan using layer-specific sub-agents.

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

**Sub-Agents:**
- **orchestrator** - Coordinates work, updates plan, creates summary
- **handler-agent** - REST endpoints (src/main/java/**/handler/)
- **domain-agent** - Business logic (src/main/java/**/domain/)
- **proxy-agent** - External clients (src/main/java/**/proxy/)
- **security-agent** - Auth/JWT (src/main/java/**/security/)
- **config-agent** - Spring beans (src/main/java/**/config/)
- **common-agent** - DTOs/exceptions (src/main/java/**/common/)

**Workflow:**
1. Orchestrator reads implementation-plan.md
2. Spawns sub-agents for affected layers
3. Sub-agents implement their tasks
4. Orchestrator updates checkboxes as tasks complete
5. All tests run and pass
6. Orchestrator creates summary.md
7. Workflow complete

**Deliverables:**
- Updated implementation-plan.md (all boxes checked)
- summary.md with all required sections
- Code changes in src/
- Passing tests

**Duration:** Variable (30 minutes to several hours depending on complexity)

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

### 5. Phase 4: Monitor Implementation

Assistant spawns sub-agents and begins work:
```
Assistant: "Starting implementation...
           [handler-agent] ✅ Created RefreshTokenController
           [domain-agent] ✅ Implemented RefreshTokenService
           ...
           All tasks complete. Tests passing. Created summary.md"
User: [Reviews changes]
```

---

## Common Scenarios

### Scenario 1: Simple API Endpoint

**Feature:** Add health check endpoint

**Phase 1:** Explore existing endpoints → Name: "health-check-endpoint"
**Phase 2:** Spec describes endpoint contract (GET /api/health, returns 200 OK)
**Phase 3:** Plan: Handler layer only, 2 tasks (controller + DTO)
**Phase 4:** handler-agent creates controller, tests pass

**Total Time:** ~30 minutes

---

### Scenario 2: Cross-Layer Feature

**Feature:** JWT refresh token support

**Phase 1:** Explore JWT implementation → Name: "jwt-refresh-tokens"
**Phase 2:** Spec describes refresh token requirements
**Phase 3:** Plan: 4 layers affected (handler, domain, security, config), 9 tasks
**Phase 4:** Multiple sub-agents work in parallel, orchestrator coordinates

**Total Time:** ~2 hours

---

### Scenario 3: External Integration

**Feature:** Integrate with payment provider API

**Phase 1:** Explore existing integrations → Name: "payment-provider-integration"
**Phase 2:** Spec describes payment flows, error handling, webhooks
**Phase 3:** Plan: 5 layers affected (handler, domain, proxy, config, common), 15 tasks
**Phase 4:** Sub-agents implement clients, error handling, configuration

**Total Time:** ~4 hours

---

## Troubleshooting

### Permission Denied Errors

**Error:**
```
❌ Phase 2 does not allow writing: src/main/java/Foo.java
```

**Solution:**
- Check current phase - Phase 2 can only write spec.md
- If you need to write code, you're in the wrong phase (should be Phase 4)
- Review phase permissions with: `python validate-phase.py --phase 2 --action info`

---

### Missing Deliverable Sections

**Error:**
```
❌ spec.md missing required section: Success Criteria
```

**Solution:**
- Add the missing section to spec.md
- Required sections are defined in feature-workflow.yaml
- Re-run validation: `python validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001-name"`

---

### Invalid Feature Name

**Error:**
```
❌ Feature name must be lowercase, alphanumeric with hyphens only
```

**Solution:**
- Use kebab-case: "jwt-refresh-tokens" not "JWT_Refresh_Tokens"
- Only lowercase letters, numbers, and hyphens
- 3-50 characters
- No spaces, underscores, dots, or slashes

---

### Unchecked Tasks in Phase 4

**Error:**
```
❌ implementation-plan.md has 3 unchecked tasks (all must be checked in phase 4)
```

**Solution:**
- Complete remaining tasks
- Update checkboxes: `- [ ]` → `- [x]`
- Re-run validation
- If tasks are no longer needed, remove them or mark as skipped in summary.md

---

### Tests Failing

**Error:**
```
❌ Tests must pass before workflow completion
```

**Solution:**
- Fix failing tests
- Run `mvn test` or `gradle test`
- Don't proceed to summary.md until all tests pass
- If tests are flaky, investigate and fix root cause

---

## Best Practices

### Feature Naming

✅ **Good:**
- jwt-refresh-tokens
- rate-limiting-middleware
- payment-webhook-handler
- user-profile-export

❌ **Bad:**
- JWTRefreshTokens (uppercase)
- jwt_refresh (underscores)
- refresh tokens (spaces)
- jwt.refresh (dots)

---

### Specification Writing

✅ **Good spec.md:**
- Focuses on what, not how
- Clear success criteria
- Measurable requirements
- No implementation details

❌ **Bad spec.md:**
- "Use Redis for caching" (too specific - that's for Phase 3)
- "Add a service" (vague)
- "Make it fast" (not measurable)

---

### Implementation Planning

✅ **Good implementation-plan.md:**
- Tasks grouped by layer
- Each task is atomic and testable
- Clear dependencies between tasks
- Testing strategy defined upfront

❌ **Bad implementation-plan.md:**
- "Implement feature" (too broad)
- Tasks not grouped by layer
- No testing strategy
- Unclear what "done" means

---

### Phase 4 Execution

✅ **Good practices:**
- Let sub-agents work within their layers
- Orchestrator coordinates, doesn't implement
- Update checkboxes as tasks complete
- Run tests frequently
- Create summary.md last

❌ **Bad practices:**
- One agent doing everything (defeats purpose of layers)
- Checking all boxes before work is done
- Skipping tests
- Creating summary.md before work complete

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
python validate-phase.py --next-fr-number
# Output: FR-005
```

### Directory Format

Format: `FR-{NNN}-{feature-name}`

- `FR-` prefix (uppercase)
- 3-digit zero-padded number (001, 042, 999)
- Hyphen separator
- kebab-case feature name

**Examples:**
- ✅ FR-001-jwt-refresh-tokens
- ✅ FR-042-rate-limiting
- ❌ fr-1-jwt (lowercase, not zero-padded)
- ❌ FR-001_jwt_tokens (underscores)

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

Usage: All sub-agents in Phase 4 read this first

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

2. **Phase 4 (Implementation):**
   - Orchestrator spawns sub-agents for affected layers
   - Each sub-agent reads:
     - `/AGENTS.md` (project-level)
     - Its layer's AGENTS.md (layer-specific)
   - Sub-agents follow both sets of constraints

3. **Result:**
   - Code changes respect architectural boundaries
   - No layer bleeding (e.g., business logic in controllers)
   - Consistent patterns across features

---

## Advanced Usage

### Modifying a Plan Mid-Flight

If you discover issues during Phase 4:

1. **Small changes:** Update implementation-plan.md directly (allowed in Phase 4)
2. **Major changes:** Consider:
   - Stop Phase 4
   - Return to Phase 3 to revise plan
   - Get new approval
   - Restart Phase 4

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

**A:** No. Each phase builds on the previous:
- Phase 2 needs the feature name from Phase 1
- Phase 3 needs the requirements from Phase 2
- Phase 4 needs the plan from Phase 3

### Q: Can I go back to a previous phase?

**A:** Yes, but with caveats:
- Can return from Phase 3 to Phase 2 (revise spec)
- Can return from Phase 4 to Phase 3 (revise plan)
- Cannot modify spec.md after Phase 2 approval (create new FR instead)

### Q: What if I disagree with the plan in Phase 3?

**A:** Don't approve it! Request changes:
- "Use approach X instead of Y"
- "Split task Z into two separate tasks"
- "Add task for monitoring"

Phase 3 is the checkpoint - get it right before Phase 4.

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
cat feature-requests/FR-001-jwt-refresh-tokens/summary.md
```

All context is in these 3 files.

---

## Next Steps

1. **Test the workflow** with a simple feature (e.g., health-check-endpoint)
2. **Review generated artifacts** in feature-requests/FR-001-*/
3. **Integrate into team process** - when to use, approval process
4. **Customize validation rules** in feature-workflow.yaml if needed
5. **Create layer-specific AGENTS.md** files for your project structure

---

## Resources

- **Workflow YAML:** `.claude/skills/feature-request/references/feature-workflow.yaml`
- **Validation Script:** `.claude/skills/feature-request/scripts/validate-phase.py`
- **Skill Definition:** `.claude/skills/feature-request/SKILL.md`
- **Project Architecture:** `/AGENTS.md`

---

*Last Updated: 2026-02-09*
*Version: 1.0.0*
