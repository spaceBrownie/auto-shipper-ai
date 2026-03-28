# Phase 4: Test Specification

**Goal:** Define what to test in testable terms before any production code is written.

This phase produces a test specification document, NOT test files. Behavioral tests against
code that doesn't exist can only be placeholders. Phase 5 writes real tests alongside
implementation in TDD style.

## Steps

1. **Read spec.md and implementation-plan.md** — understand requirements, success criteria,
   boundary conditions, and layer contracts.

2. **Use /unblock as needed** — query `unblocked_context_engine` for existing test patterns
   in the modules being touched. Look for: test conventions, fixture patterns, common
   assertion styles, and tests previously written for similar features.

3. **Write test-spec.md** — validate before writing:
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --action write --path "feature-requests/FR-{NNN}-{name}/test-spec.md"
   ```

   Required sections:

   - **Acceptance Criteria** — testable assertions derived from spec success criteria.
     Each criterion maps to specific assert statements that Phase 5 will implement.

   - **Fixture Data** — JSON payloads, edge case values, sample inputs/outputs.
     Include realistic data for happy path AND error paths.

   - **Boundary Cases** — explicitly including:
     - JSON `null` for every external API field extraction (NullNode guard — CLAUDE.md #17)
     - Missing fields (field absent from JSON)
     - Type mismatches (string where number expected)
     - Threshold values (margin boundaries, rate limits, SLA windows)
     - Empty collections, zero quantities, negative values where applicable

   - **E2E Playbook Scenarios** — new scenarios to add to `@docs/e2e-test-playbook.md`
     for this feature. Define the full flow: setup, action, expected outcome.

   - **Contract Test Candidates** — ONLY for pure domain types:
     - State machine transition rules
     - Domain event structure and required fields
     - Interface signatures and contracts
     These are the ~10% that CAN compile meaningfully against stubs.

4. **Optional: Write contract tests** for domain types listed above.
   - These must call production code (or meaningful stubs) and assert on output.
   - Ban: `assert(true)`, fixture-only assertions (`payload.contains(...)`),
     `// Phase 5:` deferred comments.
   - If production code doesn't exist for a test category, put it in test-spec.md
     instead of writing a placeholder test file.
   - If contract tests are written, verify compilation:
     ```bash
     ./gradlew compileTestKotlin
     ```

5. **Validate deliverables:**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 4 --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"
   ```

## Deliverable

`feature-requests/FR-{NNN}-{feature-name}/test-spec.md` (primary)
Optional: contract test files for pure domain types

## Permissions

- **Read:** Everything
- **Write:** `feature-requests/FR-*/test-spec.md`, `src/test/**`, `modules/**/src/test/**`
- **Bash:** ls, cat, grep, `./gradlew compileTestKotlin` (optional)

## Quality Rules

- Every external API field extraction must have a JSON `null` boundary case
- No `assert(true)` — if you can't write a meaningful assertion, document it in test-spec.md
- No fixture-only assertions (`payload.contains(...)`) — tests must call production code
- No `// Phase 5:` deferred comments — if it can't be tested now, it goes in test-spec.md
