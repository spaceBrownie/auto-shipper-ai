# Execution Plan — FR-030 Phase 5

**Meta-controller (accepted, no override):** 3 parallel agents + orchestrator, chunks of 8, deliberative cognition mode.
**Implementation plan:** 24 tasks across 6 layer buckets (§4.1 through §4.7).
**Branch:** `feat/RAT-53-shopify-dev-store-stripe-test-mode` (committed).

## Mandatory constraints (postmortem-derived)

Embed these verbatim into every Round 1/2/3 agent prompt that touches the relevant code:

| Constraint | Source | Applies to |
|---|---|---|
| **Jackson NullNode guard** — `get()?.asText()` returns `"null"` for `NullNode`. Use `?.let { if (!it.isNull) it.asText() else null }`. | CLAUDE.md #17 / PM-017 | `ShopifyListingAdapter` parsing of `variants[0].inventory_item_id` |
| **WireMock fixtures cite API doc URL** in header comment | PM-014 #111 | Any new/modified Shopify/CJ fixtures in Round 3 |
| **CLAUDE.md #13** — `@Value` on adapter constructor parameters must use `${key:}` syntax (empty default) | CLAUDE.md #13 | `CjSupplierOrderAdapter.devStoreDryRun` param |
| **CLAUDE.md #14** — never use Kotlin `internal` constructor on `@Component`/`@RestController`/`@Repository` | CLAUDE.md #14 | `DevAdminController`, `WebhookArchivalFilter`, all new Spring beans |
| **CLAUDE.md #20** — never `ObjectMapper()`; inject the Spring bean | CLAUDE.md #20 | Any new JSON-handling code |
| **CLAUDE.md #10** — modules with `@Entity` need `kotlin("plugin.jpa")`; catalog already has this | CLAUDE.md #10 | No new entities, only a new field — unaffected |
| **Test every external-API field extraction for JSON null** — CLAUDE.md #17, test-spec T-10 + T-30 are load-bearing | test-spec.md | Round 3 |
| **No `assert(true)`, no fixture-only `payload.contains(...)`, no `// Phase 5:` TODO comments** | PM-017 #266 / skill test quality rules | Round 3 |

## Round 0 — Orchestrator (foundation, no agents)

Trivial new files / SQL / directory scaffolding. Orchestrator does these directly.

- [ ] S-1: `modules/app/src/main/resources/db/migration/V23__platform_listings_inventory_item_id.sql` — ADD COLUMN + index
- [ ] S-2: `docs/fixtures/shopify-dev-store/.gitkeep` — empty
- [ ] S-3: `docs/fixtures/shopify-dev-store/README.md` — purpose, naming convention, PII-redaction policy

## Round 1 — New Kotlin + Gradle files (3 parallel agents)

Each agent creates new files only. Non-overlapping paths → safe in parallel.

**Agent 1A (fulfillment module — webhook archival):**
- N-1: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/WebhookArchivalFilter.kt` — `OncePerRequestFilter` using `CachingRequestWrapper` pattern from `ShopifyHmacVerificationFilter`. Writes body to `${autoshipper.webhook-archival.output-dir}/{YYYY-MM-DD}/{topic-or-path-slug}-{epoch-ms}.json`. Filter chain forwarded regardless of I/O error.
- N-2: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/WebhookArchivalFilterConfig.kt` — `@Configuration` with `FilterRegistrationBean`, URL patterns `/webhooks/shopify/*`, `/webhooks/cj/*`, `setOrder` numerically LESS than `ShopifyHmacVerificationFilter.order`, `@ConditionalOnProperty("autoshipper.webhook-archival.enabled")`.
- N-3 (tests deferred to Round 3): T-42..T-52.

