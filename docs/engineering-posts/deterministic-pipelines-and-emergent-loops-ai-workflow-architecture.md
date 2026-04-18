# Deterministic Pipelines and Emergent Loops: The Dual Architecture of an AI Engineering Workflow

*Auto Shipper AI Engineering | 2026-04-18*

**TL;DR** — Most AI-assisted engineering workflows treat the model as a smart assistant that reads context on-demand and forgets everything after the session. We built something different: a compound system where each postmortem adds a constraint, each constraint feeds a validator, each validator gates the next feature, and a persistent knowledge graph accumulates across every session. The architecture distinguishes two fundamentally different types of AI work — deterministic pipelines (where you know the steps) and emergent loops (where you don't) — and routes each through different tooling. Here's what we built, why the distinction matters, and what it took to learn that auto-advance was the enemy.

---

## The problem with stateless AI engineering

The default AI-assisted engineering workflow looks like this:

```
Session starts → dump context → ask question → get answer → session ends → repeat
```

Every session is an island. The model is smart but amnesiac. You rebuild context every time. Worse, the model's mistakes in session N have no bearing on session N+1 — unless you manually carry forward the lesson. Most teams don't. The bugs repeat.

After 20 postmortems across 27 feature requests in this project, we had a choice: keep fighting the same categories of bugs, or build infrastructure that makes them structurally impossible. We chose infrastructure.

The result is a layered architecture with a clean conceptual split at its core.

---

## The core distinction: deterministic vs emergent work

Not all AI engineering work is the same. The biggest mistake you can make is treating exploratory research with the same tooling as structured implementation.

**Deterministic work** is work where the steps are known in advance. Implementing a feature has a discoverable structure: understand the codebase, write a spec, plan the implementation, write tests first, implement, review. Each phase has defined inputs, defined outputs, and defined permissions. The quality of the output is measurable against the spec. You can write a validator for it.

**Emergent work** is work where the answer isn't known until you explore. "What are our top failure points?" can't be answered by following steps — it requires traversing a graph of the codebase, synthesizing institutional history, and surfacing connections that weren't explicitly searched for. The output is a discovery, not a deliverable. You can't write a validator for it because you don't know what success looks like until you see it.

We built different infrastructure for each.

---

## Layer 1: Deterministic pipelines

### The six-phase feature workflow

Every feature in this project goes through a fixed sequence: Discovery → Specification → Planning → Test Specification → Implementation → Review. This isn't a guideline — it's enforced by a state machine.

```
feature-requests/FR-{NNN}-{name}/
  spec.md                    # Phase 2 output — requirements only
  implementation-plan.md     # Phase 3 output — task breakdown
  test-spec.md               # Phase 4 output — tests before code
  summary.md                 # Phase 5 output — what shipped
```

The workflow validator rejects file writes outside permitted paths for the current phase. An agent in Phase 2 (Specification) cannot write `implementation-plan.md`. An agent in Phase 3 (Planning) cannot write code. The filesystem itself enforces the phase boundaries.

```bash
# Before any action, the agent validates permission:
python3 .claude/skills/feature-request-v2/scripts/validate-phase.py \
  --phase 3 --action write --path "modules/catalog/src/..."
# → DENIED: Phase 3 permits writes only to implementation-plan.md
```

This sounds rigid. It is, deliberately. The alternative — an agent that "figures out the right phase" autonomously — is what failed. PM-017 documents it precisely: when we let the workflow auto-advance between phases, a feature shipped 65 tests that were theater (constructor round-trips, fixture content checks, `assert(true)`), a NullNode data integrity bug that slipped through all of them, and an architecture the meta-controller had explicitly recommended against. The auto-advance removed every checkpoint where a human would have caught the gap.

**The finding:** Auto-advance is the enemy of quality in deterministic pipelines. Human gates at phase boundaries — specifically between Test Specification and Implementation, and before Review — are where quality actually gets enforced. The six-phase workflow with mandatory human approval between phases produced 23 features with zero postmortems. The same workflow with auto-advance produced immediate failures.

### The postmortem → constraint → validator loop

The real compound effect comes from this loop:

```
Bug ships → Postmortem documents root cause
         → CLAUDE.md gains a structural constraint
         → ArchUnit test enforces it at compile time
         → Next agent session starts with the constraint already internalized
```

We've run 20 postmortems. Each one produced a constraint. Not "be careful about X" — a structural rule that makes the bug class impossible:

- PM-005 documented that `@TransactionalEventListener(AFTER_COMMIT)` handlers silently discard writes without `@Transactional(REQUIRES_NEW)`. **CLAUDE.md Constraint 6** now specifies the full annotation stack. Every new listener is written with it from the start.
- PM-011 documented that a scheduled job orchestrator marked `@Transactional` caused failure status to roll back silently. **Constraint 9** prohibits `@Transactional` on orchestrators. The next DemandScanJob implementation had no transaction boundary issue.
- PM-008 and PM-010 documented that single-session implementation without the feature workflow produced green builds with broken features. **Constraint 18** prohibits implementing features outside the 6-phase workflow.

The constraints compound. An agent starting a new session reads CLAUDE.md and gets 20 bug classes that the team has already paid for. It doesn't need to rediscover them.

---

## Layer 2: Emergent loops

Deterministic pipelines are great for known work. But most strategic questions — "what are our top failure points?", "what breaks first at 10x?", "what signals are we missing?" — don't have known steps. For those, we use two complementary tools.

### Graphify: persistent knowledge graph

The codebase is a graph. Files reference each other, classes depend on other classes, domain events flow between modules, tests verify behaviors. Treating it as a flat list of files that you re-read on every session is wasteful and lossy.

Graphify extracts the graph once — AST relationships from code files, semantic relationships from documentation — and persists it. Our current graph is 2,927 nodes and 2,772 edges across all modules and documentation. It survives between sessions.

The difference this makes in practice: a query against the graph traverses the actual connection topology of the system, not just keyword matches. When we asked "what breaks first at 10x?", the graph returned `DemandScanJob` (nightly, single-threaded, O(n²) deduplication), `MarginSweepJob` (full-table load, every 6h), and `ShipmentTracker` (unbounded query, carrier API calls per active order) as the top-3 bottlenecks — because those nodes had the highest connectivity to both scheduling infrastructure and data volume paths. A keyword search for "bottleneck" would return nothing.

The graph is honest about what it knows. Every edge is tagged as `EXTRACTED` (explicit in source), `INFERRED` (reasonable inference), or `AMBIGUOUS` (uncertain). When the graph surfaces a connection, you know whether it was explicit or inferred. This matters when you're making architectural decisions based on it.

### Unblocked: institutional memory

The graph tells you *what exists*. Unblocked tells you *why it exists that way* — the prior decisions, failed approaches, and context that's nowhere in the code.

For example: the `CostEnvelope.Verified` type uses an `internal constructor`, which means only the `CostGateService` in the `:catalog` module can construct it. Why? A cross-module boundary constraint by design — other modules shouldn't be able to construct a verified cost envelope without going through the gate. If you see this in the code without context, it looks like an arbitrary access modifier. With Unblocked's institutional context, you see it's an intentional type-system enforcement of the cost gate rule.

This matters for AI agents because the model's default behavior on unfamiliar patterns is to "fix" them to match familiar patterns. Without institutional context, an agent refactoring the cost gate might make `Verified` a `data class` with a public constructor and break the entire cost gate invariant. With Unblocked, it understands the pattern was deliberate.

### How the two layers interact

The emergent loop for today's strategic audit looked like this:

```
Question: "What breaks first at 10x?"
   ↓
graphify query → surfaces DemandScanJob, MarginSweepJob, ShipmentTracker
   ↓
unblocked research → explains WHY DemandScanJob is single-threaded
                     (accepted constraint for Phase 1, Kafka deferred to Phase 2)
   ↓
code read → confirms the actual job implementations match the graph's representation
   ↓
answer: here are the three bottlenecks, here's the root cause, here's what to fix before Phase 2
```

Neither tool alone would produce the full answer. The graph gives topology; institutional memory gives intent; code read gives confirmation. All three layers are necessary.

---

## The compound effect

The architecture is designed to compound. Each layer builds on the previous one:

```
Postmortems
   → CLAUDE.md constraints (20 constraints from 20 PMs)
      → validate-phase.py (constraints enforced at action boundaries)
         → Feature workflow (6-phase human-gated pipeline)
            → Nathan reports (close the loop to strategic layer)
               → Graphify (persists the accumulated codebase knowledge)
                  → Unblocked (persists the institutional history)
```

The Nathan report is the overlooked piece. After every significant session, a structured memo goes to the project's business stakeholder in plain language — what changed, what's now possible, what decisions are needed. This isn't just communication hygiene. It creates a readable record of *why* decisions were made, which feeds back into Unblocked's institutional context for the next session. The loop closes.

In practice: a postmortem from PM-015 documented that Shopify's 5-second webhook timeout was exceeded because a processing listener ran synchronously in the commit thread. The fix (CLAUDE.md Constraint 6, extended in CLAUDE.md Constraint 6's `@Async` requirement) is now a structural rule. Unblocked surfaces it when you ask about webhook processing. The next engineer implementing a webhook handler gets the constraint before they make the mistake. The institutional memory of a production incident becomes a compile-time guard.

