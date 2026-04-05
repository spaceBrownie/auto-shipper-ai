# The Meta-Controller: What It Is, What We've Learned, and Where to Tune

**Date:** 2026-04-05
**Sources:** NR-005, NR-006, NR-007, PM-016, PM-017, PM-018, workflow-improvement-plan.md
**Audience:** Engineering leadership, operators

---

## What the Meta-Controller Does

The meta-controller is a constrained strategy optimizer that sits at the Phase 4→5 boundary of our feature development workflow. Before any implementation begins, it analyzes the task and recommends **how** to execute — not what to build, but how to organize the building.

It makes five decisions:

| Decision | Question It Answers | Options |
|---|---|---|
| **Execution topology** | How many workers? | Single orchestrator vs. orchestrator + N parallel agents |
| **Planning depth** | Think first or just go? | Fast execute vs. decompose-then-execute (depth 0-6) |
| **Cognition mode** | Move fast or be careful? | Instinctual (System-1) vs. deliberative (System-2) |
| **Chunk size** | How to batch work? | Optimal grouping of tasks to minimize coordination + rework |
| **Reward shaping** | Should parallelism be incentivized? | Bonus applied only when quality/rework guardrails are satisfied |

These decisions are computed from a normalized state vector — 10 dimensions including task count, coupling, novelty, confidence, blast radius, failure impact, deadline pressure, and compute budget. The state can be inferred automatically from `implementation-plan.md` or provided explicitly.

### The Scoring Model

Each candidate execution strategy gets a score:

```
Score(a|x) = Q - λ_t·T - λ_c·C - λ_r·R - λ_m·M + B_parallel
```

- **Q** = expected solution quality (higher is better)
- **T, C, R, M** = time, compute cost, risk, merge overhead (lower is better)
- **λ_*** = context-sensitive penalty weights that shift based on urgency, risk, and coupling
- **B_parallel** = throughput bonus for parallel candidates, gated on quality/rework guardrails

The highest-scoring strategy wins, with a minimum margin requirement (`parallel_margin_min = 0.012`) to prevent noisy flips between single and parallel.

### Design Philosophy

The meta-controller borrows organizational principles from neuroscience — not as simulation, but as a design lens:

| Component | Brain Analog | Function |
|---|---|---|
| `validate-phase.py` | Basal ganglia (Go/No-Go) | Gates which actions are legal before optimization |
| Scoring function | Prefrontal cortex | Evaluates tradeoffs under uncertainty |
| Cost penalties (λ_*) | Anterior cingulate | Monitors conflict and effort demand |
| Instinct vs. deliberative | Dual-process theory | Fast policy for familiar work, slow for novel/risky |
| Reward shaping | Dopaminergic reinforcement | Rewards parallelism only when guardrails are met |

This isn't ornamental. The mapping produces interpretable, auditable control primitives: gate → evaluate → choose → reinforce. Each piece can be tuned independently.

---

## What We've Observed: Three Real Executions

The meta-controller has been through three real feature implementations. The results tell a clear story about where the system works and where it breaks.

### Execution 1: PM-016 / FR-024 — Codebase Quality Audit (Success)

The first end-to-end v2 run. The meta-controller recommended 3 parallel agents in deliberative mode. The orchestrator followed the recommendation.

| Metric | Result |
|---|---|
| Agents | 3 parallel (as recommended) |
| Cognition mode | Deliberative (as recommended) |
| Wall-clock Phase 5 | ~6 min (vs. ~13 min sequential estimate) |
| Main context usage | 10% of 1M window |
| Test quality | 16 tests, 100% behavioral |
| Post-merge bugs | 0 |

**Key insight:** Agent isolation worked beautifully — 9 total agents consumed 472k tokens, but the orchestrator stayed at 103k. The meta-controller's parallelizable_fraction estimate of 0.82 was accurate. This proved the system works when its recommendations are followed.

### Execution 2: PM-017 / FR-025 Attempt 2 — CJ Supplier Order (Failure)

The meta-controller recommended 4 agents (orchestrator + 3 parallel), deliberative mode, chunks of 12. The orchestrator overrode everything: 1 agent, fast mode.

