# Unblocked in Production: A 36-Day Case Study

**Project:** Commerce Engine ("Auto Shipper AI")  
**Period:** February 21 – March 29, 2026 (36 days)  
**Codebase:** 356 Kotlin files, 25,720 LOC, 97 test files  
**Deliverables:** 25 feature requests shipped, 18 post-mortems, 7 executive reports  
**Team:** Solo developer + Claude Code (AI pair) + Unblocked

---

## Executive Summary

Over 36 days of building a modular commerce platform from scratch, Unblocked's automated PR reviewer caught **28 production-bound bugs** across 10 pull requests — **76% of all bugs documented in incident post-mortems**. Every one of those 28 bugs passed the project's test suite. Fourteen had green CI. Three were security vulnerabilities. The project's test suite, at its peak, had 65 passing tests that failed to catch a single data integrity bug that Unblocked flagged in under a minute.

This is not a story about Unblocked replacing testing. It's a story about what testing misses, and what catches it.

---

## The Setup

Commerce Engine is a greenfield Kotlin/Spring Boot platform that discovers, validates, and launches physical products autonomously. It has strict engineering constraints: sealed types for cost verification, explicit state machines, automated shutdown triggers, and a 30% net margin floor enforced structurally — not by convention.

The development workflow uses Claude Code as an AI pair programmer and a structured 6-phase feature request process. Unblocked occupies two roles in this workflow:

1. **Automated PR reviewer** (`unblocked-bot`) — reviews every pull request diff against codebase patterns, prior incidents, and organizational context
2. **Context engine** (`unblocked_context_engine`) — surfaces prior decisions, Slack discussions, rejected approaches, and related PRs during planning and implementation

---

## The Numbers

### Bug Detection by Source

| Detection Method | Bugs Caught | % of Total |
|---|---|---|
| Unblocked (PR review) | 28 | 76% |
| API documentation research | 3 | 8% |
| Integration / E2E testing | 2 | 5% |
| Manual operator review | 2 | 5% |
| CI build failure | 2 | 5% |

### Bug Severity Breakdown (Unblocked Catches)

| Severity | Count | Examples |
|---|---|---|
| Critical | 3 | Single-transaction webhook processing, deferred flush on assigned IDs, Hibernate session poisoning |
| High | 20 | Transaction isolation violations, state machine bypasses, silent data loss, circular dependencies |
| High (Security) | 3 | Parameter injection in Stripe API, XXE in XML parser, HMAC bypass on empty secret |
| Medium | 2 | Revenue filter including refunded orders, stale table name in E2E playbook |

### Detection Timeline Across the Project

| PM | Date | PR | Unblocked Catches | Test Suite Status |
|---|---|---|---|---|
| PM-002 | Mar 6 | #9 | 2 (circular SLA detection) | All green |
| PM-003 | Mar 6 | #10 | 5 (phantom refunds, injection vuln) | All green |
| PM-005 | Mar 11 | #12 | 3 (transaction poisoning) | All green |
| PM-006 | Mar 12 | #12 | 3 (false positive tests, revenue filter) | All green |
| PM-009 | Mar 15 | #15 | 2 (compliance pollution, stale reference) | All green |
| PM-011 | Mar 18 | #22 | 2 (transaction rollback, XXE) | All green |
| PM-012 | Mar 20 | #25 | 2 (Spring bean resolution, Jackson silent failure) | CI caught 1; Unblocked caught the other |
| PM-015 | Mar 21 | #33 | 7 (webhook cascade: currency, timestamps, concurrency, flush) | All green |
| PM-017 | Mar 27 | #39 | 1 (NullNode → "null" string in addresses) | 65 tests green |
| PM-018 | Mar 29 | #42 | 1 (FAILED order retry bypasses idempotency) | 42 tests green |

---

## What Tests Missed and Why

The 28 bugs Unblocked caught share a pattern: they exist in the seams between components, not inside them.

