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

3. **Scan postmortems for relevant prevention items (required):**
   Before spawning any sub-agents, scan `docs/postmortems/PM-*.md` for unchecked prevention
   items (`- [ ]`) that are relevant to the modules being modified. Include these as explicit
   constraints in every sub-agent prompt. This prevents repeating known bugs.

   Example: if modifying the fulfillment module and PM-015 has an unchecked item about
   `@Async` on AFTER_COMMIT listeners, include that as a mandatory constraint.

4. **Reconcile meta-controller + implementation plan into an execution plan (required):**

   Three inputs feed the execution strategy. They are complementary, not competing:

   | Input | Determines | Source |
   |-------|-----------|--------|
   | **Implementation plan tiers** | WHAT can run when (dependency order) | `implementation-plan.md` "Dependency Order" section |
   | **Meta-controller** | HOW MANY agents to use, chunk size, cognition mode | `preflight-meta-controller.json` |
   | **Round structure** | The safety PATTERN (new files → mods → tests) | This document (step 5) |

   **Reconciliation algorithm:**

   a. Parse the implementation plan's tier structure (Tier 0, 1, 2, ...).
   b. Classify each task as: `new-file`, `modify-existing`, or `test`.
   c. Group tasks into rounds using the round structure (step 5), respecting tier dependencies:
      - Round 0: Tier 0 `new-file` tasks (orchestrator, no agents)
      - Round 1: Tier 1+ `new-file` tasks, split across N agents (from meta-controller)
      - Round 2: All `modify-existing` tasks, sequentially or parallel by file
      - Round 3: All `test` tasks, split across N agents
   d. Within each round, the meta-controller's **chunk size** determines how many tasks
      each agent receives. If the meta-controller says "chunks of 12" and Round 1 has 8
      new-file tasks, one agent handles all 8. If Round 3 has 15 test tasks and chunk
      size is 12, split into 2 agents (one gets 8, one gets 7).
   e. The meta-controller's **cognition mode** (deliberative vs instinctual) determines
      how detailed the agent prompts are. Deliberative = include implementation plan code
      verbatim + all constraints. Instinctual = concise prompts with file paths and patterns.

   **Write the execution plan** to `decision-support/execution-plan.md` before spawning
   any agents. Format:

   ```markdown
   ## Execution Plan

   **Meta-controller:** {N} agents, chunks of {M}, {cognition} mode
   **Implementation plan:** {T} tiers, {X} tasks

   ### Round 0 (Orchestrator)
   - S-1: Create OrderShipped.kt (new-file, Tier 0)
   - ...

   ### Round 1 (New Files — {N} agents)
   Agent A: H-2, H-3, D-2 (new-file, Tier 1-2)
   Agent B: P-2, P-3, H-4 (new-file, Tier 2-3)

   ### Round 2 (Modifications)
   - D-1: Modify OrderService.kt (Tier 1)
   - A-1: Modify application.yml (Tier 2)

   ### Round 3 (Tests — {N} agents)
   Agent A: P-4, H-5, H-6, D-3 (test, Tier 1-3)
   Agent B: P-5, H-7, D-4, I-1 (test, Tier 3-4)
   ```

   **Override policy:** If the reconciliation doesn't work (e.g., the implementation plan
   has no tier structure), document why in `decision-support/override-justification.md`
   and use the meta-controller's chunk recommendation directly.

