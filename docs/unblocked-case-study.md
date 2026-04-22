# How a Two-Person Team Shipped 25 Features in 36 Days With Zero Production Bugs

**A case study on Unblocked as the memory layer between AI-assisted development and production-grade code.**

---

## The Problem With Being Fast

AI coding assistants are fast. Genuinely, startlingly fast. They generate code, write tests, and produce green builds at a pace that makes you feel productive. And feeling productive is dangerous when you're building something that touches real money.

We're a two-person team building a commerce platform. I'm the technical lead; my partner Nathan handles business alignment, market validation, and operational strategy. Every feature I ship touches financial data. When a bug silently corrupts a transaction record, nobody pages you at 3am. You just discover, weeks later, that your dashboards have been lying to you. That's a worse outcome.

I use Claude Code as my AI pair programmer. It writes excellent code. What it cannot do, what no AI coding tool can do alone, is remember that three weeks ago someone discussed in Slack why a particular approach was rejected. Or that a prior PR established a transaction pattern that every new module needs to follow. Or that the last time someone touched this file, they introduced a bug on line 69 that looks exactly like what's about to be committed on line 72.

AI sessions are stateless. Every session starts fresh, as if the project has no history. I needed something that could give each session the institutional memory it fundamentally lacks. That turned out to be Unblocked.

---

## Two Roles, One System

I use Unblocked in two distinct ways, and the interaction between them is where the real value lives.

### 1. Context Hydration (Before Writing Code)

Before every feature, I query Unblocked's context engine for organizational knowledge: prior PRs, Slack discussions, design docs, rejected approaches, and in-progress work. This happens at the start of discovery, before writing specs, and before finalizing implementation plans.

This is not optional. I have a post-mortem documenting what happens when you skip it. More on that shortly.

### 2. Automated PR Review (After Writing Code)

Every pull request gets reviewed by Unblocked's bot. It compares the diff against established codebase patterns, prior incidents, and organizational conventions. It reasons about transaction boundaries, state machine invariants, and security implications at a level that goes well beyond linting.

The two roles compound. Context hydration prevents known mistakes. PR review catches novel ones. Together, they create a feedback loop: bugs caught in review become institutional knowledge that context hydration surfaces for the next feature. The system gets smarter with every post-mortem, and I write a lot of post-mortems.

---

## The Vibe Coding Incident

On March 14, I skipped the workflow. I had a validation module to build and it felt straightforward enough. I let Claude Code handle it in a single unstructured session. No context hydration. No structured phases. Just "build this."

30 minutes later: 22 files, 931 lines of code, 25 passing tests. Green build.

The feature was entirely non-functional.

The module was supposed to automatically validate new products against compliance rules when they reached a certain lifecycle stage. The event that triggers that validation was never published. No code anywhere emitted it. The 25 tests verified internal logic of handlers that would never be called. I had a green build and a feature that could not possibly work. The tests were testing themselves.

Post-mortem analysis identified **3 critical issues, 4 high-severity gaps, and 7 medium issues**, all invisible to the test suite.

What would context hydration have caught? An existing test in an adjacent module demonstrated exactly how to wire up cross-module event listeners. A prior PR established the transaction pattern this module needed. Both were sitting in the codebase and in organizational history. Neither was consulted because I didn't ask.

The entire implementation had to be thrown away and redone. I deserved that.

### The Redo

The structured redo, with context hydration and the full workflow, took 10 minutes of implementation time plus 20 minutes fixing bugs caught by its own review gates. It shipped with zero critical issues. The validation flow worked on the first E2E test.

| | Vibe Coding | With Unblocked |
|---|---|---|
| Time | ~30 min | ~30 min (including review fixes) |
| Files | 22 files, 931 lines | 16 files + 5 tests, ~800 lines |
| Tests | 25 (green but shallow) | 29 (behavioral) |
| Bugs shipped to main | 3 critical, 4 high, 7 medium | 0 |
| Primary feature functional | No | Yes |
| Context engine consulted | Never | Yes, prior patterns surfaced |

