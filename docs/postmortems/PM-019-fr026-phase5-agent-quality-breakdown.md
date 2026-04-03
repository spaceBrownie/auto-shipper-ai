# PM-019: FR-026 Phase 5/6 Execution Quality Breakdown — Repeated Postmortem Lessons, Sloppy Agent Output

**Date:** 2026-04-03
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

FR-026 (CJ tracking webhook + Shopify fulfillment sync, RAT-28) required 4 rounds of PR review to reach approval — catching 6 bugs that should have been prevented by existing postmortems and engineering constraints. The Phase 5 sub-agent prompts were monolithic and lacked the layer-by-layer specificity used in FR-025. Phase 6 had no automated comment polling, requiring the user to manually trigger each review-fix cycle. Three of the six bugs were direct repeats of PM-015 patterns (synchronous AFTER_COMMIT, missing try-catch after dedup, wrong Shopify resource ID). The implementation plan contained exact code for each layer — but sub-agents wrote their own versions instead of following it, introducing the bugs. The E2E playbook ran and passed, but only tested happy-path scenarios — no failure-after-dedup, response-timing, or API contract verification scenarios existed to catch the bugs that Unblocked later found.

## Timeline

| Time | Event |
|------|-------|
| Session start | README updated and pushed. Feature-request-v2 skill triggered for RAT-28 |
| ~5 min | Phase 1 (Discovery) complete — `cj-tracking-webhook`, FR-026 |
| ~15 min | Phase 2 (Specification) complete — 11 BRs, 11 SCs, 11 NFRs |
| ~25 min | Phase 3 (Planning) complete — 22 tasks across 5 tiers, 11 layers with exact code snippets |
| ~35 min | Phase 4 (Test Specification) complete — 16 ACs, 11 fixtures, 8 test files specified |
| ~36 min | Meta-controller preflight: recommends 3 agents, deliberative mode, 3 chunks of 12 tasks |
| ~37 min | User approves Phase 5. Orchestrator creates foundation (Tier 0+1), launches 2 parallel agents |
| ~45 min | Agent A (CJ chain) and Agent B (Shopify chain) complete. 60 new tests, BUILD SUCCESSFUL |
| ~46 min | E2E playbook updated and executed — all scenarios pass |
| ~47 min | Committed, pushed, PR #42 created |
| ~48 min | **Review round 1**: Unblocked catches Bug #1 (Order GID vs FulfillmentOrder GID) and Bug #2 (body buffering before auth) |
| ~50 min | Fix applied: two-step fulfillment order lookup, filter reordering. Pushed |
| ~52 min | **Review round 2**: Unblocked catches Bug #3 (@Async missing per PM-015) and Bug #4 (GraphQL string interpolation) |
| ~53 min | Fix applied: @Async on both listeners, parameterized GraphQL variables. Pushed |
| ~55 min | **Review round 3**: Unblocked catches Bug #5 (missing try-catch on markShipped) and Bug #6 (bare ObjectMapper) |
| ~57 min | Fix applied: try-catch with ERROR log, inject Spring ObjectMapper. Pushed |
| ~58 min | PR approved |

## The Six Bugs

### Bug #1: Wrong Shopify Resource ID (High)

**What:** `ShopifyFulfillmentAdapter` passed `channelOrderId` (numeric Shopify order ID like `"820982911946154500"`) directly as a `fulfillmentOrderId` in the `fulfillmentCreateV2` mutation. Shopify requires a `FulfillmentOrder` GID (`gid://shopify/FulfillmentOrder/...`), which is a different resource.

**Why it matters:** Every fulfillment sync would silently fail — Shopify returns `userErrors` ("The fulfillment order does not exist"), the adapter returns `false`, and the customer never gets a shipping notification email.

**Root cause chain:**
1. The implementation plan (Layer 4) specified `shopifyOrderGid` as the parameter name and mentioned the GID format, but didn't call out that `channelOrderId` stores a numeric ID, not a GID
2. The Agent B prompt said "Shopify order GID format: `gid://shopify/Order/{numericId}`" but this is the **Order** GID, not the **FulfillmentOrder** GID
3. Neither the orchestrator nor the agent verified how `channelOrderId` is actually populated (by `ShopifyOrderAdapter.parse()` — it extracts `root.get("id")` which is a numeric string)
4. The `fulfillmentCreateV2` mutation requires a `FulfillmentOrder` GID, which must first be queried from the Order

**Fix:** Two-step approach — query `order.fulfillmentOrders` first, then use those GIDs in the mutation. Added `fulfillment-orders-query-success.json` and `fulfillment-orders-query-empty.json` WireMock fixtures.

---

### Bug #2: Body Buffering Before Auth Check (Medium)