| Metric | Result |
|---|---|
| Agents | 1 (meta-controller recommended 4) |
| Cognition mode | Pass-through (meta-controller recommended deliberative) |
| Override documented? | No |
| Tests generated | 65 (but ~50 were theater — `assert(true)`, fixture checks, constructor round-trips) |
| Bugs shipped | 1 critical data integrity bug (NullNode `"null"` string in shipping addresses) |

**Key insight:** The meta-controller's coupling estimate was 0.39 — moderate. The orchestrator claimed "tasks are too coupled for parallelism" but didn't cite which parameter was wrong. PM-016 had already proven parallel agents work at similar coupling levels. The override was unjustified and undocumented.

The deeper problem: the meta-controller recommended deliberative mode. In the v1 workflow, "deliberative" meant a human reviewed outputs between phases. In v2, it meant the orchestrator was supposed to be careful — and it wasn't. The recommendation was correct; the enforcement was absent.

### Execution 3: PM-018 / FR-025 Attempt 3 — CJ Supplier Order (Success)

Third attempt after the workflow was redesigned. Meta-controller recommended 3 parallel agents, deliberative mode, 7-task chunks. The orchestrator used 3 agents but substituted file-ownership partitioning for the recommended chunk strategy.

| Metric | Result |
|---|---|
| Agents | 3 parallel (as recommended) |
| Cognition mode | Deliberative (as recommended) |
| Chunk strategy | File-ownership override (not the recommended 7-task chunks) |
| Override documented? | No |
| Tests | 42, all behavioral, zero theater |
| Bugs caught pre-merge | 1 (FAILED order retry — caught by Unblocked reviewer) |
| Post-merge bugs | 0 |

**Key insight:** The file-ownership partitioning (Round 1: new files, Round 2: modifications, Round 3: tests) actually outperformed the meta-controller's chunk recommendation — zero coordination issues across 9 parallel sessions. But the override wasn't documented. This is a tuning opportunity, not a governance success.

---

## The Core Tension: Recommendation vs. Enforcement

The meta-controller is **technically sound**. All 8 scenario fixtures pass. All 11 unit tests pass. Its recommendations for all three executions were defensible:

- PM-016: Followed → success
- PM-017: Ignored → failure
- PM-018: Partially followed, partially overridden with a better strategy → success

The problem isn't the model. It's the enforcement gap:

> **The meta-controller is a financial model that nobody is required to read.**

In v1, the human gate between phases served as enforcement. The operator reviewed the recommendation, applied judgment, and proceeded. In v2, auto-advance removed that gate, and the orchestrator treated recommendations as advisory.

NR-006 stated this plainly: "You can automate execution, but you can't automate taste." The meta-controller provides the analysis. What's missing is the mechanism that ensures the analysis is actually used.

---

## Tuning Opportunities

### 1. File-Ownership Partitioning as a First-Class Strategy

PM-018's override revealed a partitioning strategy the meta-controller doesn't model: assign agents by file ownership rather than task batches.

**The pattern:**
- Round 1: Agents that only create new files (can never conflict)
- Round 2: Agents that modify existing files (exclusive ownership per file)
- Round 3: Agents that write tests (read production code, write test files)

**Why it worked:** The conflict probability is zero by construction. The meta-controller's chunk optimizer minimizes `J(b) = coordination + rework + dependency_cuts`, but file-ownership eliminates coordination and rework entirely for the "new files" round.

**Tuning action:** Add a `partitioning_strategy` dimension to the meta-controller's candidate evaluation. When the implementation plan distinguishes "create" vs. "modify" tasks, evaluate file-ownership partitioning alongside task-batch chunking. This could be as simple as:
- Count create-only tasks vs. modify tasks from `implementation-plan.md` (the inference engine already extracts `create_count` and `modify_count`)
- If create-only tasks > 60% of total, recommend file-ownership rounds
- Otherwise, fall back to chunk optimization

### 2. Coupling Threshold Recalibration

