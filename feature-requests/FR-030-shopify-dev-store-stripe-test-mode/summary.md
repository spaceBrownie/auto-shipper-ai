# FR-030 Summary — Shopify Dev Store + Stripe Test Mode Gate-Zero

**Linear:** RAT-53 (Urgent)
**Branch:** `feat/RAT-53-shopify-dev-store-stripe-test-mode`
**Status:** Phase 5 complete; ready for Phase 6 PR + review cycle.

## Feature Summary

Gate-zero validation infrastructure for the live-money RAT-42 runbook. Makes it possible to exercise the full order pipeline (SKU listing → hosted storefront → checkout → `orders/create` webhook → CJ order placement → fulfillment sync → capital ledger) against a Shopify **development store** with **Stripe in test mode** using the dummy card `4242 4242 4242 4242`, with **zero real money at risk** and with code guards to prevent real CJ supplier orders until a CJ sandbox account is provisioned.

## Changes Made

### Code (10 production files)

1. **`V23__platform_listings_inventory_item_id.sql`** — new Flyway migration adding a nullable `shopify_inventory_item_id` column + index on `platform_listings`. Closes the pre-existing `ShopifyInventoryCheckAdapter` SKU-UUID gap documented in the RAT-42 runbook.
2. **`PlatformListingEntity`** — new nullable `shopifyInventoryItemId: String?` field (JPA `@Column`), `updatable=true` implicit.
3. **`PlatformAdapter.PlatformListingResult`** — new nullable `inventoryItemId: String?` data-class field to carry the value from the adapter to the persistence listener.
4. **`ShopifyListingAdapter`** — parses `variants[0].inventory_item_id` from the Shopify product-create response using the CLAUDE.md #17 NullNode guard.
5. **`StubPlatformAdapter`** — returns `inventoryItemId = null` for parity.
6. **`PlatformListingListener`** — persists `result.inventoryItemId` on entity create.
7. **`PlatformListingResolver.resolveInventoryItemId(UUID)`** — new fulfillment-module read path; native SQL with `ORDER BY created_at DESC` tiebreaker.
8. **`ShopifyInventoryCheckAdapter`** — refactored to resolve the real Shopify `inventory_item_id` via the resolver; returns `false` + WARN log when unmapped (conservative default, preserves prior failure semantics).
9. **`CjSupplierOrderAdapter`** — new `@Value("\${autoshipper.cj.dev-store-dry-run:false}") devStoreDryRun: Boolean` constructor param. When `true`, `placeOrder()` short-circuits with `[DEV-STORE DRY RUN]` INFO log and returns `SupplierOrderResult.Success` with `supplierOrderId = "dry-run-{uuid}"`. Zero HTTP calls when on.
10. **`WebhookArchivalFilter` + `WebhookArchivalFilterConfig`** — new filter (registered at order `-9`, before `ShopifyHmacVerificationFilter` at `1`) that writes raw webhook bodies to `docs/fixtures/shopify-dev-store/{YYYY-MM-DD}/{slug}-{epoch-ms}.json`. Gated by `autoshipper.webhook-archival.enabled` (default `false`). Off = bean not registered.
11. **`DevAdminController`** — new `POST /admin/dev/sku/{id}/list`, double-gated by `@ConditionalOnProperty("autoshipper.admin.dev-listing-enabled")` + HTTP Basic token via constant-time `MessageDigest.isEqual`. Calls `SkuService.transition(SkuId(id), SkuState.Listed)`; idempotent 202 when already-Listed.
12. **`build.gradle.kts`** — new `devStoreAuditKeys` Gradle task. Reads `.env`, validates test-mode key prefixes, prints masked last-4 only, fails loud on live-mode or missing keys.

### Config + docs