---

## What this looks like in a session

Today's session: Nathan asked 7 strategic questions about the system — signals, resilience, platform exposure, defensibility, guardrails, scalability, strategic direction.

We ran all 7 queries against the live graph in parallel. Simultaneously, we ran a single `research_task` against Unblocked for institutional context. Both completed in parallel.

The graph gave us: which nodes were most relevant to each question, what their connections were, which files they lived in, what tests covered them. Unblocked gave us: the prior decisions behind those nodes, what had been tried and rejected, what the current phase constraints were.

Total time to answer 7 deep architectural questions with citations to specific code and documented institutional decisions: one round of tool calls. No manual file reading. No re-explaining the codebase architecture. The accumulated knowledge from 27 feature requests, 20 postmortems, and hundreds of prior sessions was available immediately.

That's the compound effect in practice.

---

## The honest accounting

This didn't come for free or all at once.

**The first 6 features were written without postmortems.** The patterns were wrong, the constraints weren't there, and the bugs taught us what to formalize. You have to make the mistakes to know what to enforce.

**The v2 workflow failed before it worked.** We thought automating the human gates would go faster. PM-017 documented the results. We reverted to human gates. The manual approval checkpoint between Test Specification and Implementation is not bureaucracy — it's the only moment where a human actually verifies that the tests test something real.