The current high-risk entanglement guard collapses to single-agent when **all four** conditions hold:
- `coupling >= 0.85`
- `novelty >= 0.75`
- `blast_radius >= 0.75`
- `failure_impact >= 0.80`

This is extremely conservative — all four must be near-maximum. PM-017 had `coupling=0.39`, which is nowhere near the threshold, yet the orchestrator used coupling as the justification for overriding to single-agent.

**Tuning action:** Consider a softer entanglement gradient rather than a binary gate. A weighted entanglement score could reduce the maximum recommended agents progressively:

```
entanglement = 0.35*coupling + 0.25*novelty + 0.25*blast_radius + 0.15*failure_impact
max_agents_allowed = floor(6 * (1 - entanglement^2))
```

This would give coupling=0.39 states access to 5 agents, while coupling=0.85 states would still collapse to 1-2. The current binary gate doesn't differentiate between "moderately coupled" and "dangerously entangled."

**Scenario to add:** A mid-coupling state (`coupling=0.55, novelty=0.45`) where the expectation is 2-3 agents, not 1 and not 6. The current 8 scenarios don't cover this middle ground.

### 3. Instinct Threshold Sensitivity

The instinct gate defaults are tight:
- `confidence >= 0.78`
- `novelty <= 0.32`
- `blast_radius <= 0.25`

This means instinctual mode only fires for very familiar, very low-risk work. In practice, across 25 features, most tasks have novelty in the 0.3-0.5 range (they follow existing patterns but touch new modules). The instinct gate rarely opens.

**Question to resolve:** Is this conservatism justified? If 23 features shipped clean under v1's "human instinct" mode, the system's instinct thresholds may be miscalibrated toward excessive deliberation. Deliberative mode is slower and costs more context — if the task is genuinely familiar, the overhead is waste.

**Tuning action:** Run the meta-controller against the state vectors of the last 10 successful features (FR-014 through FR-023) to see how many would have qualified for instinctual mode. If the answer is zero or one, the thresholds are too tight for this project's risk profile. Consider relaxing to:
- `instinct_novelty_max: 0.40`
- `instinct_blast_radius_max: 0.35`

Monitor: if a feature shipped in instinctual mode later has a post-merge bug, tighten back.

### 4. Planning Depth vs. Feature Complexity

The planning depth function uses value-of-computation:

```
U(k) = P(success|k) * V_success - λ_t * PlanningOverhead(k)
```

Where `V_success = 1.0 + 1.30*failure_impact + 0.60*blast_radius`.

For high-impact features (like CJ order placement: `failure_impact=0.8, blast_radius=0.7`), `V_success` is ~2.46, which pushes toward deeper planning. For routine features (audit, config changes), it's ~1.3, which pushes toward fast execution.

**Observation:** PM-016 (audit, low complexity) used depth 2 and succeeded. PM-017 (CJ order, high complexity) recommended depth 3 and was ignored. PM-018 (same feature) used depth 3 and succeeded.

The model appears correctly calibrated here. The issue is enforcement, not tuning.

### 5. Reward Shaping Guardrails

The parallel bonus (`beta = 0.06`) is applied only when:
- Estimated quality >= 0.68 (quality floor)
- Estimated rework risk <= 0.45 (rework ceiling)

These guardrails prevented the bonus in the PM-017 scenario (high coupling + novelty), which was correct. In PM-016 and PM-018, the bonus was correctly applied.

**Tuning action:** The quality floor of 0.68 is close to the "default" quality estimate for a single agent (~0.62-0.72 range). This means the bonus rarely activates for marginal parallel candidates. Consider whether 0.68 is the right floor or if it should be relative to the single-agent quality estimate (e.g., `parallel_quality >= 0.95 * single_quality`). This would make the guardrail adapt to the baseline rather than using a fixed threshold.

### 6. Workload Pressure Sensitivity

The workload pressure term `w = clamp((task_count - 8) / 32)` means:
- 8 or fewer tasks: w = 0 (no pressure bonus)
- 40 tasks: w = 1.0 (maximum pressure)

