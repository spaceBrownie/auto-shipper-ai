# How One Developer Shipped 25 Features in 36 Days — Without Shipping a Single Bug

**A case study on Unblocked as the missing layer between AI-assisted development and production-grade code.**

---

## The Problem Nobody Talks About

AI coding assistants are fast. They generate code, write tests, and produce green builds at a pace no human can match. But speed without institutional memory is just velocity toward bugs.

I'm a solo developer building a commerce platform that autonomously launches physical products. Every feature touches real money — pricing engines, reserve calculations, supplier orders, payment processing. A silent data corruption bug doesn't just break a test; it breaks revenue tracking.

I use Claude Code as my AI pair programmer. It's exceptional at writing code. What it can't do — what no AI coding tool can do alone — is remember that three weeks ago, a teammate discussed in Slack why a particular approach was rejected. Or that a prior PR established a transaction pattern that every new module needs to follow. Or that the last time someone touched this file, they introduced a bug on line 69 that looked exactly like what's about to be committed on line 72.

That's where Unblocked changed everything.

---

## Two Roles, One System

I use Unblocked in two distinct ways, and the interaction between them is what makes it transformative.

### 1. Context Hydration (Before Writing Code)

Before every feature, I query Unblocked's context engine for organizational knowledge: prior PRs, Slack discussions, design docs, rejected approaches, and in-progress work. This happens at the start of discovery, before writing specs, and before finalizing implementation plans.

This isn't optional. I learned the hard way what happens without it.

### 2. Automated PR Review (After Writing Code)

Every pull request gets reviewed by Unblocked's bot. It compares the diff against established codebase patterns, prior incidents, and organizational conventions. It doesn't just lint — it reasons about transaction boundaries, state machine invariants, and security implications.

The two roles compound. Context hydration prevents known mistakes. PR review catches novel ones. Together, they create a feedback loop: bugs caught in review become institutional knowledge that context hydration surfaces for the next feature.

---

## The Vibe Coding Incident

On March 14, I skipped the workflow. I had a compliance module to build — IP checks, regulatory guards, processor rule validation. Straightforward enough. I let Claude Code handle it in a single unstructured session. No context hydration. No structured phases. Just "build this."

30 minutes later: 22 files, 931 lines of code, 25 passing tests. Green build. Done.

Except it wasn't done. The feature was entirely non-functional.

The system was supposed to automatically check new products against compliance rules when they reached a certain lifecycle stage. The event that triggers that check? Never published. No code anywhere emitted it. The 25 tests verified internal logic of handlers that would never be called. A green build with a broken feature.

Post-mortem analysis identified **3 critical issues, 4 high-severity gaps, and 7 medium issues** — all invisible to the test suite.

What would context hydration have caught? The existing `OrderEventListenerTest` in an adjacent module demonstrated exactly how to test cross-module event listeners. A prior PR established the transaction pattern the compliance module needed. Both were sitting in the codebase and in organizational history. Neither was consulted.

The entire implementation had to be thrown away and redone.

### The Redo

The structured redo — with context hydration and the full workflow — took 10 minutes of implementation time plus 20 minutes fixing bugs caught by its own review gates. It shipped with zero critical issues. The compliance auto-check flow actually worked.

| | Vibe Coding | With Unblocked |
|---|---|---|
| Time | ~30 min | ~30 min (including review fixes) |
| Files | 22 files, 931 lines | 16 files + 5 tests, ~800 lines |
| Tests | 25 (green but shallow) | 29 (behavioral) |
| Bugs shipped to main | 3 critical, 4 high, 7 medium | 0 |
| Primary feature functional | No | Yes |
| Context engine consulted | Never | Yes — prior patterns surfaced |

> *"A green build is not a shipped feature."* — PM-008 post-mortem

---

## Context Hydration at Scale: The Feature Request Workflow

After that incident, I embedded Unblocked into my development workflow as a structural requirement — not a checklist item, but a standing directive available at every phase.

My workflow has 6 phases: Discovery, Specification, Planning, Test Specification, Implementation, and Review. At each phase, the AI agent can query Unblocked's context engine for relevant organizational knowledge. The instruction is simple:

> *"Use /unblock as needed — query for related PRs, prior attempts, Slack discussions, rejected approaches, and existing conventions relevant to this work."*

I tested two integration models:

**Prescribed hydration points** ("query before drafting, query before finalizing") — theoretically rigorous, but neither the AI nor the workflow enforced them. Two features shipped with bugs.

**Standing directive** ("use as needed at every phase") — simple, ambient, always available. 23 features shipped with zero post-merge bugs.

The simpler instruction won because it made context retrieval a reflex, not a ceremony.

### What Context Hydration Catches

Each phase has specific knowledge targets:

- **Discovery:** Has this been attempted before? Are there rejected approaches? Is someone else working on this?
- **Specification:** Do business requirements align with prior team decisions? Are there stakeholder constraints not captured in code?
- **Planning:** What patterns does this codebase use for similar work? What architectural decisions would constrain the design?
- **Test Specification:** What test conventions exist? What fixture patterns? What assertion styles?

This matters because AI assistants are stateless by design. Each session starts fresh. Without context hydration, every feature is built as if the project has no history — no prior decisions, no rejected approaches, no established patterns. Context hydration gives the AI session the institutional memory it fundamentally lacks.

---

## The 65-Test Illusion

The most dramatic proof came on March 27. My automated workflow generated 65 tests for a supplier order placement feature. All 65 passed. The PR looked clean.

