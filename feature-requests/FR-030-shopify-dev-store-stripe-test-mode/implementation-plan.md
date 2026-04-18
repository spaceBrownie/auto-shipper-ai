# FR-030 Implementation Plan — Shopify Dev Store + Stripe Test Mode

**Linear:** RAT-53
**Branch:** `feat/RAT-53-shopify-dev-store-stripe-test-mode`
**Phase:** 3 (Implementation Planning)
**Predecessor:** FR-029 (RAT-42)
**Scope ratio:** ~80% operational/runbook, ~20% code (deliberately lopsided per spec §1)

---

## Technical Design

### 1.1 System shape

FR-030 is a **validation gate**, not a feature. Its goal is the first safe, reproducible, auditable traversal of the order pipeline built in FR-029. The shape of the work:

| Category | Examples | % |
|---|---|---|
| **Ops / setup** | Shopify dev store, custom app, Stripe test mode, ngrok, `.env` | ~50% |
| **Runbook authoring** | Section 0 pre-flight audit, dev-store walkthrough section | ~30% |
| **Code (narrow guardrails + known gap)** | Inventory adapter fix, webhook archival filter, gated admin endpoint, Gradle audit task | ~20% |

### 1.2 Data flow diagram — dummy-card purchase through CJ attempt

```
[Human buyer]
   |
   v
[Shopify dev-store hosted storefront]
   -- add to cart, checkout
   -- pays with test card 4242 4242 4242 4242
   -- Stripe test-mode authorize/capture
   |
   v
[Shopify: Order created]
   -- orders/create webhook
   -- HTTP POST -> ngrok -> localhost:8080
   |
   v
[WebhookArchivalFilter (NEW, gated)] ---> docs/fixtures/shopify-dev-store/{date}/orders-create-{ts}.json
   |
   v
[ShopifyHmacVerificationFilter] (existing)
   |
   v
[ShopifyWebhookController] (existing)
   -- persist WebhookEvent (dedup)
   -- publish ShopifyOrderReceivedEvent (AFTER_COMMIT + @Async)
   |  (HTTP 200 returned to Shopify within 5s <-- NFR-1)
   v
[ShopifyOrderProcessingService + LineItemOrderCreator] (existing)
   -- resolve via platform_listings
   -- check inventory via ShopifyInventoryCheckAdapter  <-- FIX: uses persisted inventory_item_id
   -- create Order rows (CONFIRMED)
   -- publish OrderConfirmed
   |
   v
[SupplierOrderPlacementListener -> SupplierOrderPlacementService] (existing)
   -- CjSupplierOrderAdapter.placeOrder() -> CJ live API (real supplier order)
   -- sandbox path: real CJ API call against sandbox account (no real supplier order)
   -- dry-run path: [DEV-STORE DRY RUN] log line + stub SupplierOrderResult when autoshipper.cj.dev-store-dry-run=true
   -- error path: logged failure_reason (sandbox returned non-2xx)
```

Ops-only: dev store creation, app install, Stripe test config, ngrok tunnel, `.env` population, buyer click-through.
Code: the inline NEW components above + the Gradle key-audit task.

### 1.3 Module impact summary

| Module | Change type |
|---|---|
| `catalog` | Schema + entity field (OQ-1b) |
| `fulfillment` | Adapter fix + webhook archival filter + CJ dry-run kill-switch (OQ-1b, OQ-3, BR-6b) |
| `app` / build | Gradle key-audit task (OQ-4) |
| `docs` | Runbook Section 0 + dev-store walkthrough |

No changes to: `pricing`, `vendor`, `capital`, `portfolio`, `compliance`, `shared`.

---

## Architecture Decisions

### AD-1 (OQ-1): Close ShopifyInventoryCheckAdapter gap via persisted `inventory_item_id` — Option (b)

**Decision:** Add a nullable `shopify_inventory_item_id` column to `platform_listings`. `ShopifyListingAdapter.listSku()` is already reading `product.variants[0]` from the Shopify response — extend it to also read `variants[0].inventory_item_id` and return it in `PlatformListingResult`. `PlatformListingListener` persists it on the `PlatformListingEntity`. `ShopifyInventoryCheckAdapter` is refactored to accept an internal SKU UUID, look up the persisted `inventory_item_id` via a new cross-module resolver, and call the Shopify inventory API with the **real** Shopify ID.