Same time. Same developer. Same AI assistant. The only variable was whether organizational context was in the room.

> *"A green build is not a shipped feature."* -- PM-008 post-mortem

---

## Context Hydration at Scale: The Feature Request Workflow

After that incident, I embedded Unblocked into my development workflow as a structural requirement. Not as a checklist item that gets skipped under pressure, but as a standing directive available at every phase.

My workflow has multiple structured phases, from discovery through implementation and review. At each phase, the AI agent can query Unblocked's context engine for relevant organizational knowledge. The instruction is deliberately simple:

> *"Use /unblock as needed. Query for related PRs, prior attempts, Slack discussions, rejected approaches, and existing conventions relevant to this work."*

I tested two integration models:

**Prescribed hydration points** ("query before drafting, query before finalizing"): theoretically rigorous, but neither the AI nor the workflow enforced them. Two features shipped with bugs.

**Standing directive** ("use as needed at every phase"): simple, ambient, always available. 23 features shipped with zero post-merge bugs.

The simpler instruction won. I have a theory about why: ceremony creates something to skip; a reflex just happens. When you tell a system "do this at step 3 and step 7," it optimizes around those steps. When you tell it "this is always available," it reaches for it when it actually needs it.

### What Context Hydration Catches

Each phase has specific knowledge targets:

- **Discovery:** Has this been attempted before? Are there rejected approaches? Is someone else working on this?
- **Specification:** Do business requirements align with prior team decisions? Are there stakeholder constraints not captured in code?
- **Planning:** What patterns does this codebase use for similar work? What architectural decisions would constrain the design?
- **Test Specification:** What test conventions exist? What fixture patterns? What assertion styles?

This matters because every AI session starts with amnesia. Without context hydration, every feature is built as if the project has no history, no prior decisions, no rejected approaches, no established patterns. You can have the most capable AI in the world, and if it doesn't know what the team decided last Tuesday, it will confidently propose the thing the team rejected last Tuesday.

---

## The 65-Test Illusion

On March 27, my automated workflow generated 65 tests for a feature integrating with an external API. All 65 passed. The PR looked clean. I felt good about it. That feeling was wrong.

Unblocked's automated review found one bug: when an optional field was absent in the source data, the system would send the literal string `"null"` to the external API instead of omitting the field. Downstream records would be saved with the word "null" as actual data. In a financial system, that kind of silent corruption compounds.

Post-mortem analysis revealed that ~50 of the 65 tests were theater: `assert(true)`, constructor round-trips, fixture existence checks. They verified that code compiles, not that it works. Only ~23% of the tests exercised real system behavior. I had mistaken test count for test coverage, which is a humbling thing to write down.

Unblocked caught the bug by comparing one section of the adapter against a pattern established earlier in the same file. It recognized that one block used a null-safe guard and the other didn't. No number of tests would catch an inconsistency like that, because tests verify behavior you specify. Unblocked verifies consistency you didn't think to specify. That's a fundamentally different kind of coverage.

> *"Automated checks cover the scenarios you think of. External review covers the ones you don't. Both are essential."* -- NR-007

---

## 28 Bugs, Zero Post-Merge Incidents

Over 36 days and 25 shipped features, Unblocked's automated PR reviewer caught **28 bugs** across 10 pull requests. Every one of those 28 bugs had a green test suite at the time of detection. Let that sit for a moment: 28 times, the tests said "all clear" and Unblocked said "look again."

**By category:**

| Bug Class | Count | Why Tests Miss Them |
|---|---|---|
| Transaction boundary violations | 11 | Unit tests mock persistence; integration tests mask the real transaction lifecycle |
| Silent data corruption | 4 | Tests construct objects directly; serialization edge cases never exercised |
| Security vulnerabilities | 3 | Functional tests don't model adversarial inputs |
| State machine violations | 3 | Scenario tests verify specified paths; Unblocked reasons about all possible paths |
| Logic errors | 5 | Double-counting, circular dependencies, stale references. Correct in isolation, wrong in context. |
| Infrastructure | 2 | Stale references, filter mismatches between related queries |

