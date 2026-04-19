# PM-021: FR-030 — First Autonomous Review-Fix Loop + Token Economics

**Date:** 2026-04-18
**Severity:** Medium
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

FR-030 (RAT-53 Shopify dev-store + Stripe test-mode gate-zero) shipped end-to-end in one session through the full 6-phase feature-request-v2 workflow, including the first successful run of the Phase 6 autonomous review-fix polling loop. The loop cycled 4 times against `unblocked[bot]`, surfaced 4 legitimate bugs, pushed fixes with regression guards, and reached APPROVED state without human intervention in the review cycle. Cost: orchestrator hit 309k tokens (31% of 1M context) — and that's only the orchestrator, not counting ~10 subagents that each ran their own independent context windows. Several novel process failures surfaced that the v2 workflow had not previously encountered at this scope.

This PM documents two intertwined findings — token economics and serial review cycles — then proposes a single architectural change (subagent log directory) that addresses the root causes of both, backed by a prevention list where each item is tagged with the Claude Code hook event that can enforce it deterministically.

## Timeline

| Time | Event |
|------|-------|
| Session start | User invoked `/feature-request-v2` on RAT-53. Filemap hydrated via graphify (273 entries). |
| Phase 1 (Discovery) | Subagent identified scope as ~80% ops / 20% code, flagged 6 open questions, proposed feature name. Load-bearing call: surfaced `ShopifyInventoryCheckAdapter` SKU-UUID gap as E2E blocker. |
| Phase 2 (Spec) | Subagent resolved all 6 OQs with rationale. Decisions: Stripe test mode primary, `.env` for secrets (no Vault), runbook-only live/dev guard. |
| **User correction #1** | RAT-47 (CJ reconciliation) flagged as already completed in FR-028/PR #48. Memory file was 4 days stale. All 3 planning artifacts updated to remove "in progress" language. |
| Phase 3 (Plan) | Subagent resolved 4 implementation OQs. 20 tasks across 6 layer buckets. |
| **User correction #2** | User provided CJ sandbox-account API-doc excerpt. NOT in any memory. Reshaped NFR-3, added BR-3a (sandbox) + BR-6b (dry-run kill-switch). Task count grew 20 → 24. 5 new tests (T-64..T-68). |
| Phase 4 (Test Spec) | 63 testable assertions + 10 E2E scenarios. Load-bearing: T-10/T-30 (NullNode guard), T-65 (zero-HTTP-call verification for CJ dry-run). |
| Phase 5 preflight | Meta-controller recommended 3 parallel agents + orchestrator, chunks of 8, deliberative cognition. User accepted. |
| **Phase 5 human gate** | Orchestrator paused. User approved. |
| Phase 5 Round 0 | Orchestrator directly wrote V23 migration, fixtures README, .gitkeep. |
| Phase 5 Round 1 | 3 parallel subagents: WebhookArchivalFilter, DevAdminController, Gradle devStoreAuditKeys. All BUILD SUCCESSFUL. |
| Phase 5 Round 2 | 3 parallel subagents: catalog wiring, fulfillment adapter + CJ dry-run, config/runbook/.env.example. |
| Round 2 break | `CjSupplierOrderAdapterWireMockTest` helper broken by new constructor param. Orchestrator patched before Round 3. |
| Phase 5 Round 3 | 3 parallel test subagents. Wrote 63 tests + 6 fixtures. |
| Round 3 break | `PlatformListingEntityPersistenceTest` failed — no parent `skus` row seeded for FK; used `sku.id.value` but `Sku.id` is raw `UUID`. Orchestrator fixed. |
| Full suite | 655 tests passing, 0 failures across 10 modules. |
| E2E playbook subagent | Fresh session. Added 14 scenarios (SC-RAT53-01..10 from test-spec + 11..14 orchestrator-augmented). |
| Git staging | 52 archival files leaked into `modules/app/docs/fixtures/` during integration tests. Unstaged + added `/modules/*/docs/` to `.gitignore`. |
| **Phase 5 → 6 human gate** | Orchestrator paused. User approved. |
| PR #52 created | CI started. |
| Polling cycle 1 | Bot flagged `@Component` on `WebhookArchivalFilter` — Spring auto-registers `Filter` beans at `/*` regardless of `@ConditionalOnProperty` on the config. Real production bug. |
| Fix 1 (`c3beabf`) | Removed `@Component`, moved `@Value` to config, added T-43b regression guard. First write broke on nested `/*` inside KDoc. |
| Accidental commit | `.claude/scheduled_tasks.lock` got swept up by `git add -A`. Follow-up gitignore commit. |
| Bot silence | Orchestrator assumed bot wouldn't re-review. Reported loop exit. |
| **User correction #3** | Bot requires `@Unblocked please review again, I fixed the issue…` trigger phrase. Not in any accessible doc. |
| Polling cycle 2 | Bot flagged missing `LIMIT 1` on `PlatformListingResolver.resolveInventoryItemId`. |
| Fix 2 (`9e48c1a`) | Added `LIMIT 1`. T-22 tiebreaker still passes. |
| Polling cycle 3 | Bot flagged same-ms filename collision in `WebhookArchivalFilter` (epoch-ms alone isn't unique). |
| Fix 3 (`4375f75`) | Added 8-hex UUID suffix. T-48b regression guard (20 same-ms deliveries → 20 distinct files). |
| Polling cycle 4 | Bot flagged `request.servletPath` → filesystem-hostile chars could pass through slug sanitization. |
| Fix 4 (`1d0e31c`) | `.replace(Regex("[^a-z0-9._-]"), "")` whitelist. T-49b guard with crafted hostile `servletPath`. |
| Polling cycle 5 | Bot APPROVED. CI green. Rebuilt graphify (2238 nodes, 1871 edges). Loop exited. |

## Symptoms

### Symptom A: Token economics were not modeled before the session

Orchestrator at 309k tokens (31% of 1M) at loop exit. This does **not** include:

- ~10 subagent context windows (Phase 1, Phase 2, Phase 3, Phase 4, Round 1 × 3, Round 2 × 3, Round 3 × 3, E2E fresh session). Each subagent ran independent contexts sized 50k–190k tokens per tool-result tallies.
- Total aggregate consumption across orchestrator + subagents plausibly 1M+ tokens for a "moderate-sized" FR (24 tasks / 68 tests).

Meta-controller outputs execution strategy but not cost projection. Aggregate is invisible until the session is over.

### Symptom B: Four serial review-cycle iterations for bugs a single careful review would have caught

The `@Component`-on-Filter bug is a pattern already established in the codebase — `ShopifyHmacVerificationFilter` and `CjWebhookTokenVerificationFilter` are explicitly not `@Component`. The Phase 5 Round 1 Agent A prompt told the subagent to "match the existing pattern" but the agent wrote `@Component("webhookArchivalFilter")` anyway. Similarly: `LIMIT 1`, same-ms filename collision, and slug whitelist were all predictable once the filter code existed — nothing in the subagent prompts flagged "review new filesystem-writing code for race/overwrite/injection surface" as a checklist item.

## Root Cause

### 5 Whys — Symptom A (token economics)

1. **Why** was the orchestrator at 309k tokens? → It held every subagent's final report verbatim, every tool result for every file edit and Bash command, every `<system-reminder>`, and the full text of 5+ review comments with diffs.
2. **Why** wasn't context compacted? → v2 has no explicit compaction step between phases.
3. **Why** don't subagent contexts roll back into the orchestrator? → By design — subagents isolate context. But that means the orchestrator cannot answer questions about what a subagent did without re-reading files. Total cost = orchestrator + Σ(subagents), invisible until after the fact.
4. **Why** wasn't this modeled before the session? → The meta-controller outputs chunk-size and agent-count recommendations but not **projected token cost**. No phase has a budget check.
5. **Why** is there no budget check? → v2 was designed for correctness, not cost. Cost only becomes visible at scale (FR-030 is the first time 10 subagents were spawned from one orchestrator session).

### 5 Whys — Symptom B (serial review cycles)

1. **Why** did unblocked[bot] find 4 bugs across 4 cycles instead of 1? → Each bot review pass runs against the current HEAD. After each fix lands, the bot re-scans and finds the *next* highest-severity issue.
2. **Why** didn't the Phase 5 subagents find these themselves? → Subagent prompts included specific postmortem constraints (CLAUDE.md #13-#20) but did NOT include "review your own new I/O surface for race conditions, filename collisions, input sanitization."
3. **Why** weren't those constraints in the prompt? → They weren't in any postmortem the orchestrator scanned. The `@Component` pattern was in the codebase but not in a PM.
4. **Why** didn't the orchestrator review the subagent output before PR? → It did compile + test. Tests passed. The only *static* review was "does the test suite go green?" — not "does this filter match the existing filter pattern?"
5. **Why** is there no pre-PR structural review? → The skill has `unblock` integration as a "use as needed" guideline, but no explicit pre-PR step for self-review against existing patterns. `unblocked[bot]` review becomes the de facto review gate — which works, but serially.

## Novel Failure Modes

Eight distinct patterns first observed in this session, not previously documented:

1. **`unblocked[bot]` trigger-phrase discoverability.** Bot requires `@Unblocked please review again, I fixed the issue…` to re-review. Not documented anywhere the agent had access to. User had to tell the orchestrator.

2. **Nested `/*` in KDoc breaks Kotlin lexer.** Writing `URL pattern \`/*\`` inside a KDoc opened a nested block-comment level because Kotlin (unlike Java) supports nested `/* */` blocks. Caught at compile time, wasted a cycle.

3. **Relative-path archival leak.** `autoshipper.webhook-archival.output-dir: docs/fixtures/shopify-dev-store` resolves from Gradle CWD. When `@SpringBootTest` booted, CWD was `modules/app/`, so the filter wrote to `modules/app/docs/fixtures/…`. 52 files leaked into `git add -A`.

4. **Session-persistence artifact committed via `git add -A`.** `ScheduleWakeup` writes `.claude/scheduled_tasks.lock` which is project-local but ephemeral. Not gitignored by default.

5. **Memory file decay without blocking signal.** The RAT-47 memory carried "in progress" language 4 days after RAT-47 merged. Read-time `<system-reminder>` said "this memory is 4 days old" (advisory) but the orchestrator had already embedded the stale text into all 3 planning artifacts.

6. **Missing-from-memory facts.** CJ sandbox accounts exist and are critical to any live-test strategy, but this was not in any memory file. Without the user's hands-on correction, the feature would have shipped with `dev-store-dry-run` as permanent default.

7. **Subagent field-name drift.** Round 2B was told `CjSupplierOrderAdapter` dry-run log should include `request.productSku`. Actual field is `supplierVariantId`. Subagent substituted correctly, but the *orchestrator prompt* carried the wrong field because the implementation-plan.md had it wrong — plan was written from test-spec language, not entity inspection.

8. **FK-constraint blind spot in Round 3 tests.** Round 3A wrote `PlatformListingEntityPersistenceTest` using `UUID.randomUUID()` as `skuId`. The `platform_listings.sku_id → skus.id` FK rejected the insert. Test-writing subagents do not receive "inspect schema's referential constraints before seeding test data" guidance.

## Impact

**What was affected:**
- Session efficiency — Phase 6 took ~20+ minutes of wall clock on polling + fixing alone, on top of Phases 1-5.
- Token budget — orchestrator consumed 31% of a 1M context window; subagent aggregate was invisible and unbounded.
- Institutional knowledge — several novel process failures went undocumented before this PM.

**Was the output correct?** Yes. PR #52 merged with:
- 655 tests passing
- 4 real bugs caught pre-merge (filter bean leak, missing `LIMIT 1`, filename collision, slug injection)
- 4 regression guards committed alongside each fix (T-43b, T-48b, T-49b + expanded T-43)
- CJ sandbox knowledge captured in memory for future sessions

**Blast radius of non-detection:** Had the Phase 6 autonomous loop NOT been used, the 4 bugs would have shipped to `main`. The `@Component` bug in particular would have caused disk writes on every HTTP request in production. The loop earned its cost.

## Lessons Learned

### What went well
- **Autonomous Phase 6 loop worked.** 4 iterations, 4 fixes, APPROVED state reached.
- **Meta-controller recommendation was sound.** 3 parallel agents / deliberative / 8-task chunks held up across Rounds 1-3.
- **Fresh-session E2E subagent pattern worked.** Context isolation prevented pollution.
- **Load-bearing safety tests held.** T-10, T-30, T-65 all passed. T-65 (zero HTTP calls when dry-run on) is the single most valuable test in the FR.
- **User's 2 corrections caught early.** RAT-47 stale-status and CJ sandbox both injected before Phase 5, avoiding rework.
- **Regression guards for every review fix.** Every bug landed with a new named test. None of these tests existed in test-spec.md — they were born from the review cycle.
- **Graphify rebuild at end of Phase 6.** 2238 nodes updated; next FR starts with an accurate filemap.

### What could be improved
- **No token budget at any phase.** Meta-controller projects strategy, not cost.
- **Phase 5 subagents do not self-review against existing patterns.** "Match the pattern" is insufficient when the pattern involves absence of an annotation.
- **No pre-PR structural review step.** Skill jumps from `./gradlew test green` → `gh pr create`.
- **Memory freshness is user-enforced.** Read-time `<system-reminder>` is advisory, not blocking.
- **Missing process knowledge** (`@Unblocked` trigger phrase) cost a review cycle.
- **Test-path conventions are not pinned.** Integration tests inheriting Spring defaults wrote archival files to the wrong module directory.

## Design Proposal: Subagent Log Directory

The single most expensive missing primitive in v2 is **shared state between orchestrator and subagents that doesn't live in the orchestrator's context window**. This proposal addresses the root cause of both symptoms at once.

### The gap today

- Subagent final reports are injected verbatim into the orchestrator prompt → orchestrator bloat.
- Subagent internal reasoning, tool calls, file edits, and token usage are invisible unless re-summarized.
- Post-session forensics (reconstructing this PM) took ~20 min of scrolling tool results.
- No way for a downstream subagent to read what the upstream agent decided without the orchestrator embedding that summary into the downstream prompt.

### The proposal

Mandate every subagent to write a structured summary file to `feature-requests/FR-{NNN}-{name}/decision-support/subagent-logs/{phase}-{round}-{role}-{UTC-timestamp}.md` as its final step. The orchestrator enriches each file with token-usage and wall-clock-duration metadata after the subagent returns. The directory becomes the **session's persistent memory across agent boundaries** — read-on-demand by the orchestrator, consumable by downstream subagents, and archival-quality for post-mortems.

### File format

```markdown
---
phase: 5
round: 1
agent_role: 1A-webhook-archival-filter
agent_id: ac4de1e0410e4965d
started_at: 2026-04-18T23:30:11Z
ended_at: 2026-04-18T23:31:31Z
duration_seconds: 80            # filled in by orchestrator from agent tool-result metadata
tool_uses: 14                    # filled in by orchestrator
token_estimate: 62702            # filled in by orchestrator
files_created:
  - modules/fulfillment/src/main/kotlin/.../WebhookArchivalFilter.kt
  - modules/fulfillment/src/main/kotlin/.../WebhookArchivalFilterConfig.kt
files_modified: []
compile_result: BUILD SUCCESSFUL
test_result: not-run
---

## What this agent did
Created WebhookArchivalFilter and its config. Reused existing CachingRequestWrapper.

## Decisions made
- Archival filter registered as @Component for Spring discovery, config owns lifecycle.
- Order = -9; rationale: HMAC is 1, leave headroom.

## Open questions surfaced
None.

## Assumptions made (flag for orchestrator review)
- Assumed @Component on a Filter would NOT auto-register at /*.   ← THIS is the kind of
  assumption the pre-PR audit catches.

## Constraints honored
- CLAUDE.md #13, #14, #20: all yes/n/a.

## What downstream agents need to know
- Filter order is -9.
- The filter is @Component.
```

### How it addresses each PM symptom

| Symptom | How the log directory helps |
|---|---|
| Orchestrator hits 309k tokens | Orchestrator receives a 1-sentence "wrote log to `<path>`" reply and reads the log only when it needs details. Bloat becomes opt-in. |
| Subagent token cost invisible | Every log has `token_estimate` in frontmatter. `decision-support/session-totals.json` generated by a 10-line script. |
| Post-mortem reconstruction took 20 min | PM skill points at `decision-support/subagent-logs/` and the timeline writes itself. |
| Field-name drift (Round 2B) | Downstream subagents read Round 2's log to see the actual field name that was used, not planning-stage language. |
| FK-constraint blind spot (Round 3) | Round 2A's log would have stated "entity requires FK parent row seeded" — Round 3A would have read it. |
| `@Component` bug slipped past Phase 5 | **Assumptions made** section forces subagents to surface load-bearing assumptions. Orchestrator batch-reviews the assumptions between rounds. |
| Memory decay with no blocking signal | Assumptions section catches "I assumed RAT-47 was still in progress because the memory said so" — orchestrator can cross-check. |

### Limitations

1. **Subagents can't self-report their own token usage** — usage lives on the tool-result envelope, visible to the orchestrator AFTER return. Pattern: subagent writes the draft log; orchestrator (or a hook) fills `token_estimate` / `duration_seconds` / `tool_uses` from the `<usage>` block.
2. **Trusting subagent self-reports has limits** — the file is *intent*, not ground truth. Treat `files_created` as a claim to verify against `git status`.
3. **Schema drift** — if agents invent frontmatter fields, aggregation breaks. Prevention: a committed template the prompts reference verbatim.
4. **Storage cost** — acceptable. Plain text, a few KB each, alongside spec/plan/test-spec which are already committed.
5. **Orthogonal to, not a replacement for, summary.md** — summary.md is the outward-facing feature doc; subagent logs are the internal session audit trail.

## Prevention

Most prevention items below are enforced by Claude Code hooks (`code.claude.com/docs/en/agent-sdk/hooks`) rather than instructions. Items tagged **[HOOK: event]** are enforced deterministically by the harness, not the model. Items with no tag are file-additions, skill-changes, or pure knowledge.

### Subagent log directory (implements the Design Proposal above)

- [ ] Add `.claude/skills/feature-request-v2/templates/subagent-log-template.md` with the frontmatter schema and narrative sections shown above.
- [ ] Update every phase's instruction file (`phase-1-discovery.md` through `phase-5-implementation.md`) to mandate writing the log before return.
- [ ] **[HOOK: SubagentStop]** Reject subagent return if no log file exists newer than the subagent's start time. Exit 2 with "subagent must write a log before returning." This is the teeth that makes the mandate real.
- [ ] **[HOOK: PostToolUse on `Agent`]** After each `Agent` tool result, enrich the matching log file with `duration_seconds` / `tool_uses` / `token_estimate` parsed from the `<usage>` block. ~30 LOC shell script.
- [ ] **[HOOK: PreToolUse on `Bash gh pr create`]** Grep all `decision-support/subagent-logs/*.md` for `## Assumptions made` sections; exit 2 with the aggregated list if any assumption is unverified. **This is the specific mechanism that would have blocked PR #52's 4 review cycles.**
- [ ] Add `scripts/session-totals.py --fr FR-{NNN}` that reads all logs and outputs token/duration/tool-use aggregates. Invoked by Stop hook.
- [ ] **[HOOK: Stop]** Run `session-totals.py` at end of turn; write `decision-support/session-totals.json`.
- [ ] Teach the `incident-postmortem` skill to consume `decision-support/subagent-logs/` as primary source material (replaces conversation-scan reconstruction).
- [ ] Add downstream-read expectation to subagent prompts (Round N prompts read Round N-1 logs). Reduces orchestrator prompt bloat and field-name drift.
- [ ] **[HOOK: PreToolUse on `Bash gh pr create`]** Also call `validate-phase.py --check-subagent-logs` to ensure ≥1 log per phase exists before PR creation.

### Token / cost visibility

- [ ] **[HOOK: PreToolUse on `Bash` matching `meta_controller.py`]** Extend `meta_controller.py` to print projected orchestrator-token cost at loop exit; hook ensures the projection is surfaced before user approves Phase 5. Low-effort follow-on from the log-directory work.
- [ ] Document the "compaction moment" heuristic: if orchestrator context >40% before Phase 6, spawn a fresh summary-writer subagent that reads checkboxes + git log + subagent logs and reports compact state.

### Review quality (would have caught the 4 PR #52 bugs pre-PR)

- [ ] **[HOOK: PreToolUse on `Bash gh pr create`]** For every NEW Spring bean / filter / controller introduced on the branch, diff against existing sibling classes in the same package; warn on pattern divergence (e.g. `@Component` on a `Filter` when no other filter has it). Same hook as the assumption audit.
- [ ] Add an I/O-writing checklist to Phase 5 subagent prompts: "(a) filename/key collision under same-ms concurrency, (b) user-controlled input sanitization, (c) SQL `LIMIT N` for any `.first()` consumer." These three alone would have eliminated cycles 2, 3, 4.
- [ ] **[HOOK: SessionStart]** Inject `@Unblocked please review again, I fixed the issue…` trigger-phrase reminder into initial context. Saves a full review cycle.

### Memory freshness

- [ ] **[HOOK: PreToolUse on `Read` of `~/.claude/projects/*/memory/*.md`]** Inject "this memory is N days old — verify before quoting in planning artifacts" when mtime > 7 days. Upgrade from advisory to blocking when the target write is to `spec.md` / `implementation-plan.md` / `test-spec.md`.
- [ ] Auto-update project memories after each merged PR: a post-Phase-6 script checks if the PR title matches `RAT-N`, then updates any memory file mentioning `RAT-N` as "in progress."

### Test-infrastructure hygiene

- [ ] **[HOOK: SessionStart]** Auto-append `.claude/scheduled_tasks.lock` to `.gitignore` if absent. Belt-and-suspenders: **[HOOK: PreToolUse on `Bash git add -A`]** greps staged tree for `.claude/` files and warns.
- [ ] Tests that enable `WebhookArchivalFilter` MUST override `output-dir` to a `@TempDir`. Canonical fix is in code, not a hook — but **[HOOK: PostToolUse on `Bash ./gradlew test`]** can verify no fixtures appeared outside `/docs/fixtures/` as a regression safety net.
- [ ] Phase 5 Round 3 subagent checklist: "For every entity-persistence test, seed all FK-parent rows first." (Not hook-shaped — goes in the test-writing subagent's prompt template.)
- [ ] ArchUnit rule: when a test uses `.value` on a field, verify the field's type is actually a value class.

### KDoc conventions

- [ ] **[HOOK: PostToolUse on `Edit`/`Write` for `*.kt`]** Grep for nested `/*` inside `/** ... */` blocks; warn on match. Could also run detekt in the same hook.

### Process knowledge

- [ ] Save a reference memory for autonomous-loop operation. Trigger phrase, serial-review cadence, common `unblocked[bot]` finding categories (filter lifecycle, SQL optimization, filename collisions, input sanitization).

### Hook caveats (for whoever implements the above)

- Hooks add debugging surface. A hook failure often manifests as "the agent suddenly stopped." Every hook script needs logging.
- Hooks run globally unless filtered. A `PreToolUse` that greps every Bash command adds latency. Filter on command pattern.
- Some hooks require user permission-mode awareness (a hook shelling out to `gh api` in a restricted session may fail silently).
- Per-project (`.claude/settings.json`) vs per-user (`~/.claude/settings.json`): subagent-log enforcement is per-project; `.claude/scheduled_tasks.lock` gitignore is per-user (applies to every CC project).
- Graphify has a standing no-hooks instruction (per `feedback_graphify_no_hooks.md`). Scoped to graphify only.

## Next Steps

Highest-leverage hooks, ordered by cost-to-implement vs. bugs-prevented. Filed as **[RAT-55](https://linear.app/ratrace/issue/RAT-55)** under `Innovation` tag:

1. **`PostToolUse` on `Agent`** → enrich subagent log with usage metadata. ~30 LOC. Enables the entire log-directory proposal; highest leverage.
2. **`PreToolUse` on `gh pr create`** → audit `## Assumptions made` sections + structural bean diff. ~20 LOC. Would have prevented PR #52's 4 review cycles.
3. **`SubagentStop`** → enforce log-file existence. ~15 LOC. Makes the log mandate real.
4. **`SessionStart`** → gitignore `.claude/scheduled_tasks.lock` + inject `@Unblocked` trigger phrase reminder. ~10 LOC.

The `update-config` skill (`.claude/skills/update-config/`) is the correct tool to write these into `.claude/settings.json`.

Follow-up implementation work outside the hooks themselves:
- Subagent-log template file
- `scripts/session-totals.py`
- Phase instruction-file updates (`phase-1-discovery.md` .. `phase-5-implementation.md`)
- `incident-postmortem` skill teach-to-read-logs
- Memory auto-update script for merged PRs
