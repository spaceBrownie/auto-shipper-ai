# Solving Problems That Solve Problems

*A reflection on building production software with AI-assisted development, where every bug is an opportunity to make the system permanently smarter.*

---

## The Session That Proved the Model

On March 21, 2026, we built a complete Shopify webhook integration — 42 files, 3,900 lines of code, security verification, concurrency handling, multi-channel extensibility — in a single development session. Eight bugs were found. All eight were fixed before any code reached production.

That's not the interesting part.

The interesting part is that one of the fixes introduced a new bug. And the fix for *that* bug introduced *another* new bug. A three-layer cascade, each layer revealing a deeper issue in the system. The final fix didn't just solve the immediate problem — it touched 23 database entities across every module in the system and added an automated architecture test that makes the entire *class* of bug structurally impossible to reintroduce.

The system ended the session safer than it started. Not just "this bug was fixed" safer. "This category of bug can never happen again" safer.

This is what it looks like when you stop treating problems as obstacles and start treating them as raw material.

## The Philosophy: Problems Are Just Other Problems to Architect and Solve

Most development workflows are organized around avoiding problems. Write careful specs. Review code thoroughly. Test extensively. The implicit assumption is that bugs are failures — lapses in diligence that should be minimized.

We take a different stance: **bugs are information.** They reveal gaps in the system's understanding of itself. The goal isn't to never have bugs — it's to build a system that converts each bug into a permanent structural improvement.

This changes how you think about speed. Traditional caution says "slow down to avoid mistakes." Our model says "move fast, but build layers that catch what you miss, and make sure every catch strengthens the next layer."

## The Validation Layers

What makes this work is not any single tool or practice. It's the *stack* — six layers that compound:

### Layer 1: Specification Before Code

Every feature goes through a structured workflow: discovery (read-only exploration), specification (business requirements), planning (technical design with task breakdown), then implementation. By the time code is written, the architecture has been reviewed and approved.

This doesn't prevent bugs. What it prevents is *architectural drift* — the slow divergence between what you intended to build and what you actually built when you were figuring it out file by file.

### Layer 2: Parallel Execution with Bounded Scope

Implementation is split across multiple parallel agents, each owning a non-overlapping set of files. They can't step on each other. Their output is mechanically verifiable: does it compile? Do the tests pass?

This is how 19 source files and 10 test files can be created in a single pass without merge conflicts. It's not just faster — it's *safer*, because each agent's scope is small enough to reason about completely.

### Layer 3: Automated Code Review

An automated reviewer examines every pull request. It doesn't just look for style issues — it analyzes transaction semantics, concurrency patterns, exception propagation paths, and framework-specific behaviors.

In the Shopify webhook session, the automated reviewer found all 8 bugs. Some of these bugs required understanding how Hibernate's `merge()` operation defers database writes, how PostgreSQL marks transactions as aborted after constraint violations, and how Spring's transaction callback system interacts with thread scheduling. These are the kinds of issues that exist at the intersection of three different frameworks, where the behavior depends on subtle interactions that no single developer is likely to hold in their head simultaneously.

A human reviewer might have caught 2-3 of the 8. The automated reviewer caught all 8.

### Layer 4: Structural Enforcement (Architecture Tests)

When a bug reveals a *class* of problem — not just a single instance — we add an automated architecture test. These tests run at compile time and fail the build if the pattern is violated.

Example: We discovered that database entities with manually-assigned IDs need to implement a specific interface, or the database layer silently defers writes in a way that breaks error handling. Rather than documenting this as a "best practice" that developers need to remember, we added a test that scans every entity in the codebase and fails the build if any assigned-ID entity is missing the interface.

The result: that category of bug is now structurally impossible. Not discouraged, not documented — *impossible*. The build won't pass.

We currently have 3 of these architecture rules. They accumulate over time. Each one was born from a real bug. Each one prevents an entire class of future bugs.

### Layer 5: CI Safety Gates

