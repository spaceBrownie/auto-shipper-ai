## Execution Plan

**Meta-controller:** 3 agents, chunks of 7, deliberative mode
**Implementation plan:** 5 layers, 20 tasks (Layer 1: Code, Layer 2: Config, Layer 3: Docker, Layer 4: Docs, Layer 5: Verification)

**Override:** Using 2 parallel agents instead of 3. The code change (ShipmentTracker) and config (.env.example) are trivial enough for the orchestrator to handle directly in Round 2. Only the Dockerfile (new) and runbook (new, large) warrant dedicated agents in Round 1. A 3rd agent would just sit idle — no task chunk is large enough to justify it.

**Which state parameter was wrong:** `task_count=27` overcounts because many "tasks" are single-line verifications (e.g., "verify .gitignore excludes .env"). Effective parallelizable work units are ~12, not 27.

### Round 0 (Orchestrator)
- 3.1: Create `.dockerignore` (new-file, trivial)

### Round 1 (New Files — 2 agents)
- Agent A: 3.2 Create `Dockerfile` (new-file)
- Agent B: 4.1-4.9 Create `docs/live-e2e-runbook.md` (new-file, all runbook sections)

### Round 2 (Modifications — orchestrator)
- 1.2-1.4: Modify `ShipmentTracker.kt` (@PostConstruct + early return + import)
- 2.1: Rewrite `.env.example`
- 3.3: Extend `docker-compose.yml`

### Round 3 (Tests — orchestrator)
- Write `ShipmentTrackerEmptyProvidersTest.kt` (3 tests from test-spec.md)

### Round 4 (Verification)
- 1.5, 5.1: `./gradlew test`
- 5.2: `docker build -t auto-shipper-ai .`
- 5.3-5.4: Manual verification of env completeness and runbook
- E2E playbook update (new subagent)