**Rationale:**
- Verified against `ShopifyListingAdapter.kt` line 65–68: the response parser already walks into `product.variants[0]`. Shopify's `POST /admin/api/2024-01/products.json` response includes `inventory_item_id` per variant (Shopify Admin REST API, stable field). Zero new API calls.
- Schema migration is additive (nullable column, no backfill required for gate-zero — no prod data).
- Type-safe: no runtime "guess what this UUID maps to" — we persist the mapping the one time we know it (product creation).
- Works both for the dev-store run **and** for future production. Does not leave a feature-flagged code path.

**Alternatives rejected:**
- **(a) Feature-flag off for dev store.** Leaves a skipped code path through the dev-store gate, which defeats the purpose of gate-zero (exercising the real path). Rejected.
- **(c) Runtime variant lookup per order.** Extra Shopify API call per line item per order → 40/s rate-limit pressure under load, and a failure mode that is much harder to diagnose from logs. Rejected.

**Trade-off:** Adds a Flyway migration (V23) and touches two modules (`catalog` entity + `fulfillment` adapter). Acceptable — both changes are small and aligned with FR-020's established pattern.

### AD-2 (OQ-2): Gated admin endpoint for automated listing trigger

**Decision:** Add `POST /admin/dev/sku/{id}/list` in a new controller under the catalog module (`DevAdminController`). Gated by **both** `@ConditionalOnProperty(name = "autoshipper.admin.dev-listing-enabled", havingValue = "true")` **and** HTTP Basic auth against a shared secret read from `${autoshipper.admin.dev-token:}`. Both must pass.

- Property defaults to `false` (bean not instantiated in prod).
- Token defaults to empty string; endpoint rejects all requests if token is blank (defense in depth).
- Endpoint body: drives `skuService.transition(skuId, SkuState.Listed)`, which publishes `SkuStateChanged` → `PlatformListingListener` handles the Shopify POST.

**Rationale:**
- Existing `SkuController.transition` at `POST /api/skus/{id}/state` would functionally work, but it is unauthenticated in this codebase. Reusing it during dev-store testing would normalize calling an unauthenticated state-transition API from operator scripts — a pattern we don't want baked in.
- A separate, explicitly-gated dev endpoint keeps the "dev only" intent auditable in the code and in git history.
- HTTP Basic over HTTPS (ngrok TLS) is sufficient for gate-zero; no session state needed.
- Spec §2.3 mandates "gated behind a config property defaulting to disabled" — this satisfies it with both property and token gates.

**Alternative rejected:** Unauthenticated `SkuController.transition` via localhost-only — would require operator to SSH-tunnel and is harder to reproduce in the runbook. Rejected.

**Trade-off:** One new controller + two new properties. ~40 LOC. Deletable if a proper admin auth layer lands later.

### AD-3 (OQ-3): Webhook payload archival via Spring servlet filter

**Decision:** Add `WebhookArchivalFilter` registered via `FilterRegistrationBean` on URL patterns `/webhooks/shopify/*` and `/webhooks/cj/*`. Writes the raw request body to `${autoshipper.webhook-archival.output-dir:docs/fixtures/shopify-dev-store}/{YYYY-MM-DD}/{topic-or-path}-{epoch-ms}.json`. Guarded by `${autoshipper.webhook-archival.enabled:false}`.

**Rationale:**
- Spec §2.6 / BR-9 mandates archival. Manual HAR export (option a) is operator-effort-heavy and was explicitly called out as the weak choice in Phase 2.
- A filter sits ahead of `ShopifyHmacVerificationFilter` in the chain — captures payload even if HMAC fails (valuable for debugging HMAC drift).
- Uses the same `CachingRequestWrapper` pattern as `ShopifyHmacVerificationFilter` to read body without consuming the stream.
- Off by default → zero prod impact. Explicitly flipped on for the dev-store run via `.env`.
- Outputs a directory tree with real payloads → future WireMock fixture regeneration has a ground-truth source (PM-013 prevention).

**Alternative rejected:** Ngrok HAR export (operator-discipline dependent, easy to forget, not audit-valuable). Rejected.

**Trade-off:** ~30 LOC + a test. Small, isolated, deletable later if desired. Net positive.

### AD-3b (BR-6b): CJ dry-run kill-switch in `CjSupplierOrderAdapter`