Our continuous integration pipeline doesn't just verify that tests pass — it verifies that tests *ran*. This sounds redundant, but it's not. Tests can be silently skipped due to configuration issues, missing dependencies, or framework incompatibilities. The test suite reports "0 failures" because it ran 0 tests.

We maintain explicit count gates: "this test class must have executed at least N tests." If the count drops, the build fails. This session revealed that the gates were only checking one module out of eight — a blind spot that was immediately closed.

### Layer 6: Postmortems That Feed Forward

After a significant bug (or cascade of bugs), we write a structured postmortem. But the postmortem isn't just documentation — it's a *source of future constraints*. Its lessons become engineering rules, which become context for the AI-assisted development system, which prevents the same class of bug from being generated in the first place.

The feedback loop is: bug → fix → postmortem → constraint → AI context → fewer bugs of that type → new types of bugs → repeat.

Each cycle makes the system smarter. The constraint list grows. The architecture tests accumulate. The AI has more context about what to avoid and what patterns to follow.

## The Bug Cascade: A Case Study

The Shopify webhook session produced the clearest example of this philosophy in action.

**The story:**

1. The system needed to handle duplicate webhook deliveries (Shopify sometimes sends the same sale notification twice). The solution: check if we've seen this event ID before, and if not, save a record and process the order.

2. **Bug found:** Two simultaneous requests can both pass the "have we seen this?" check before either saves its record. The second save hits a primary key constraint violation. The error propagates as a server error to Shopify.

3. **Fix applied:** Catch the constraint violation and treat it as "already processed." Return success to Shopify.

4. **New bug introduced by the fix:** Catching a database constraint violation inside a transaction poisons the database session. The catch block returns a success response, but the transaction management layer detects the poisoned session and throws its own error. Shopify still gets a server error.

5. **Fix applied:** Move the save operation into its own isolated transaction. If the constraint violation happens, only the inner transaction is poisoned. The outer transaction stays clean.

6. **New bug introduced by *that* fix:** The save operation uses a database strategy called "merge" (check if it exists, then insert). With an isolated transaction, the merge defers the actual insert to the transaction's commit phase — *after* the catch block has already executed. The constraint violation fires from the commit, completely outside the error handling code.

7. **Root cause discovered:** This is the same category of bug documented in a postmortem from 18 days earlier (PM-001). That postmortem created one prevention rule, but the prevention only covered one manifestation of the underlying problem. The *other* manifestation — the one we just hit — went unguarded.

8. **Final fix (three layers):**
   - Use a different save method that forces the insert immediately (tactical fix)
   - Implement the correct interface on the entity so the database layer uses "insert" instead of "merge" (architectural fix)
   - Retrofit the same interface onto all 23 entities in the system that have the same pattern (systemic fix)
   - Add an architecture test that fails the build if any future entity has this pattern without the interface (structural prevention)

**The result:** A three-layer cascade of bugs, each one revealing a deeper issue, terminating in a fix that made the entire system safer — not just the webhook, not just the fulfillment module, but every entity in every module.

## Why This Matters

The cascade took about 90 minutes to fully resolve. In a traditional development workflow, some of these bugs would likely have reached production. The deferred-write bug, in particular, is invisible to unit tests — it only manifests with a real database under concurrent load.

But the time spent wasn't wasted. It was *invested*. The system now has:
- An architecture test that prevents the root cause from being reintroduced
- A documented engineering constraint that future development (both human and AI) will follow
- A postmortem that captures the full reasoning for anyone who encounters a similar pattern
- 23 entities that were silently vulnerable to the same class of bug, now fixed

The traditional alternative — finding this bug in production, tracing it through logs, writing a hotfix under pressure, then hoping the postmortem's lessons stick — is slower, riskier, and less thorough.

## The Trust Gradient: From Manual to Autonomous

The system didn't start this way. In the beginning, everything was manual and human-in-the-loop.

Every file edit required explicit approval. Every shell command needed confirmation. Every agent action was reviewed before execution. This was necessary — the validation layers didn't exist yet. There were no architecture tests, no engineering constraints, no CI gates beyond "does it compile." Trusting the system to move fast would have been reckless, because there was nothing to catch what it missed.