5. **Execute in dependency-ordered ROUNDS (mandatory structure):**

   Sub-agents MUST be spawned in dependency-ordered rounds, NOT by functional area.
   Each round produces a compilable increment. This is the proven FR-025 pattern
   (PM-018) and must not be collapsed into monolithic per-area agents.

   | Round | What | Agents | Depends On |
   |-------|------|--------|------------|
   | 0 | Orchestrator creates foundation | Orchestrator (no agents) | None |
   | 1 | New files only | Up to N agents (meta-controller) | Round 0 |
   | 2 | Modify existing files | Sequential or parallel by file | Round 1 |
   | 3 | Write all tests | Up to N agents (meta-controller) | Round 2 |

   **Round 0 (Orchestrator):** The orchestrator directly creates simple foundation pieces
   (domain events, value objects, config properties, static mappers) that have no dependencies.
   This avoids agent overhead for trivial files.

   **Round 1 (New files):** Each agent creates new files only — never modifies existing files.
   Agents can run in parallel because they write to non-overlapping file paths.
   Agent count comes from the meta-controller recommendation.

   **Round 2 (Modifications):** Agents modify existing files (e.g., adding event publication
   to an existing service, updating application.yml). Run sequentially if modifying the
   same file; parallel if modifying different files.

   **Round 3 (Tests):** Agents write all test files. By this point, all production code exists
   and compiles. Tests can reference real classes, not placeholders.

   **Compile check between rounds:** Run `./gradlew compileKotlin` after each round to catch
   issues before they cascade.

6. **Implementation plan code is the floor:**
   Sub-agent prompts MUST include the exact code snippets from implementation-plan.md as the
   starting point for each file. Agents may improve the code (add error handling, refine
   logging) but must not regress below it. If the plan says "publish event after save,"
   the agent must publish the event after save — not before, not in a different method.

   When the implementation plan has layer-by-layer code (e.g., "Layer 7: CjTrackingProcessingService"),
   copy the relevant layer's code block into the agent prompt verbatim.

7. **TDD alongside implementation:**
   - Read test-spec.md for what to test
   - Write test → implement → make test pass → repeat
   - Use test-spec.md acceptance criteria as the test design guide
   - Every test must call production code and assert on its output
   - Use /unblock as needed throughout implementation

8. **Update implementation-plan.md** — check boxes as tasks complete:
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action write --path "feature-requests/FR-{NNN}-{name}/implementation-plan.md"
   ```

9. **Post-implementation E2E scenario augmentation (required):**
   After all implementation tasks complete but BEFORE spawning the E2E subagent, the
   orchestrator must review the implemented code and augment test-spec.md's E2E scenarios
   with adversarial/failure cases based on the actual integration points:

   - **Dedup-then-crash scenario:** If the feature has dedup + async processing, add a
     scenario verifying the system handles processing failure after dedup commit
   - **Response-timing scenario:** If the feature receives external webhooks, add a scenario
     verifying the HTTP response returns within the external system's expected timeout
   - **Contract verification scenario:** If the feature calls external APIs via stubs in
     local profile, add a scenario that verifies the request shapes match WireMock fixtures
   - **Thread boundary scenarios:** If the feature uses @Async, add scenarios that verify
     the async processing completes (check eventual state, not just immediate response)

   Write these augmented scenarios into the E2E playbook update instructions for the subagent.

10. **Run E2E test playbook in a NEW subagent (mandatory):**
    After all implementation tasks complete, spawn a **fresh subagent** for the E2E playbook.
    This MUST be a new subagent session — the implementation context must not pollute the
    playbook execution. The subagent should:
    - First, update `@docs/e2e-test-playbook.md` with new scenarios from test-spec.md's
      "E2E Playbook Scenarios" section AND the orchestrator's augmented scenarios from step 9
    - Then execute the full playbook: `@docs/e2e-test-playbook.md`
    - Report results back to the orchestrator
    This step is mandatory regardless of whether new scenarios were added.

11. **Run full test suite:**
    ```bash
    ./gradlew test
    ```
    All tests must pass — both new and existing.

12. **Create summary.md** — after all checkboxes complete and tests pass:
    ```bash
    python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --action write --path "feature-requests/FR-{NNN}-{name}/summary.md"
    ```

    Required sections:
    - **Feature Summary** — brief overview of what was implemented
    - **Changes Made** — high-level description of changes
    - **Files Modified** — list of all changed files with descriptions
    - **Testing Completed** — test results and coverage
    - **Deployment Notes** — any special deployment considerations

13. **Validate completion:**
    ```bash
    python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 5 --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"
    ```

14. **Recommended: Run policy regression audit:**
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