**What:** `CjWebhookTokenVerificationFilter` created `CachingRequestWrapper(httpRequest)` at line 49, *before* validating the `Authorization` header at lines 51-63. `CachingRequestWrapper` eagerly reads the entire request body into memory via `readAllBytes()`.

**Why it matters:** Unauthenticated attackers can send large payloads to `/webhooks/cj/*`, forcing memory allocation before rejection. Unlike Shopify's HMAC filter (which needs the body for signature computation), CJ's token auth only checks a header.

**Root cause chain:**
1. The Agent A prompt said "Reuses `CachingRequestWrapper` so the body can be read later by the controller" — correct intent, wrong placement
2. The Shopify HMAC filter creates the wrapper early because it needs the body bytes for HMAC computation. The agent copied this pattern without understanding *why* the wrapper is positioned there
3. No test asserted the ordering of wrapper creation vs auth check

**Fix:** Moved `CachingRequestWrapper` creation after token validation succeeds.

---

### Bug #3: Missing @Async — PM-015 Bug #5 Repeat (High)

**What:** `CjTrackingProcessingService.onCjTrackingReceived()` and `ShopifyFulfillmentSyncListener.onOrderShipped()` used `@TransactionalEventListener(AFTER_COMMIT)` without `@Async`. AFTER_COMMIT fires **synchronously** in the commit thread — the entire processing chain (DB lookups + Shopify API retries with 1s/2s/4s backoff) blocks the HTTP 200 response to CJ.

**Why it matters:** Worst case: 7+ seconds of Shopify retries blocking the CJ webhook response. If CJ enforces a timeout, tracking updates are lost.

**This is a direct repeat of PM-015 Bug #5**, which was fixed by adding `@Async` to `ShopifyOrderProcessingService.onOrderReceived()`. The fix was documented in PM-015, the `@EnableAsync` annotation was already on the application class, and `ShopifyOrderProcessingService` was listed as a pattern reference in the agent prompts — but the agent didn't carry the `@Async` annotation over.

**Root cause chain:**
1. CLAUDE.md constraint #6 says "AFTER_COMMIT + REQUIRES_NEW" but does **not** mention `@Async`
2. PM-015 documented the `@Async` fix but it was never codified into CLAUDE.md as a constraint
3. The agent prompts referenced `SupplierOrderPlacementListener` (which lacks `@Async`) as the pattern — not `ShopifyOrderProcessingService` (which has it)
4. The implementation plan (Layer 7, Layer 8) specified only `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` — matching CLAUDE.md #6 but missing `@Async`

**Fix:** Added `@Async` to both listeners.

---

### Bug #4: GraphQL String Interpolation (Low)

**What:** `ShopifyFulfillmentAdapter.queryFulfillmentOrders()` used string interpolation (`"$orderGid"`) in the GraphQL query body, while `executeFulfillmentCreate()` used parameterized GraphQL variables.

**Why it matters:** If `channelOrderId` ever contains GraphQL metacharacters (e.g., `"`), the query breaks. Inconsistent with the mutation pattern.

**Fix:** Changed to parameterized variables (`$orderId: ID!` + `variables` map).

---

### Bug #5: Missing try-catch on markShipped() — PM-015 Bug #1 Pattern (High)

**What:** `CjTrackingProcessingService.onCjTrackingReceived()` called `orderService.markShipped()` without a try-catch. If `markShipped()` fails (transient DB error, optimistic lock, etc.), the exception propagates to the async executor's uncaught handler. But the dedup record (`cj:{orderId}:{trackingNumber}`) was already committed by the controller's transaction. CJ's retries will receive `"already_processed"`, leaving the order permanently stuck in CONFIRMED.

**This is the same failure pattern as PM-015 Bug #1** (unsupported currency crashes event listener after dedup commit) and **PM-015 Bug #2** (malformed timestamp throws uncaught exception after dedup commit). The pattern is: "dedup committed, processing crashes, retries blocked, data permanently stuck."

**Root cause chain:**
1. The implementation plan (Layer 7) didn't include try-catch around `markShipped()`
2. Agent A's prompt didn't mention the dedup-then-crash failure mode
3. `ShopifyFulfillmentSyncListener` (written by Agent B) *does* have try-catch — but Agent A didn't follow that pattern
4. The test-spec had no explicit "markShipped failure" test case for the processing service

**Fix:** Wrapped `markShipped()` in try-catch, logged at ERROR for alerting. Added failure-path test.

---

### Bug #6: Bare ObjectMapper Instead of Spring-Managed Bean (Low)

**What:** `ShopifyFulfillmentAdapter` created `private val objectMapper = ObjectMapper()` — a plain Jackson instance without the Kotlin module or Spring-configured modules.

**Root cause:** Agent B's prompt didn't specify ObjectMapper injection. The codebase pattern (`ShopifyListingAdapter`, `CjSupplierOrderAdapter`) is to inject the Spring `ObjectMapper`.