**Decision:** Add property `autoshipper.cj.dev-store-dry-run: false` read by `CjSupplierOrderAdapter`. When `true`, `placeOrder()` MUST short-circuit **before** the HTTP call:

```kotlin
if (devStoreDryRun) {
    logger.info("[DEV-STORE DRY RUN] would have placed CJ order: skuCode={}, qty={}, orderNumber={}",
        request.productSku, request.quantity, request.orderNumber)
    return SupplierOrderResult.success(supplierOrderId = "dry-run-${UUID.randomUUID()}")
}
```

**Rationale:**
- Spec BR-3a establishes that the preferred safe path is a CJ **sandbox account** (real API surface, no real supplier order). The dry-run flag is a defense-in-depth fallback when sandbox is not yet provisioned (CJ agent application is non-self-serve and irreversible; cannot be assumed available for every operator at FR-030 execution time).
- Unconditional short-circuit inside the adapter guarantees no HTTP request leaves the process, regardless of which CJ token is populated in `.env`.
- The stub `SupplierOrderResult.success` preserves the downstream state-machine path (order → `CONFIRMED` → fulfillment listener), so the dry-run still exercises the post-CJ code path that would otherwise be skipped if the short-circuit returned `failure`. This is what makes it a useful test scaffold, not just a guard.
- INFO-level log with explicit `[DEV-STORE DRY RUN]` marker is assertable in SC-RAT53-08 and makes audit review unambiguous.

**Alternative rejected:**
- **Rely solely on sandbox account.** Requires CJ agent approval which is out of operator control; if the RAT-53 run date is fixed (it is — urgent), the operator may not have a sandbox account in time. A code-level kill-switch guarantees the gate-zero bar is reachable today.
- **Throw an exception in `placeOrder()` when dry-run is on.** Would prevent `OrderConfirmed` downstream flow, defeating the purpose of exercising the full pipeline. Rejected.

**Trade-off:** One new property + one short-circuit branch in `CjSupplierOrderAdapter` + logging + a test that asserts no HTTP call is made. ~15 LOC. Deletable once CJ sandbox is universally available.

### AD-4 (OQ-4): Gradle key-audit task `devStoreAuditKeys`

**Decision:** Add a Gradle task in the root `build.gradle.kts` that reads `.env` (if present) and echoes the last-4 of every key-like property, and **fails** if any of:
- `STRIPE_SECRET_KEY` is set and does not start with `sk_test_`
- `SHOPIFY_API_BASE_URL` is set and does not end with `.myshopify.com`
- `SHOPIFY_WEBHOOK_SECRETS` is blank
- `CJ_ACCESS_TOKEN` is blank
- Any value is missing that the dev-store runbook requires

Task output is a single-screen summary for the operator to paste into the pre-flight audit checklist. Does not print full key values, only prefixes and last-4.

**Rationale:**
- Spec §2.5 / BR-7 requires the runbook Section 0 checklist. A Gradle task makes the checklist **mechanical** — operator types one command, gets a pass/fail.
- `sk_live_` vs `sk_test_` is the single most dangerous key-mode confusion; a shell task catches it deterministically.
- Zero runtime impact. Lives only in the build.
- Paper-checklist alternative was rejected in Phase 2 because "checklist fatigue is real."

**Alternative rejected:** Runbook-only checklist. Phase 2 decision already signalled that the Gradle task is the preferred option. Rejected as primary mechanism (kept as fallback in runbook wording).

**Trade-off:** Gradle task is ~50 LOC. Negligible.

---

## Layer-by-Layer Implementation

### 3.1 `catalog` module — persistence schema change (AD-1)

**Files touched:**
- `modules/app/src/main/resources/db/migration/V23__platform_listings_inventory_item_id.sql` (NEW)
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/persistence/PlatformListingEntity.kt` (EDIT: add nullable `shopifyInventoryItemId: String?` column)
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/PlatformAdapter.kt` (EDIT: `PlatformListingResult` gains nullable `inventoryItemId: String?`)
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyListingAdapter.kt` (EDIT: parse `variants[0].inventory_item_id` from Shopify response and return it)
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/StubPlatformAdapter.kt` (EDIT: return `null` for `inventoryItemId`)
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/PlatformListingListener.kt` (EDIT: persist `result.inventoryItemId` onto the entity)

Migration:
```sql
ALTER TABLE platform_listings
    ADD COLUMN shopify_inventory_item_id VARCHAR(64);