**Transaction boundary bugs (11 of 28).** Spring's `@TransactionalEventListener(AFTER_COMMIT)` handlers that write to the database require `@Transactional(propagation = REQUIRES_NEW)`. Without it, writes are silently discarded. Unit tests mock the repository layer, so the transaction context is never exercised. Integration tests that wrap the whole test in `@Transactional` mask the problem by keeping a transaction open. Unblocked caught this pattern in PM-001, PM-005, PM-006, and PM-015 — each time in a different module, with different symptoms.

**Silent data corruption (4 of 28).** Jackson's `path()` returns `MissingNode` whose `asText()` returns `""` — not null. Jackson's `get()` on a JSON `null` returns `NullNode` whose `asText()` returns the string `"null"` — not Kotlin null. Both bypass Kotlin's `?:` null-coalescing. Tests that construct domain objects directly never exercise JSON deserialization. Unblocked caught these in PM-012 and PM-017.

**Security vulnerabilities (3 of 28).** Parameter injection in form-encoded HTTP bodies (PM-003), XXE in XML parsing (PM-011), and HMAC verification bypass on empty secrets (PM-015). Security bugs require adversarial thinking about inputs that functional tests rarely model.

**State machine violations (3 of 28).** Order state transitions without guard clauses (PM-003), compliance status polluted by historical records (PM-009), and failed-order retry bypassing idempotency (PM-018). State machine bugs require reasoning about sequences of events, not individual operations.

---

## The PM-017 Moment: 65 Tests, Zero Real Coverage

PM-017 is the inflection point. The project's v2 workflow auto-generated 65 tests for FR-025 (CJ supplier order placement). All 65 passed. The PR looked clean.

Unblocked's review found one bug: `ShopifyOrderAdapter` used `?.asText()` on fields that could be JSON `null`. Jackson returns `NullNode` for these — a non-null object whose `asText()` returns the four-character string `"null"`. Shipping addresses would be saved as `"null, null, null null"`.

Post-mortem analysis revealed that ~50 of the 65 tests were "theater" — `assert(true)`, constructor round-trips, fixture existence checks. They tested that code compiles, not that it works.

Unblocked caught the bug in under a minute. The 65 tests, in aggregate, provided zero protection against it.

> *"This bug was caught by the Unblocked automated reviewer — not by any of the 65 tests the workflow generated."* — PM-017

---

## Unblocked as Organizational Memory

The second role — context engine — is harder to quantify but shows up in the project's evolution.

**Pattern propagation.** PM-001 documented the `AFTER_COMMIT` + `REQUIRES_NEW` pattern on March 4. By March 11 (PM-005), Unblocked was catching violations of that pattern in new modules by comparing diffs against the established codebase convention. The pattern didn't need to be re-discovered — it was surfaced automatically.

**Standing directive, not checklist.** The project tested two integration models:
- **v1 (FR-001 through FR-023):** "Use /unblock as needed" — a standing directive. 23 features shipped with zero post-merge bugs.
- **v2 (FR-024, FR-025 attempts 1-2):** Prescribed hydration points ("query before drafting", "query before finalizing"). Neither was enforced or used. Both attempts produced bugs.

The project reverted to the standing directive model. The simpler instruction worked because it made context retrieval ambient rather than procedural.

> *"You can automate execution, but you can't automate taste."* — NR-006

**Prior art retrieval.** During planning phases, the context engine surfaces related PRs, rejected approaches from Slack, and prior architectural decisions. This prevented re-litigating resolved decisions and reduced implementation churn — though this effect is harder to isolate quantitatively.

---

## What Unblocked Doesn't Do Well

The data also shows limits.

**Not a planner.** PM-016 and PM-017 found that Unblocked is most valuable reviewing diffs (comparing concrete code against patterns) and least valuable seeding implementation plans (speculating about what might go wrong). Planning requires judgment about priorities; review requires pattern matching against evidence. Unblocked excels at the latter.