**Graphify only compounds if you keep it updated.** A graph built six months ago and never updated is worse than no graph — it gives you false confidence about stale structure. We keep it current with `--update` runs when significant structure changes.

**The tools are only as good as the questions.** Emergent loop tools surface connections, but you still have to ask the right questions. "What breaks first at 10x?" is a good question. "How's the codebase?" is not.

---

## The principle underneath all of it

There's a tension at the heart of AI engineering: models are generalists trained to be helpful, which means they naturally autocomplete toward familiar patterns. Left unconstrained, an agent implements what it's seen before, not what the project needs.

The deterministic pipeline fights this by constraining the action space — the agent can only do what the current phase permits, and each phase deliverable is validated against its spec. The emergent loop fights it by providing richer context — the agent knows not just what the code says but why it was written that way.

Together they address the two root causes of most AI engineering failures: **insufficient structure** (agent does the wrong thing because nothing prevented it) and **insufficient context** (agent does the right thing in the wrong system because it didn't understand the constraints).

You can't write a constraint for everything you haven't discovered yet. But once you've discovered it — once a bug has shipped, been postmortemed, and been named — you can make it structurally impossible. The system gets more reliable with each failure it survives. That's what compound engineering means.

---

## Try it yourself

**Build the knowledge graph once, query it repeatedly:**
```bash
/graphify .                          # full pipeline on current directory
/graphify query "what breaks at 10x" # BFS traversal from matching nodes
```

**Wire postmortems to constraints:**
After any significant bug, write a postmortem with a root cause section. Convert the root cause to a CLAUDE.md constraint in imperative form ("Never use X without Y because Z"). If it's checkable at compile time, add an ArchUnit rule.

**Enforce phase boundaries in your workflow:**
Before every agent action, validate whether the action is permitted in the current phase. The validation script doesn't have to be complex — even a simple YAML state machine that lists permitted file paths per phase will catch most violations.

**Keep the human gate before implementation:**
Whatever your workflow looks like, put a mandatory human review between test specification and code writing. This is where bad test plans surface. Automating past it is how you get 65 tests that test nothing.

---

*Tags: ai-engineering, context-engineering, workflows, knowledge-graphs, spring-boot, compound-systems*