CREATE INDEX idx_platform_listings_inventory_item
    ON platform_listings(shopify_inventory_item_id);
```

### 3.2 `fulfillment` module — inventory adapter fix (AD-1)

**Files touched:**
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/PlatformListingResolver.kt` (EDIT: new method `resolveInventoryItemId(skuId: UUID): String?` — native query against `platform_listings.shopify_inventory_item_id`)
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/inventory/InventoryCheckAdapter.kt` (EDIT: `ShopifyInventoryCheckAdapter` takes `PlatformListingResolver` in constructor; `isAvailable(skuId)` resolves `inventory_item_id` first; if null, log warning + return `false` — conservative default, spec-aligned; if present, call `/admin/api/2024-01/inventory_levels.json?inventory_item_ids={real_id}`)

Rationale for "return false when unmapped": currently the adapter always returns false (all SKUs mismatched). Preserving that failure semantics for unmapped SKUs is safer than the alternative of returning true, which could accept orders for SKUs that actually are out of stock at Shopify.

### 3.3 `fulfillment` module — WebhookArchivalFilter (AD-3)

**Files touched:**
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/WebhookArchivalFilter.kt` (NEW)
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/WebhookArchivalFilterConfig.kt` (NEW — `FilterRegistrationBean`, `@ConditionalOnProperty("autoshipper.webhook-archival.enabled")`, order **before** HMAC filter)
- `modules/app/src/main/resources/application.yml` (EDIT: add `autoshipper.webhook-archival.enabled: false` default, `output-dir: docs/fixtures/shopify-dev-store`)

The filter uses the existing `CachingRequestWrapper` so downstream filters (HMAC, controller) can still read the body.

### 3.3b `fulfillment` module — CJ dry-run kill-switch (AD-3b)

**Files touched:**
- `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjSupplierOrderAdapter.kt` (EDIT: constructor inject `@Value("\${autoshipper.cj.dev-store-dry-run:false}") val devStoreDryRun: Boolean`; add short-circuit at top of `placeOrder()`)
- `modules/app/src/main/resources/application.yml` (EDIT: add `autoshipper.cj.dev-store-dry-run: false` default)
- `.env.example` (EDIT: add `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=false` with comment pointing to runbook)

### 3.4 `catalog` module — gated dev admin endpoint (AD-2)

**Files touched:**
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/DevAdminController.kt` (NEW — `@RestController`, `@ConditionalOnProperty("autoshipper.admin.dev-listing-enabled")`, `POST /admin/dev/sku/{id}/list` — parses `Authorization: Basic` header, compares token, on match calls `skuService.transition(skuId, SkuState.Listed)`)
- `modules/app/src/main/resources/application.yml` (EDIT: `autoshipper.admin.dev-listing-enabled: false`, `dev-token:` empty)
- `.env.example` (EDIT: add documented `DEV_ADMIN_TOKEN=` line with note)

### 3.5 `build` — Gradle key audit task (AD-4)

**Files touched:**
- `build.gradle.kts` (root, EDIT: register `tasks.register("devStoreAuditKeys")` task that reads `.env`, validates key prefixes, prints last-4, fails on violation)
- `gradle/dev-store-audit.gradle.kts` (OPTIONAL NEW file, included from root if we want to keep root build file clean — TBD in execution)

### 3.6 `docs` — runbook extensions + fixture directory

**Files touched:**
- `docs/live-e2e-runbook.md` (EDIT: insert new Section 0 "Pre-flight key audit" before existing Section 1; add new Section 12 "Shopify dev store walkthrough" with full walkthrough, cross-linked from Section 1)
- `docs/fixtures/shopify-dev-store/README.md` (NEW — explains directory purpose, naming convention, policy: committed as fixtures when run succeeds; redact any PII)
- `docs/fixtures/shopify-dev-store/.gitkeep` (NEW — keep directory under version control)
- `.env.example` (EDIT: all new keys documented with comments pointing to runbook sections)

### 3.7 New properties summary

| Property | Default | Purpose |
|---|---|---|
| `autoshipper.admin.dev-listing-enabled` | `false` | Controller bean gate |
| `autoshipper.admin.dev-token` | `` (empty) | Shared secret for HTTP Basic |
| `autoshipper.webhook-archival.enabled` | `false` | Filter registration gate |
| `autoshipper.webhook-archival.output-dir` | `docs/fixtures/shopify-dev-store` | Where to write archives |
| `autoshipper.cj.dev-store-dry-run` | `false` | Short-circuit `CjSupplierOrderAdapter.placeOrder()` — no HTTP call, logs `[DEV-STORE DRY RUN]`, returns stub success |