**Doesn't replace integration tests.** Of the 28 bugs caught, all were pre-merge. But the project identified 7 external API adapters with no WireMock contract tests (PM-013) and 3 fixtures built from adapter code rather than API documentation (PM-014). Unblocked caught symptoms but didn't flag the structural gap — that took directed API documentation research.

**Doesn't pause when uncertain.** Unlike a human reviewer who might say "I'm not sure about this," Unblocked makes definitive statements. When it's right (28 times), this is efficient. When organizational context conflicts with current codebase state, it can anchor on outdated decisions. The project mitigates this by treating Unblocked output as input to human judgment, not as a gate.

---

## The Constraint Engine Effect

An unexpected outcome: Unblocked's catches directly shaped the project's engineering constraints. Of the 18 constraints in the project's CLAUDE.md (the codebase-level instruction file for AI tooling):

| Constraint | Origin PM | Bug Caught By |
|---|---|---|
| #6: Cross-module event listener transaction pattern | PM-001, PM-005 | Unblocked |
| #8: JSONB columns require @JdbcTypeCode | PM-011 | Unblocked |
| #11: XML parsers must use OWASP-hardened config | PM-011 | Unblocked |
| #12: URL-encode user-supplied values in form bodies | PM-003 | Unblocked |
| #13: @Value annotations need empty defaults | PM-012 | Unblocked |
| #14: Never use Kotlin internal constructor on Spring beans | PM-012 | Unblocked |
| #15: Jackson get() vs path() | PM-012 | Unblocked |
| #16: Assigned-ID entities must implement Persistable | PM-015 | Unblocked |
| #17: NullNode guard on Jackson get()?.asText() | PM-017 | Unblocked |

**9 of 18 engineering constraints (50%)** were established from bugs first caught by Unblocked. These constraints are now enforced structurally — via ArchUnit rules, sealed types, and AI instruction files — meaning the same class of bug cannot recur. Unblocked didn't just catch bugs; it generated the project's institutional knowledge.

---

## Cost-Benefit

### Without Unblocked (Counterfactual)

28 bugs reach production. Conservatively:
- 11 transaction bugs cause silent data loss in financial records (orders, reserves, pricing)
- 3 security vulnerabilities are exploitable (parameter injection, XXE, HMAC bypass)
- 4 data corruption bugs produce garbage in customer-facing fields
- 3 state machine violations allow invalid order/SKU transitions
- Each production bug requires: detection time + diagnosis + hotfix + post-mortem + regression test
- Conservative estimate: 2-4 hours per production bug = 56-112 hours of reactive work

### With Unblocked (Actual)

28 bugs caught pre-merge. Each fix took 15-45 minutes (the bug was already identified and located). Total reactive time: ~14-21 hours. Net savings: ~42-91 hours over 36 days.

But the real value isn't time saved — it's the 11 transaction bugs that would have silently corrupted financial data in a system whose entire mandate is "durable net profit." Silent data loss in revenue tracking, reserve calculations, and margin dashboards would have undermined the system's core business function.

---

## Summary

| Metric | Value |
|---|---|
| Development period | 36 days |
| Features shipped | 25 |
| Bugs caught by Unblocked | 28 |
| Bugs caught by all other methods combined | 9 |
| Security vulnerabilities caught pre-merge | 3 |
| Engineering constraints generated from Unblocked catches | 9 of 18 (50%) |
| Post-merge bugs on features using Unblocked review | 0 |
| Test suites that were green when Unblocked found bugs | 10 of 10 PRs |

Unblocked works because tests verify what you thought to test. Unblocked verifies what you didn't think to test — by comparing your diff against everything the codebase already knows.

---

*Data sourced from 18 incident post-mortems (PM-001 through PM-018), 7 executive reports (NR-001 through NR-007), and 25 feature request records (FR-001 through FR-025) in the Commerce Engine repository.*