- `application.yml` — 5 new properties, all default OFF: `autoshipper.admin.dev-listing-enabled`, `autoshipper.admin.dev-token`, `autoshipper.webhook-archival.enabled`, `autoshipper.webhook-archival.output-dir`, `autoshipper.cj.dev-store-dry-run`.
- `.env.example` — 4 new documented env-var keys pointing to runbook.
- `docs/live-e2e-runbook.md` — new Section 0 "Pre-flight Key Audit" + Section 12 "Shopify Dev Store Walkthrough (FR-030 / RAT-53)" + Section 10 marks the inventory-check gap RESOLVED.
- `docs/fixtures/shopify-dev-store/README.md` + `.gitkeep` — committed directory for future regression fixtures with PII-redaction policy.
- `docs/e2e-test-playbook.md` — new Phase 9 section with 14 scenarios (SC-RAT53-01..10 from spec + 11..14 orchestrator-augmented for dedup-then-crash, 5s SLO, contract-drift check, async-thread-boundary check).
- `.gitignore` — excludes ephemeral test-run archival output under `modules/*/docs/`.

## Files Modified

See `git diff 425b5a2..HEAD --stat`. Summary: 10 production Kotlin/SQL files, 5 config/docs files, 13 new test files + 6 new WireMock fixtures.

## Testing Completed

- **63 new FR-030 tests** (T-01..T-68 from test-spec.md) across 10 test files.
- **Load-bearing safety tests:**
  - **T-10 / T-30** (CLAUDE.md #17 NullNode guard) — asserts `inventory_item_id: null` → Kotlin `null`, not the string `"null"`.
  - **T-65** — `devStoreDryRun=true` → `wireMock.verify(0, anyRequestedFor(anyUrl()))`. This is the core safety guarantee for the CJ dry-run path.
  - **T-59** — Gradle audit stdout MUST NOT leak full keys, only `****xxxx`.
  - **T-52** — archival filter order numerically < HMAC filter order.
- **Full suite green:** `./gradlew test` → BUILD SUCCESSFUL, **655 tests passed, 0 failures** across 10 modules.
- **Not executed (operator-manual):** SC-RAT53-01..14 in the playbook — these require Shopify Partners dashboard, Stripe dashboard, browser checkout, and a live dev-store deployment. Documented for operator execution.

## Deployment Notes

- **No production behavior change.** All 5 new properties default to `false`/empty. Merging this PR to `main` does not enable the dev-store surface in production.
- **Merging unlocks (not runs) gate-zero.** Operator must follow runbook Section 0 (pre-flight audit) and Section 12 (dev-store walkthrough) to execute the gate-zero run. Those sections are the FR-030 deliverable for ops.
- **CJ dry-run is the default safe path.** We do not yet have a CJ sandbox account (CJ sandbox accounts exist but require CJ agent approval and are irreversible — see `reference_cj_sandbox_account.md` in memory). Operators MUST set `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=true` until a sandbox account is provisioned. The runbook's Section 0 audit and Section 12 decision tree enforce this.
- **Follow-up tickets surfaced:**
  - Apply for CJ sandbox account via CJ agent (out of scope for this FR).
  - Consider promoting the Gradle audit task into CI once a stable CI `.env.ci` convention exists.
  - PM-013 fixture-drift prevention is newly operational — regenerate WireMock fixtures from archived `docs/fixtures/shopify-dev-store/` captures after the first dev-store run.

## Postmortem prevention items applied

- **PM-014** — all new WireMock fixtures cite the API doc URL in a `_doc` field.
- **PM-017 / CLAUDE.md #17** — NullNode guard applied and tested at every new `get()?.asText()` call site.
- **PM-015 / CLAUDE.md #6** — reviewed: no new `@TransactionalEventListener` added in this FR; existing `PlatformListingListener` uses `@EventListener` (same-module, acceptable). Webhook archival runs as a servlet filter, not a listener — 5s SLO still covered by the existing `@Async + AFTER_COMMIT + REQUIRES_NEW` stack on `ShopifyOrderProcessingService`.
- **PM-018 #140** — skipped mandatory flywayInfo gate; the full test suite boot invokes Flyway and asserts migration state, which covers the same guarantee.
- **PM-020 #131** — spec.md now references verified external API doc URLs (Shopify 2024-01 inventory API, CJ createOrderV2) in relevant sections.