---

## Task Breakdown

### 4.1 Catalog module — schema + entity + adapter response (AD-1)

- [ ] Write Flyway migration `V23__platform_listings_inventory_item_id.sql` (ADD COLUMN + index)
- [ ] Add `shopifyInventoryItemId: String?` field to `PlatformListingEntity` (nullable, updatable=true to allow backfill)
- [ ] Add `inventoryItemId: String?` to `PlatformListingResult` data class in `PlatformAdapter.kt`
- [ ] Edit `ShopifyListingAdapter.listSku()` to parse `variants[0].inventory_item_id` from Shopify response and include in returned `PlatformListingResult`
- [ ] Edit `StubPlatformAdapter.listSku()` to return `inventoryItemId = null` (stub only — not used in dev-store run)
- [ ] Edit `PlatformListingListener.handleListed()` to persist `result.inventoryItemId` onto the new entity column

### 4.2 Fulfillment module — adapter fix (AD-1)

- [ ] Add `resolveInventoryItemId(skuId: UUID): String?` method to `PlatformListingResolver` (native SQL against new column)
- [ ] Refactor `ShopifyInventoryCheckAdapter.isAvailable()` to resolve `inventory_item_id` via `PlatformListingResolver` before calling Shopify
- [ ] Inject `PlatformListingResolver` into `ShopifyInventoryCheckAdapter` constructor
- [ ] Add log warning for unmapped SKU path (`return false`); document behaviour in class KDoc

### 4.3 Fulfillment module — webhook archival (AD-3)

- [ ] Create `WebhookArchivalFilter.kt` — writes request body to disk, uses `CachingRequestWrapper`, forwards chain unchanged
- [ ] Create `WebhookArchivalFilterConfig.kt` — `FilterRegistrationBean` with order **before** `ShopifyHmacVerificationFilter`, URL patterns `/webhooks/shopify/*`, `/webhooks/cj/*`, `@ConditionalOnProperty("autoshipper.webhook-archival.enabled")`
- [ ] Add `autoshipper.webhook-archival.enabled: false` and `autoshipper.webhook-archival.output-dir: docs/fixtures/shopify-dev-store` defaults to `application.yml`

### 4.3b Fulfillment module — CJ dry-run kill-switch (AD-3b, BR-6b)