Zero post-merge bugs on features that went through Unblocked review. The only post-merge bug in the project's history came from the vibe coding session that skipped Unblocked entirely. I've stopped treating that as coincidence.

---

## The Constraint Engine Effect

This was the outcome I didn't plan for and the one that matters most in retrospect.

Each bug caught in PR review led to a post-mortem. Each post-mortem established an engineering constraint, a rule encoded in the project's AI instruction file so the same class of bug could never recur. Of 18 engineering constraints governing this codebase, **9 originated from bugs first caught by Unblocked.** They span the full surface area:

| Bug Class | Constraints Generated |
|---|---|
| Transaction isolation | 2 rules governing cross-module event handling and write boundaries |
| Data serialization | 3 rules preventing silent null coercion, type mismatches, and encoding errors |
| Security | 2 rules enforcing input sanitization and hardened XML parsing |
| Framework-specific traps | 2 rules preventing constructor resolution failures and configuration crashes |

These constraints are enforced structurally via architectural test rules, sealed types, and AI instruction files. They prevent recurrence permanently. The bugs Unblocked caught on day 10 became the rules that protected day 30. Half of my project's institutional knowledge exists because Unblocked found something worth writing down.

---

## What Unblocked Doesn't Do

I have 18 post-mortems and a philosophy about intellectual honesty, so here are the limitations I've observed. Three of them.

**It reviews better than it plans.** Unblocked excels at comparing diffs against established patterns (concrete, evidence-based). It's weaker at predicting what might go wrong during planning (speculative, requires judgment). During one feature, it correctly surfaced two known bugs from a prior attempt, and both were prevented. But this created tunnel vision: the focus narrowed to preventing known bugs while missing a novel one in the same file. You can't solve all future problems by listing all past ones.

**It doesn't replace integration tests.** Several of our external service integrations had no contract tests. Unblocked caught symptoms in individual PRs but didn't flag the structural gap. That required directed research against API documentation. Unblocked sees trees with remarkable clarity; the forest is still your job.

**It doesn't pause when uncertain.** A human reviewer might say "I'm not sure about this." Unblocked makes definitive statements. When it's right, this is efficient. When organizational context is stale, it can anchor on outdated decisions. I treat Unblocked's output as input to my judgment, not as a gate. The tool is a brilliant colleague who is always confident. You learn to calibrate.

---

## What This Adds Up To

Our team shipped 25 features with financial impact in 36 days with zero production bugs. Nathan validates what we should build; I build it; Unblocked makes sure what I build is consistent with everything we've already learned.

I want to be careful about that claim because I'm the kind of person who writes 18 post-mortems in 36 days, which means I'm also the kind of person who finds bugs everywhere. So let me be precise: zero bugs reached production on features that went through Unblocked review. The test suite alone would not have gotten me there, because 28 bugs passed it. The AI coding assistant alone would not have gotten me there, because I proved it on March 14 when I skipped the workflow and built a feature that could not possibly function.

What got me there was the combination: an AI that writes code fast, a business partner who keeps me honest about what matters, and Unblocked ensuring that code is consistent with everything the project already knows.

Context hydration before implementation prevents known classes of mistakes. Automated PR review after implementation catches novel ones. The feedback loop between them (bugs become constraints become institutional knowledge become context for the next feature) is what turns a small team's output into something that resembles a much larger engineering org's. We don't have a large team. We have clarity about roles, a structured workflow, and a memory layer. That has been enough.

There's a deeper thing I've come to believe over these 36 days: speed amplifies whatever your process produces. If your process produces good code, AI makes you faster at shipping good code. If your process has gaps, AI makes you faster at shipping bugs. Most of the conversation around AI-assisted development focuses on the speed. Almost nobody talks about the memory. Unblocked is the memory, and it turns out that's the part that actually matters.

---

*All data sourced from 18 incident post-mortems, 7 executive reports, and 25 feature request records. Every number traces to a specific, documented incident in the project repository.*