Unblocked's automated review found one bug: when a customer has no phone number, the system would send the literal string `"null"` to the supplier API instead of omitting the field. Shipping addresses would be saved as `"null, null, null null"`.

Post-mortem analysis revealed that ~50 of the 65 tests were theater — `assert(true)`, constructor round-trips, fixture existence checks. They verified that code compiles, not that it works. Only ~23% of the tests exercised real system behavior.

Unblocked caught the bug by comparing lines 69-81 of the adapter against the pattern established on lines 30-31 of the same file. It recognized that one block used a null-safe guard and the other didn't. No test — no matter how many — would catch an inconsistency like that, because tests verify behavior you specify. Unblocked verifies consistency you didn't think to specify.

> *"Automated checks cover the scenarios you think of. External review covers the ones you don't. Both are essential."* — NR-007

---

## 28 Bugs, Zero Post-Merge Incidents

Over 36 days and 25 shipped features, Unblocked's automated PR reviewer caught **28 bugs** across 10 pull requests. Every one of those 28 bugs had a green test suite at the time of detection.

**By category:**

| Bug Class | Count | What Tests Miss |
|---|---|---|
| Transaction boundary violations | 11 | Unit tests mock the repository; integration tests wrap in @Transactional — both mask the real behavior |
| Silent data corruption | 4 | Tests construct objects directly; JSON deserialization edge cases never exercised |
| Security vulnerabilities | 3 | Functional tests don't model adversarial inputs |
| State machine violations | 3 | Scenario tests verify specified paths; Unblocked reasons about all possible paths |
| Logic errors | 5 | Double-counting, circular dependencies, stale references — correct in isolation, wrong in context |
| Infrastructure | 2 | Stale table names, filter mismatches between related queries |

**By outcome:** Zero post-merge bugs on features that went through Unblocked review. The only post-merge bug in the project's history came from the vibe coding session that skipped Unblocked entirely.

---

## The Constraint Engine Effect

An unexpected compounding benefit: Unblocked's catches generated the project's institutional knowledge.

Each bug caught in PR review led to a post-mortem. Each post-mortem established an engineering constraint — a rule encoded in the project's AI instruction file so the same class of bug could never recur. Of 18 engineering constraints governing this codebase, **9 originated from bugs first caught by Unblocked:**

| Constraint | What It Prevents |
|---|---|
| Cross-module event listener transaction pattern | Silent write discard in post-commit handlers |
| JSONB columns require @JdbcTypeCode annotation | PostgreSQL type mismatch on insert |
| XML parsers must use OWASP-hardened config | XXE injection via external entities |
| URL-encode user-supplied values in form bodies | Parameter injection in HTTP requests |
| @Value annotations need empty defaults | Bean instantiation crash on disabled profiles |
| Never use Kotlin internal constructor on Spring beans | Synthetic parameter breaks dependency injection |
| Jackson get() vs path() selection | Silent empty string instead of null |
| Assigned-ID entities must implement Persistable | Deferred flush breaks deduplication |
| NullNode guard on Jackson get()?.asText() | Literal "null" string instead of null |

These constraints are enforced structurally — via architectural test rules, sealed types, and AI instruction files. They prevent recurrence permanently. Unblocked didn't just catch bugs; it generated the rules that make the next 25 features safer than the first 25.

---

## What Unblocked Doesn't Do

Honesty matters more than marketing. Three limitations I've observed:

**It's a better reviewer than planner.** Unblocked excels at comparing diffs against established patterns (concrete, evidence-based). It's weaker at predicting what might go wrong during planning (speculative, requires judgment). During one feature, it correctly surfaced two known bugs from a prior attempt — both were prevented. But this created tunnel vision: the team focused on preventing known bugs while missing a novel one in the same file.

> *"Unblocked is most valuable as a reviewer (checking diffs against patterns), less valuable as a planner (seeding specific bugs to watch for). You can't solve all future problems by listing all past ones."* — NR-006

**It doesn't replace integration tests.** Seven external API adapters had no contract tests. Unblocked caught symptoms in individual PRs but didn't flag the structural gap — that required directed research against API documentation.

**It doesn't pause when uncertain.** Unlike a human reviewer who might say "I'm not sure about this," Unblocked makes definitive statements. When it's right, this is efficient. When organizational context is stale, it can anchor on outdated decisions. The mitigation: treat Unblocked's output as input to human judgment, not as a gate.

---

## The Real Value Proposition

I'm a solo developer who shipped 25 features with financial impact — pricing engines, margin calculations, supplier order placement, payment processing — in 36 days with zero production bugs.

That sentence shouldn't be possible. Solo developers on financially critical systems don't have zero-defect records. The test suite alone wouldn't have gotten me there — 28 bugs passed it. The AI coding assistant alone wouldn't have gotten me there — the vibe coding incident proved that.

What got me there was the combination: an AI that writes code fast, and Unblocked ensuring that code is consistent with everything the project already knows.

Context hydration before implementation prevents known classes of mistakes. Automated PR review after implementation catches novel ones. The feedback loop between them — bugs become constraints become institutional knowledge become context for the next feature — is what turns a solo developer's output into something that looks like a well-coordinated team's.

The uncomfortable truth about AI-assisted development is that speed amplifies whatever your process produces. If your process produces good code, AI makes you faster at shipping good code. If your process has gaps, AI makes you faster at shipping bugs. Unblocked is the layer that closes the gaps — not by slowing you down, but by giving every session the memory of every session that came before it.

---

*All data sourced from 18 incident post-mortems, 7 executive reports, and 25 feature request records. No claims are extrapolated — every number traces to a specific, documented incident in the project repository.*