But each session added gates. Each postmortem added constraints. Each bug-turned-architecture-test reduced the surface area of what could go wrong silently. And as the gates accumulated, the need for manual approval diminished.

**The progression looked like this:**

1. **Early sessions:** Approve every file write, review every change, manually verify test output. Speed bottleneck: human review of every action.

2. **Middle sessions:** The phase-based workflow (discovery → spec → plan → implementation) provided natural approval checkpoints. Instead of approving every file, you approve the *plan*, then let the agents execute. The four phase gates replaced hundreds of individual approvals.

3. **Current state:** The operator runs with permissions fully open (`--dangerously-skip-permissions`), because the accumulated gates make it safe to do so. The AI won't push to production without being told to. It won't delete branches. It won't modify files outside the project. And if it writes bad code, the architecture tests catch structural violations, the automated reviewer catches semantic bugs, and the CI gates catch silent test skips. The permission system was training wheels — the real safety comes from the validation layers.

**The only remaining speed bottleneck is the four phase approvals** — the human must approve the feature name, the specification, the implementation plan, and the final result. Everything between those checkpoints runs at machine speed. This is intentional: the approvals are strategic decisions ("is this the right thing to build?"), not tactical ones ("is this line of code correct?"). The tactical correctness is handled by the layers.

This is a key insight: **you don't start with trust. You earn it by building systems that justify it.** Each gate you add is simultaneously a safety mechanism and a step toward autonomy. When enough gates exist, manual oversight becomes redundant for the things the gates cover — and you can redirect human attention to the things only humans can judge.

## The Next Gate: Tests Before Code

The validation layers are still evolving. The next planned addition: **writing tests before writing implementation code.**

Today, tests are written alongside or after the implementation. This means a test is always shaped by the code it's testing — it validates what was built, not what *should* have been built. When a test fails, it's usually because the test was written wrong, not because the implementation deviates from the spec.

Flipping the order changes the dynamic:

1. During the planning phase, tests are generated from the specification — they encode what the system *should* do, independent of how it's implemented.
2. The tests fail immediately (there's no implementation yet). This is expected.
3. Implementation agents write code to make the tests pass.
4. If a test can't be made to pass, the question becomes: **is the test wrong, or is the approach wrong?** This is a far more productive question than "does this code work?" because it forces a conversation about intent vs. reality.

This creates a new kind of gate — one that catches not just bugs in the code, but misalignment between the specification and the implementation. It's the difference between "the code does what it does correctly" and "the code does what we actually wanted."

## The Compounding Effect

Each session makes the next session safer. The constraint list grows. The architecture tests accumulate. The CI gates expand. The AI assistant has more context about what patterns to follow and what pitfalls to avoid. The human operator's approval surface shrinks to only the decisions that matter.

This is the compounding effect of treating problems as problems to architect and solve, rather than as failures to avoid:

- **Session N** produces code + bugs
- **Session N's fixes** produce constraints + architecture tests
- **Session N+1** benefits from those constraints + tests
- **Session N+1's bugs** are *different* bugs (not repeats), because the structural prevention works
- **Session N+1's fixes** add *more* constraints + tests
- The operator trusts the system more, removes manual approvals, and redirects attention to strategic decisions
- The system gets permanently smarter — and permanently faster

After 15 postmortems, 16 engineering constraints, and 3 architecture rules, the category of bugs we encounter has shifted. We're not fixing "forgot to add a null check" bugs anymore. We're fixing "the interaction between three frameworks under concurrent load with isolated transactions produces a deferred write that escapes the error handling boundary" bugs. The easy bugs are structurally impossible. The hard bugs get caught by automated review. The very hard bugs get caught, fixed, and converted into structural prevention.

The trust gradient — from fully manual to nearly autonomous — isn't a leap of faith. It's an engineering outcome. Each gate you build is a brick in the bridge between "I need to check everything" and "I only need to check what matters."

This is what production-grade software development looks like at speed: not the absence of problems, but a system that metabolizes problems into permanent improvements — and earns more autonomy with every cycle.