**Fix:** Constructor-injected Spring `ObjectMapper`.

### Bug #7: E2E Playbook Missed Detectable Bugs (Process Gap)

**What:** The E2E playbook was updated and executed during Phase 5, and all scenarios passed. However, the playbook scenarios were purely functional (happy-path chain, dedup, auth rejection, unknown order). No scenario verified:
- That the HTTP response returns *before* the processing completes (Bug #3 — @Async)
- That a processing failure after dedup doesn't leave the order permanently stuck (Bug #5 — try-catch)
- That the Shopify API receives a FulfillmentOrder GID, not an Order GID (Bug #1 — wrong resource ID)

**Why it matters:** The playbook is the last gate before PR creation. If it had included failure-path and timing scenarios, at least Bugs #3 and #5 could have been caught before review.

**Root cause chain:**
1. The test-spec's E2E scenarios (E2E-CJ-1 through E2E-CJ-5) were all happy-path or simple error cases — no failure-after-dedup scenario, no response-timing assertion
2. The E2E playbook subagent faithfully transcribed the test-spec scenarios but didn't augment them with adversarial/failure scenarios
3. Phase 5 instructions say "update playbook with new scenarios from test-spec.md" — but test-spec.md itself lacked failure-path E2E scenarios
4. The playbook ran against a local profile where `StubShopifyFulfillmentAdapter` returns `true` — so the real Shopify API path (with the wrong GID) was never exercised

**Additionally:** The E2E playbook was run *during* Phase 5 (before PR creation), which is correct per the workflow. But the scenarios were designed only from test-spec.md, which itself was written before implementation. Post-implementation E2E scenarios — informed by the actual code paths and integration points — would have been more targeted.

---

## Root Cause Analysis — 5 Whys

### Why did 6 bugs survive Phase 5?

1. **Why** did sub-agents produce code with these bugs? → The agent prompts were monolithic (one giant prompt per agent) instead of layer-by-layer with specific code to follow.

2. **Why** were the prompts monolithic? → The orchestrator didn't follow the Phase 5 instructions, which specify "Spawn sub-agents in dependency-ordered groups" (Foundation → Domain Logic → Integration). Instead, it created 2 agents by functional area (CJ chain vs Shopify chain).

3. **Why** didn't the orchestrator follow the layered approach? → FR-025 (PM-018) had evolved a "Round 1: new files, Round 2: modified files, Round 3: tests" pattern that worked well. This session skipped that — the orchestrator sent both new-file creation and test writing to the same agent.

4. **Why** weren't PM-015 lessons (Async, try-catch after dedup) applied? → PM-015's fixes were documented in a postmortem but never promoted to CLAUDE.md constraints. The agent prompts referenced CLAUDE.md #6 but #6 only says "AFTER_COMMIT + REQUIRES_NEW" — it doesn't mention `@Async`. The implementation plan also only specified the two-annotation pattern.

5. **Why** doesn't CLAUDE.md capture the `@Async` and try-catch-after-dedup patterns? → PM-015 and PM-018 both identified prevention items but those prevention items were never executed — they remain unchecked in the postmortem documents.

### Why was Phase 6 manual?

1. **Why** did the user have to manually trigger each review round? → No automated comment polling was implemented.
2. **Why** wasn't it implemented? → PM-018 prevention item "Phase 6: add comment polling" was documented but never executed (still unchecked).
3. **Why** do prevention items go unexecuted? → No tracking mechanism ensures postmortem prevention items are implemented before the next feature request.

## Impact

- **User time wasted:** 3 manual review-fix cycles that should have been 0-1
- **No production impact:** Bugs caught before merge
- **Confidence erosion:** PM-015 postmortem lessons not applied undermines trust in the postmortem process

## Lessons Learned

### What went well
- **Unblocked bot is a critical safety net** — caught all 6 bugs across 3 review rounds
- **Build stayed green throughout** — every fix was verified before push
- **Foundation pieces done right** — the orchestrator's Tier 0+1 work (OrderShipped, CjCarrierMapper, CjTrackingReceivedEvent, config, OrderService modification) was clean
- **E2E playbook functional scenarios all passed** — the core chain (webhook → SHIPPED → DELIVERED) works

### What could be improved
- **Sub-agent prompts need layer-by-layer structure** — monolithic "here's everything, go" prompts produce sloppy code. FR-025's Round 1/2/3 pattern (new files → modified files → tests) was more effective
- **Implementation plan code should be binding** — Layer 7 had exact code for `CjTrackingProcessingService`. If the agent had followed it, Bug #5 would still exist (no try-catch in the plan) but Bug #3 (missing @Async) would also still exist. The plan itself was incomplete
- **Postmortem prevention items are dead letters** — PM-015 and PM-018 both have unchecked prevention items that would have prevented bugs in FR-026
- **CLAUDE.md constraint #6 is incomplete** — needs `@Async` for webhook-triggered listeners
- **Phase 6 still has no automated polling** — user manually triggers every review cycle
- **E2E playbook only tests happy paths** — no failure-path scenarios (dedup-then-crash, response timing, wrong API resource ID). The playbook transcribed test-spec.md verbatim instead of adding adversarial scenarios informed by the actual implementation
- **E2E playbook can't exercise real Shopify paths** — local profile uses `StubShopifyFulfillmentAdapter`, so the wrong-GID bug (Bug #1) was invisible to E2E. Need WireMock-backed E2E scenarios or a "contract E2E" mode that validates request shapes
- **Test-spec E2E scenarios are written pre-implementation** — they reflect the *spec*, not the *code*. Post-implementation review of E2E scenarios (informed by actual integration points, thread boundaries, and error paths) would catch more issues

## Prevention

### CLAUDE.md Constraint Updates (Immediate)

- [x] **Update constraint #6** — Add: "Webhook-triggered `@TransactionalEventListener(AFTER_COMMIT)` handlers that run after an HTTP response must also carry `@Async` to avoid blocking the webhook response thread. Applies to any listener in the CJ webhook → processing → Shopify sync chain or similar patterns where the publisher is itself an async-processed webhook event."
- [x] **Add constraint #19: Try-catch after dedup commit** — "Any `@TransactionalEventListener(AFTER_COMMIT)` handler that processes a deduplicated event (where the dedup record was committed in a prior transaction) MUST wrap its critical operations in try-catch. Without this, a transient failure leaves the event permanently stuck — retries are blocked by the committed dedup record. Log at ERROR with the dedup key for manual intervention."
- [x] **Add constraint #20: Inject Spring-managed ObjectMapper** — "Never create `ObjectMapper()` in Spring components. Always inject the Spring-managed bean, which has the Kotlin module and other project-configured modules registered."

### Phase 5 Execution Improvements

- [x] **Enforce layered agent spawning** — Phase 5 instructions already specify Foundation → Domain → Integration groups. The orchestrator must follow this, not collapse into functional-area agents. Add to Phase 5 instructions: "Sub-agents MUST be spawned in dependency-ordered layers, not by functional area. Each round should produce a compilable increment."
- [x] **New-files-first, modifications-second** — Codify the FR-025 pattern: Round 1 creates new files only, Round 2 modifies existing files, Round 3 writes tests. This prevents agents from making conflicting changes to shared files.
- [x] **Implementation plan code is the floor** — Sub-agent prompts must include the exact code from the implementation plan as the starting point. Agents may improve it but must not regress below it.
- [x] **Postmortem-aware prompts** — Before Phase 5, scan `docs/postmortems/` for prevention items tagged with the current module. Include relevant constraints in sub-agent prompts.

### E2E Playbook Improvements

- [x] **Mandatory failure-path E2E scenarios** — For any feature with dedup + async processing, the playbook must include: (1) a "processing failure after dedup" scenario verifying the system doesn't permanently lose the event, (2) a response-timing scenario verifying the HTTP response returns within the external system's timeout (e.g., CJ's webhook timeout)
- [x] **Post-implementation E2E review pass** — After Phase 5 implementation completes but before the E2E subagent runs, the orchestrator should review the implementation's integration points (thread boundaries, external API calls, failure modes) and augment test-spec E2E scenarios with adversarial cases. The E2E subagent should receive both the test-spec scenarios AND the orchestrator's augmented scenarios
- [ ] **Contract E2E mode for stubbed adapters** — When E2E runs against local profile with stubs, add a "contract verification" step that reads the stub's expected request/response shapes and compares them against WireMock fixtures. This would have caught the Order GID vs FulfillmentOrder GID mismatch (Bug #1) even without a real Shopify API
- [x] **E2E playbook must run AFTER all implementation is complete** — Phase 5 step 6 says this, but the playbook scenarios should be finalized post-implementation, not just transcribed from pre-implementation test-spec.md

### Phase 6 Automation

- [x] **Implement comment polling loop** — Phase 6 should poll `gh api repos/{owner}/{repo}/pulls/{pr}/comments` and `gh api repos/{owner}/{repo}/pulls/{pr}/reviews` every 60 seconds. When new comments arrive, automatically read and categorize them. Exit when: CI green + PR approved + no unresolved comments.

### Postmortem Process

- [ ] **Prevention item tracker** — Create a mechanism (could be a file, a Linear label, or a skill) that tracks unchecked prevention items from prior postmortems and surfaces them during Phase 1 (Discovery) of subsequent feature requests.