**Agent 1B (catalog module — dev admin endpoint):**
- N-4: `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/DevAdminController.kt` — `@RestController`, `@ConditionalOnProperty("autoshipper.admin.dev-listing-enabled")`, `POST /admin/dev/sku/{id}/list`, HTTP Basic token compare via **constant-time** `MessageDigest.isEqual`, on success calls `skuService.transition(skuId, SkuState.Listed)`, returns 202 Accepted with SKU id. NO `internal` constructor (CLAUDE.md #14).
- N-5 (tests deferred to Round 3): T-32..T-41.

**Agent 1C (build — Gradle key audit task):**
- N-6: Register `tasks.register("devStoreAuditKeys")` in root `build.gradle.kts`. Reads `.env` from project root if present, validates: `STRIPE_SECRET_KEY` starts with `sk_test_` (or is missing/empty → fail with "missing"), `SHOPIFY_API_BASE_URL` ends with `.myshopify.com` (or missing → fail), `SHOPIFY_WEBHOOK_SECRETS` non-blank, `CJ_ACCESS_TOKEN` non-blank. Prints **only last 4 chars** or masked `****xxxx`. Fails with distinct non-zero exit codes for "missing" vs "invalid" (test-spec T-61 decision: pick 2 for missing-env, 1 for invalid-key).
- N-7 (tests deferred to Round 3): T-53..T-61.

Compile check after Round 1: `./gradlew compileKotlin compileTestKotlin`.

## Round 2 — Modifications to existing files (3 parallel agents, non-overlapping)

**Agent 2A (catalog — inventory_item_id persistence wiring):**
- M-1: Add nullable `shopifyInventoryItemId: String?` field to `PlatformListingEntity` (column `shopify_inventory_item_id`, `updatable = true`).
- M-2: Add nullable `inventoryItemId: String?` to `PlatformListingResult` data class in `modules/catalog/src/main/kotlin/.../proxy/platform/PlatformAdapter.kt`.
- M-3: Edit `ShopifyListingAdapter.listSku()` to parse `variants[0].inventory_item_id` from Shopify product-create response. **MUST use the NullNode guard** (`?.let { if (!it.isNull) it.asText() else null }`) — CLAUDE.md #17.
- M-4: Edit `StubPlatformAdapter.listSku()` to return `inventoryItemId = null`.
- M-5: Edit `PlatformListingListener.handleListed()` (or equivalent) to persist `result.inventoryItemId` onto the entity.

**Agent 2B (fulfillment — adapter fixes + CJ dry-run):**
- M-6: Add `resolveInventoryItemId(skuId: UUID): String?` to `PlatformListingResolver` (native SQL against the new column via existing JPA pattern).
- M-7: Refactor `ShopifyInventoryCheckAdapter` — inject `PlatformListingResolver`, in `isAvailable(skuId)`: resolve inventory_item_id first; if null → log WARN + return `false` (preserves conservative default); if present → call Shopify inventory endpoint with the real Shopify id.
- M-8: `CjSupplierOrderAdapter` — add `@Value("\${autoshipper.cj.dev-store-dry-run:false}") private val devStoreDryRun: Boolean` constructor param (CLAUDE.md #13). In `placeOrder()` FIRST LINE: if `devStoreDryRun` then INFO log `[DEV-STORE DRY RUN] would have placed CJ order: skuCode={}, qty={}, orderNumber={}` and return `SupplierOrderResult.success(supplierOrderId = "dry-run-${UUID.randomUUID()}")`. NO HTTP CALL.

**Agent 2C (config + runbook + .env.example):**
- M-9: `modules/app/src/main/resources/application.yml` — add `autoshipper.admin.dev-listing-enabled: false`, `autoshipper.admin.dev-token:` (empty), `autoshipper.webhook-archival.enabled: false`, `autoshipper.webhook-archival.output-dir: docs/fixtures/shopify-dev-store`, `autoshipper.cj.dev-store-dry-run: false`.
- M-10: `.env.example` — add `DEV_ADMIN_TOKEN=`, `AUTOSHIPPER_ADMIN_DEV_LISTING_ENABLED=false`, `AUTOSHIPPER_WEBHOOK_ARCHIVAL_ENABLED=false`, `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=false` — each with a comment linking back to runbook Section 0 / 12.
- M-11: `docs/live-e2e-runbook.md` — insert new `## 0. Pre-flight Key Audit` section at top (before existing Section 1). Include `./gradlew devStoreAuditKeys` step, sandbox-vs-dry-run checklist, sign-off block, last-4 recording.
- M-12: `docs/live-e2e-runbook.md` — append new `## 12. Shopify Dev Store Walkthrough` section with the full procedure per spec §2.5 / BR-8 (Partners signup, custom app + scopes, Stripe test-mode payment config, `.env` population, bootRun, DevAdminController usage, buyer purchase flow `4242 4242 4242 4242`, verification, CJ path selection guidance, post-test cleanup).
- M-13: `docs/live-e2e-runbook.md` Section 10 Known Gaps — mark the ShopifyInventoryCheckAdapter SKU-UUID row as resolved under FR-030.

Compile check after Round 2: `./gradlew compileKotlin compileTestKotlin`.
DB check after Round 2: `./gradlew flywayInfo flywayValidate` (PM-018 prevention item #140).

## Round 3 — Tests (3 parallel agents)

Agents write test files only. All use JUnit 5, MockK, AssertJ (project convention), WireMock for adapter tests.

**Agent 3A (catalog-side tests — T-01 through T-17):**
- T-01..T-04: Flyway V23 migration tests (column exists, nullable, index exists, reversible).
- T-05..T-07: `PlatformListingEntity` field persistence tests.
- T-08..T-14: `ShopifyListingAdapter.listSku()` — variant extraction happy path, absent, JSON null (NullNode guard), empty variants array, wrong type.
- T-15..T-17: `PlatformListingListener` persistence of inventoryItemId — non-null, null, repeated-listing idempotence.

**Agent 3B (fulfillment-side tests — T-18 through T-31 + T-64 through T-68):**
- T-18..T-23: `PlatformListingResolver.resolveInventoryItemId` — found, not-found, multi-row tiebreaker (test-spec §5.3 OQ: pick "latest by created_at"), invalid UUID, null skuId, empty string mapping.
- T-24..T-31: `ShopifyInventoryCheckAdapter.isAvailable` — mapped SKU happy path, unmapped SKU → false, resolver returns empty → false, Shopify 404, Shopify 429, `available: null` (NullNode guard T-30), negative available treated as unavailable.
- T-64..T-68: `CjSupplierOrderAdapter` dry-run — short-circuit behavior, **zero HTTP calls** (WireMock `verify(0, anyRequestedFor(...))`), default-off still hits HTTP, downstream stub id flows to `orders` row, property defaults false.

**Agent 3C (handler + filter + Gradle + env tests — T-32 through T-63):**
- T-32..T-41: `DevAdminController` — 404 when disabled, 401 missing/wrong auth, 202 happy path, UUID validation, repeated-call semantics (test-spec §5.3 OQ: pick 202 both times — idempotent transition acceptable or 409 on duplicate listing; Phase 5 selects one + documents).
- T-42..T-52: `WebhookArchivalFilter` — writes file when enabled, disabled by default (bean absent), IOException does not break chain, downstream still reads body, filter ordering numerically before HMAC filter (T-52).
- T-53..T-61: Gradle `devStoreAuditKeys` — all variants of pass/fail plus output masking (T-59 — assert full keys NEVER in stdout).
- T-62: `.env.example` sanity — no real secrets, all documented keys present.
- T-63: `application.yml` defaults — all new properties read as `false` / empty.

Compile check after Round 3: `./gradlew compileTestKotlin`.

## After Round 3 (orchestrator)

- [ ] Check off implementation-plan.md checkboxes as rounds complete.
- [ ] Run full suite: `./gradlew test`. Triage any regressions.
- [ ] E2E playbook subagent (**fresh session** per skill step 10) — update `docs/e2e-test-playbook.md` with SC-RAT53-01..10 scenarios from test-spec.md §4, then execute where automatable. Manual dev-store scenarios (SC-03..10) are documented-only for operator execution.
- [ ] Write `summary.md`.
- [ ] Validate: `validate-phase.py --phase 5 --check-deliverables`.

## No override

Meta-controller recommendation (3 agents, chunks of 8, deliberative) is accepted per user confirmation. No `override-justification.md` written.