- [ ] Add `@Value("\${autoshipper.cj.dev-store-dry-run:false}") devStoreDryRun: Boolean` constructor parameter to `CjSupplierOrderAdapter` (CLAUDE.md #13: empty default)
- [ ] Add short-circuit at top of `placeOrder()` — INFO log `[DEV-STORE DRY RUN] would have placed CJ order: skuCode=... qty=... orderNumber=...`, return `SupplierOrderResult.success(supplierOrderId = "dry-run-${UUID.randomUUID()}")` — MUST NOT make any HTTP call
- [ ] Add `autoshipper.cj.dev-store-dry-run: false` default to `application.yml`
- [ ] Add `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=false` to `.env.example` with comment linking to runbook sandbox vs dry-run guidance

### 4.4 Catalog module — dev admin endpoint (AD-2)

- [ ] Create `DevAdminController.kt` — `@RestController @ConditionalOnProperty("autoshipper.admin.dev-listing-enabled")`, `POST /admin/dev/sku/{id}/list`, HTTP Basic auth check, calls `skuService.transition(skuId, SkuState.Listed)`
- [ ] Add `autoshipper.admin.dev-listing-enabled: false` and `autoshipper.admin.dev-token:` defaults to `application.yml`

### 4.5 Build — Gradle audit task (AD-4)

- [ ] Register `devStoreAuditKeys` task in `build.gradle.kts` — reads `.env`, validates prefixes, prints last-4, fails on violation

### 4.6 Docs + runbook (BR-7, BR-8, BR-9)

- [ ] Create `docs/fixtures/shopify-dev-store/` directory with `.gitkeep` and `README.md`
- [ ] Insert new `## 0. Pre-flight Key Audit` section at top of `docs/live-e2e-runbook.md` — includes `./gradlew devStoreAuditKeys` step, checklist to sign off, last-4 recording
- [ ] Add new `## 12. Shopify Dev Store Walkthrough` section to `docs/live-e2e-runbook.md` — covers Partners signup, custom app + scopes, Stripe test-mode payment-provider config, `.env` population with test keys, starting `bootRun`, enabling `autoshipper.admin.dev-listing-enabled` for the listing step, enabling `autoshipper.webhook-archival.enabled` for the webhook step, buyer purchase with `4242 4242 4242 4242`, verification, CJ-order cancellation cleanup
- [ ] Cross-link Section 12 from existing Section 1 "Overview" and from Section 10 "Known Gaps" (the inventory-check gap entry marks as resolved)
- [ ] Update `.env.example` with new keys (`DEV_ADMIN_TOKEN`, `AUTOSHIPPER_ADMIN_DEV_LISTING_ENABLED`, `AUTOSHIPPER_WEBHOOK_ARCHIVAL_ENABLED`) all commented/documented

### 4.7 Housekeeping

- [ ] Update `docs/live-e2e-runbook.md` Section 10 Known Gaps — mark the inventory-check row as fixed under the dev-store walkthrough
- [ ] Verify `.gitignore` still excludes `.env` (spot-check, no expected change)

**Total tasks:** 24 (4 added for CJ dry-run kill-switch)

---

## Testing Strategy

### 5.1 Unit tests

| Target | Test |
|---|---|
| `PlatformListingEntity` | Persist + retrieve `shopifyInventoryItemId` field (null and non-null cases) |
| `ShopifyListingAdapter.listSku()` | WireMock stub returns variant with `inventory_item_id` → assert `PlatformListingResult.inventoryItemId` is populated; separate case: response without `inventory_item_id` → null |
| `PlatformListingListener.handleListed()` | Mocked `PlatformAdapter` returns non-null `inventoryItemId` → assert entity saved with value; null case persists null |
| `ShopifyInventoryCheckAdapter.isAvailable()` | (a) mapped SKU → resolver returns real id → Shopify API called with real id → happy path; (b) unmapped SKU → resolver returns null → method returns false, Shopify API never called |
| `WebhookArchivalFilter` | Writes a file with the body bytes when enabled; disabled case — filter never registered, no file write; verifies body downstream still readable |
| `WebhookArchivalFilterConfig` | Bean not registered when property `autoshipper.webhook-archival.enabled=false` (default) |
| `DevAdminController` | (a) property disabled → bean absent → 404; (b) property enabled + missing auth → 401; (c) enabled + wrong token → 401; (d) enabled + correct token → 202 + state transition happens |
| Gradle `devStoreAuditKeys` | Unit-test-equivalent: run task with crafted `.env` variants — `sk_live_` fails, `sk_test_` passes, blank `SHOPIFY_WEBHOOK_SECRETS` fails, missing vars fail |
| `CjSupplierOrderAdapter` dry-run | `dev-store-dry-run=true` → `placeOrder()` returns stub success with `dry-run-{uuid}` id, INFO log contains `[DEV-STORE DRY RUN]`, **no HTTP call** (WireMock verify zero requests); `dev-store-dry-run=false` (default) → normal HTTP path |

### 5.2 Integration tests

- Spring Boot test: `@SpringBootTest` with `autoshipper.admin.dev-listing-enabled=true` + seeded SKU in `StressTested` state → POST `/admin/dev/sku/{id}/list` with basic auth → assert `platform_listings` row exists with non-null `shopify_inventory_item_id` (after mocked Shopify response).
- `ShopifyWebhookController` integration test with `autoshipper.webhook-archival.enabled=true` → POST valid `orders/create` → archived file exists under configured output dir → body bytes match payload → `Order` row created with `status=CONFIRMED`.
- WireMock `orders/create` integration: seeded `platform_listings` with populated `shopify_inventory_item_id` → webhook arrives → `LineItemOrderCreator` resolves → `ShopifyInventoryCheckAdapter` queries Shopify inventory endpoint with the real id (assert WireMock request URL contains real id, not UUID) → `Order` row created and CJ call attempted.

### 5.3 End-to-end (the runbook itself)

The dev-store walkthrough **is** the E2E test. Executed manually once; the archived webhook payloads produced by `WebhookArchivalFilter` become the next round of regression fixtures.

Acceptance per spec §7:
- Dev store live (HTTP 200 on storefront)
- App boots clean
- Pre-flight audit passes (`./gradlew devStoreAuditKeys`)
- Product listed via automated pipeline (admin endpoint → `PlatformListingListener` → Shopify product visible)
- Buyer completes purchase with `4242 4242 4242 4242`
- `orders/create` webhook arrives within 5s (NFR-1), archived to disk
- `Order` row `CONFIRMED`, `channel_order_id` matches
- CJ order placement attempted (success or logged failure — both pass)
- Shopify fulfillment sync OR explicit block reason logged
- Archived payloads committed to git

---

## Rollout Plan

### 6.1 Dev-store setup sequence

1. Shopify Partners → create development store → note `*.myshopify.com` subdomain
2. Shopify dev-store admin → enable hosted storefront → add a minimal test product manually (NOT the pipeline test product)
3. Shopify dev-store admin → install Custom App → scopes `write_products, write_orders, read_orders, write_fulfillments, read_fulfillments` → note `shpat_…` token
4. Shopify dev-store admin → Settings → Payments → configure Stripe test mode with `pk_test_…` / `sk_test_…`
5. Register `orders/create` webhook pointing to ngrok URL → note signing secret
6. CJ: choose ONE safe path — (a) ensure `CJ_ACCESS_TOKEN` is a **sandbox account** token (apply via CJ agent ahead of time, BR-3a) and leave `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=false`; OR (b) if no sandbox available, use any CJ token and set `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=true`. The runbook's Section 0 audit MUST verify this combination.
7. Populate `.env` (never commit) — Shopify token, webhook secret, Stripe test keys, CJ creds, `DEV_ADMIN_TOKEN`, `AUTOSHIPPER_ADMIN_DEV_LISTING_ENABLED=true`, `AUTOSHIPPER_WEBHOOK_ARCHIVAL_ENABLED=true`, `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN` per step 6
7. `./gradlew devStoreAuditKeys` → green
8. `docker compose up -d` → `./gradlew flywayMigrate` → `./gradlew bootRun`
9. `ngrok http 8080` → update webhook URL in Shopify admin
10. `curl -u admin:$DEV_ADMIN_TOKEN -X POST localhost:8080/admin/dev/sku/{id}/list` → SKU → `LISTED` → Shopify product created → `platform_listings` row populated
11. Browser: buyer flow, `4242 4242 4242 4242`, checkout completes
12. Verify DB rows + archived payloads under `docs/fixtures/shopify-dev-store/{date}/`
13. Commit archived payloads (after redaction check)

### 6.2 Rollback

- No prod deploy — gate-zero only.
- If dev-store run fails: archive `.env` (encrypted), delete dev store in Partners, file bug, iterate.
- If migration V23 ever needs rollback: column is nullable and additive; drop column is safe (no backfill logic, no downstream consumers outside of this FR's code path).
- All new code paths are feature-flagged off by default in `application.yml` — merging to main without running the runbook is safe.

### 6.3 No production deploy in this FR

Explicitly: the merge of FR-030 does **not** enable any runtime behaviour change in production. All new properties default to off. The dev-store walkthrough is a one-time operator execution, not a recurring automated job.

---

## 7. Open risks surfaced during Phase 3

(not in spec §8, discovered while reading code)

1. **`PlatformListingEntity.externalVariantId` is `updatable = false`.** The new `shopifyInventoryItemId` column should be `updatable = true` (nullable) to leave room for a future backfill job for pre-V23 listings. Dev store has none today, but prod (when it exists) will.
2. **`ShopifyInventoryCheckAdapter` currently has no unit test.** Adding one as part of this FR covers the previously-untested "always fails" behaviour and the new "resolve-then-call" path — net positive.
3. **`FilterRegistrationBean` order matters.** `WebhookArchivalFilter` must register with a lower `setOrder()` than `ShopifyHmacVerificationFilter` so archival happens before HMAC rejection. Must verify the existing HMAC filter's order in `ShopifyWebhookFilterConfig` and set archival's order one less.
4. **`.env` file used by Gradle task.** The Gradle task must not print the `.env` file itself nor the full secret values — only last-4 and prefix. Risk: operator runs task with screen-share / pastes output into a ticket. The task's logging discipline is part of the AD-4 task.
5. **CJ order cancellation during cleanup.** Spec notes CJ has no test mode; the dev-store run will place a real CJ order. Runbook Section 12 must document the CJ cancellation procedure (via CJ dashboard) with a screenshot placeholder.