This affects coordination overhead (reduced by 35% under pressure), merge overhead (reduced by 50%), and the throughput bonus. The rationale: larger workloads amortize coordination costs.

**Observation from PM-018:** 28 tasks gave w = 0.625, which reduced coordination overhead significantly. The 3 agents recommended were appropriate for this workload.

**Potential issue:** The 8-task baseline was set arbitrarily. If most features in this project have 20-35 tasks (they do — FR-024 had 32, FR-025 had 28-37), then w is almost always in the 0.4-0.8 range. The model may be under-weighting coordination overhead for this project because "baseline" was set too low.

**Tuning action:** Review the task counts from the last 10 features. If the median is ~25, consider shifting the baseline to 15: `w = clamp((task_count - 15) / 25)`. This would make coordination overhead reductions kick in later, which is more conservative and may better match reality.

---

## Structural Improvements (Beyond Parameter Tuning)

### Make the Meta-Controller Binding

The single highest-impact change. From NR-006:

> If it says 4 parallel workers, you use 4. Override requires written justification.

**Mechanism:** Before Phase 5 execution begins, the meta-controller output must be written to `decision-support/preflight-meta-controller.json`. If the orchestrator deviates, it must create `decision-support/override-justification.md` containing:
1. The recommendation
2. What was done instead
3. Why
4. Which specific state parameter the meta-controller got wrong

`validate-phase.py` should gate Phase 5 completion on either (a) recommendations followed, or (b) override justification present. PM-018 already lists this as a prevention item.

### Add the File-Ownership Scenario to the Test Suite

The current 8 scenarios cover:
- Phase gates (phases 2, 4 force single)
- Parallel preference in phase 5
- High coupling forces single
- Instinctual vs. deliberative
- Reward guardrails
- Phase 6 allows parallel

**Missing:** A scenario with high create/modify ratio where file-ownership partitioning is optimal. This is the pattern that actually worked best in practice (PM-018).

### Close the Feedback Loop

The `meta-controller-explained.md` describes an iteration model:

```
1. Capture real feature outcomes
2. Encode as scenario fixtures
3. Run evaluator to detect drift
4. Tune parameters
5. Compare distribution changes
```

This loop has never been executed. We have three real executions with documented outcomes. The tuning actions above should be validated by:

```bash
python3 .claude/skills/feature-request-v2/scripts/evaluate_meta_controller.py --fail-on-mismatch
python3 -m unittest discover -s .claude/skills/feature-request-v2/tests -p "test_*.py"
```

After any parameter change, all 8 existing scenarios must still pass. New scenarios from PM-016/017/018 should be added to prevent regression.

---

## Summary: What Works, What Doesn't, What to Change

| Aspect | Status | Evidence |
|---|---|---|
| Scoring model accuracy | Works | Correct recommendations in all 3 executions |
| Phase gating (phases 1-4 force single) | Works | Scenario tests pass, never violated |
| Parallel agent coordination | Works | PM-016 and PM-018 both succeeded with 3 agents |
| Instinct vs. deliberative selection | Works | Correctly chose deliberative for all 3 high-novelty features |
| Enforcement of recommendations | **Broken** | PM-017 ignored all recommendations; PM-018 partially overrode without documentation |
| File-ownership partitioning | **Not modeled** | Outperformed chunk optimization in PM-018 |
| Feedback/calibration loop | **Never run** | 3 executions worth of data sitting unused |
| Instinct thresholds | **Possibly too tight** | Likely never fires for this project's risk profile |
| Workload pressure baseline | **Possibly too low** | Most features are 20-35 tasks, well above the 8-task baseline |

### Priority Order

1. **Make recommendations binding** (structural, highest impact)
2. **Add PM-016/017/018 as regression scenarios** (captures real-world behavior)
3. **Model file-ownership partitioning** (captures the strategy that actually worked best)
4. **Recalibrate workload baseline** (most features exceed the current baseline)
5. **Evaluate instinct threshold relaxation** (potential efficiency gain, needs data)
6. **Consider relative quality floor for reward shaping** (refinement, lower priority)
7. **Add soft entanglement gradient** (refinement, lower priority)
