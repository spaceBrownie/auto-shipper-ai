# Phase 5: Implementation

**Goal:** Execute implementation plan using layer-specific sub-agents. Write tests alongside
implementation in TDD style, guided by test-spec.md.

## Steps

1. **Run strategy preflight (required):**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/meta_controller.py --phase 5 --json --out feature-requests/FR-{NNN}-{name}/decision-support/preflight-meta-controller.json
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --check-decision-support --feature-dir "feature-requests/FR-{NNN}-{name}"
   ```
   The meta-controller auto-infers workload state from implementation-plan.md.

2. **Follow meta-controller recommendations autonomously:**
   - single vs parallel execution
   - decomposition depth
   - cognition mode (instinctual vs deliberative)
   - batch/chunk sizing

   **Override policy:** If you override the meta-controller, you MUST document the reason in
   `decision-support/override-justification.md` with: (1) the recommendation, (2) what was
   done instead, (3) why, and (4) which specific state parameter the meta-controller got wrong.
   If no parameter is wrong, follow the recommendation.

3. **Spawn sub-agents in dependency-ordered groups:**

   | Group | Agents | Depends On |
   |-------|--------|------------|
   | 1 (Foundation) | config-agent, common-agent | None |
   | 2 (Domain Logic) | domain-agent, proxy-agent, security-agent | Group 1 |
   | 3 (Integration) | handler-agent | Group 2 |

   Each sub-agent reads its layer's AGENTS.md for constraints.
   Within each group, agents may run in parallel if no intra-group dependencies.

4. **TDD alongside implementation:**
   - Read test-spec.md for what to test
   - Write test → implement → make test pass → repeat
   - Use test-spec.md acceptance criteria as the test design guide
   - Every test must call production code and assert on its output
   - Use /unblock as needed throughout implementation

5. **Update implementation-plan.md** — check boxes as tasks complete:
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action write --path "feature-requests/FR-{NNN}-{name}/implementation-plan.md"
   ```

6. **Run E2E test playbook in a NEW subagent (mandatory):**
   After all implementation tasks complete, spawn a **fresh subagent** for the E2E playbook.
   This MUST be a new subagent session — the implementation context must not pollute the
   playbook execution. The subagent should:
   - First, update `@docs/e2e-test-playbook.md` with new scenarios from test-spec.md's
     "E2E Playbook Scenarios" section (if any)
   - Then execute the full playbook: `@docs/e2e-test-playbook.md`
   - Report results back to the orchestrator
   This step is mandatory regardless of whether new scenarios were added.

7. **Run full test suite:**
   ```bash
   ./gradlew test
   ```
   All tests must pass — both new and existing.

8. **Create summary.md** — after all checkboxes complete and tests pass:
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action write --path "feature-requests/FR-{NNN}-{name}/summary.md"
   ```

   Required sections:
   - **Feature Summary** — brief overview of what was implemented
   - **Changes Made** — high-level description of changes
   - **Files Modified** — list of all changed files with descriptions
   - **Testing Completed** — test results and coverage
   - **Deployment Notes** — any special deployment considerations

9. **Validate completion:**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"
   ```

10. **Recommended: Run policy regression audit:**
    ```bash
    python3 .claude/skills/feature-request-v2/scripts/evaluate_meta_controller.py --fail-on-mismatch
    python3 -m unittest discover -s .claude/skills/feature-request-v2/tests -p "test_*.py"
    ```

## Deliverables

- Updated `implementation-plan.md` (all checkboxes checked)
- `summary.md` (exactly 1)
- Code changes in src/ or modules/
- All tests passing

## Permissions

- **Read:** Everything
- **Write:** Code files, plan, summary, decision-support, build configs, docs
- **Bash:** Full development workflow (gradlew, git add, git commit, mkdir)
- **Forbidden:** rm -rf, git push --force, git reset --hard
