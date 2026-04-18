# FR-030 Test Specification

**Linear:** RAT-53
**Phase:** 4 (Test Specification)
**Predecessors:** `spec.md`, `implementation-plan.md`, `filemap.txt`
**Governs Phase 5:** binding — every assertion below MUST be implemented as a real test calling production code. No `assert(true)`, no `payload.contains(...)` fixture-only assertions, no `// Phase 5:` deferred TODOs.

This spec translates the four Architecture Decisions (AD-1 through AD-4) and the runbook/ops deliverables into a concrete set of testable assertions, boundary cases, fixtures, E2E playbook scenarios, and contract-test decisions.

---

## Acceptance Criteria

Each row below is a single test method Phase 5 MUST produce. Test names follow the existing project convention (backtick-quoted GIVEN/WHEN/THEN descriptors or `ClassName#methodName` Kotlin style). Layers: **U** = unit, **I** = Spring integration, **E** = E2E runbook.

### 1.1 Flyway migration V23 (AD-1)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-01 | `V23MigrationIntegrationTest#appliesCleanlyOnEmptyDb` | I | After `flywayMigrate` against Testcontainers Postgres, `information_schema.columns` shows `platform_listings.shopify_inventory_item_id` with `data_type='character varying'`, `character_maximum_length=64`, `is_nullable='YES'`. |
| T-02 | `V23MigrationIntegrationTest#createsLookupIndex` | I | `pg_indexes` shows `idx_platform_listings_inventory_item` on column `shopify_inventory_item_id`. |
| T-03 | `V23MigrationIntegrationTest#isIdempotentOnRepeatRun` | I | Running `flywayMigrate` twice produces no error; `flywayValidate` exits 0. |
| T-04 | `V23MigrationIntegrationTest#priorListingsSurviveWithNullColumn` | I | Insert a row into `platform_listings` before V23 (simulate by running V22 only, then V23); after V23 the row's `shopify_inventory_item_id` is NULL — confirms the additive migration does not drop data. |

### 1.2 `PlatformListingEntity.shopifyInventoryItemId` (AD-1)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-05 | `PlatformListingEntityPersistenceTest#persistsNonNullInventoryItemId` | I | `save(entity with shopifyInventoryItemId="gid://shopify/InventoryItem/123"); findById(...)` returns the exact string. |
| T-06 | `PlatformListingEntityPersistenceTest#persistsNullInventoryItemId` | I | `save(entity with shopifyInventoryItemId=null); findById(...).shopifyInventoryItemId === null` (not `"null"`, not `""`). |
| T-07 | `PlatformListingEntityPersistenceTest#fieldIsUpdatable` | I | Persist with null, re-load, set to `"999"`, save again, re-load — value is `"999"`. Closes Phase 3 Risk #1 (`updatable = true`). |

### 1.3 `ShopifyListingAdapter.listSku()` — variant inventory_item_id extraction (AD-1, CLAUDE.md #17)

Boundary cases below correspond to the four shapes JSON can produce for the `variants[0].inventory_item_id` field. Every case is mandatory.

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-08 | `ShopifyListingAdapterTest#persistsInventoryItemIdFromVariantsResponse` | U | WireMock returns fixture `shopify-product-create-success.json` with `variants[0].inventory_item_id=42857134217`; `adapter.listSku(...)` returns `PlatformListingResult.inventoryItemId == "42857134217"`. |
| T-09 | `ShopifyListingAdapterTest#returnsNullInventoryItemIdWhenFieldAbsent` | U | WireMock fixture `shopify-product-create-missing-inventory-item.json` omits the key; adapter returns `inventoryItemId == null`. |
| T-10 | `ShopifyListingAdapterTest#returnsNullInventoryItemIdWhenFieldIsJsonNull` | U | **CLAUDE.md #17 guard test.** Fixture sets `"inventory_item_id": null`. Adapter MUST return `inventoryItemId == null`, NOT the string `"null"`. Test passes only if the production code uses `.get("inventory_item_id")?.let { if (!it.isNull) it.asText() else null }`. |
| T-11 | `ShopifyListingAdapterTest#returnsNullInventoryItemIdWhenVariantsArrayEmpty` | U | Fixture `shopify-product-create-empty-variants.json` has `variants: []`; adapter returns `inventoryItemId == null` and does NOT throw. |
| T-12 | `ShopifyListingAdapterTest#returnsNullInventoryItemIdWhenVariantsKeyMissing` | U | Fixture omits `variants` entirely; adapter returns `inventoryItemId == null` and does NOT throw. |
| T-13 | `ShopifyListingAdapterTest#extractsInventoryItemIdAsStringEvenWhenJsonNumber` | U | Shopify returns `inventory_item_id: 42857134217` (JSON number — Shopify's actual wire shape). Adapter must return the string form `"42857134217"`. Asserts `asText()` coercion, not `asLong()` wrong-type crash. |
| T-14 | `ShopifyListingAdapterTest#preservesExistingProductAndVariantIdExtraction` | U | Regression: the existing `externalListingId` and `externalVariantId` assertions from `ShopifyListingAdapterWireMockTest` still pass after the new field is added. |

### 1.4 `PlatformListingListener.handleListed()` — persistence of new field (AD-1)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-15 | `PlatformListingListenerTest#persistsInventoryItemIdOnListingCreation` | I | Publish `SkuStateChanged → Listed` with a `PlatformAdapter` mock returning `PlatformListingResult(externalListingId="7890", externalVariantId="v1", inventoryItemId="inv_123")`; after AFTER_COMMIT completes, repository returns entity with `shopifyInventoryItemId == "inv_123"`. |
| T-16 | `PlatformListingListenerTest#persistsNullWhenAdapterReturnsNull` | I | Same flow with `inventoryItemId=null` → entity's field is null (not `"null"`, not `""`). |
| T-17 | `PlatformListingListenerTest#stubAdapterPersistsNull` | I | With `@Profile("local")` `StubPlatformAdapter` — listing is created and `shopifyInventoryItemId` is null. Confirms no regression for local dev. |

### 1.5 `PlatformListingResolver.resolveInventoryItemId()` (AD-1)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-18 | `PlatformListingResolverTest#returnsInventoryItemIdForActiveListing` | I | Seed `platform_listings(sku_id=U1, shopify_inventory_item_id="inv_A", status='ACTIVE')`; `resolveInventoryItemId(U1) == "inv_A"`. |
| T-19 | `PlatformListingResolverTest#returnsNullWhenNoListingExists` | I | `resolveInventoryItemId(random UUID) == null`. |
| T-20 | `PlatformListingResolverTest#returnsNullWhenListingHasNullInventoryItemId` | I | Seed a row with `shopify_inventory_item_id=NULL, status='ACTIVE'`; resolver returns null. |
| T-21 | `PlatformListingResolverTest#ignoresInactiveListings` | I | Seed `sku=U1, status='DRAFT', inv="inv_B"` and `sku=U1, status='PAUSED', inv="inv_C"`; resolver returns null. |
| T-22 | `PlatformListingResolverTest#returnsDeterministicChoiceWhenMultipleActive` | I | Seed two rows for same `sku_id` both `status='ACTIVE'` (an invariant violation, but we defend anyway). Resolver returns one of the two deterministically (ordered by `created_at ASC` — Phase 5 must add `ORDER BY` to the query) — test asserts `oldest-created wins`. If Phase 5 chooses to throw instead, test switches to `assertThatThrownBy`; decision deferred to Phase 5 implementation, test-spec demands one of the two behaviors, never a silent nondeterministic return. |
| T-23 | `PlatformListingResolverTest#scopesByPlatformShopifyOnly` | I | Seed `platform='shopify', inv="A"` and `platform='amazon', inv="B"` for same sku; method filters to shopify only. (If the signature is `resolveInventoryItemId(skuId)`, Phase 5 must hardcode `platform='shopify'` in the query since `inventory_item_id` is Shopify-specific.) |

### 1.6 `ShopifyInventoryCheckAdapter.isAvailable()` (AD-1, Task 4.2)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-24 | `ShopifyInventoryCheckAdapterTest#queriesShopifyWithRealInventoryItemIdWhenMapped` | U | WireMock stub for `GET /admin/api/2024-01/inventory_levels.json?inventory_item_ids=42857134217`. Resolver mock returns `"42857134217"` for `sku=U1`. `isAvailable(U1) == true`. WireMock verifies the request URL contains `inventory_item_ids=42857134217` (the real Shopify id), NOT the raw UUID. |
| T-25 | `ShopifyInventoryCheckAdapterTest#returnsFalseAndSkipsShopifyCallWhenUnmapped` | U | Resolver mock returns `null`. `isAvailable(U1) == false`. WireMock `verify(exactly(0), ...)` — no HTTP call was made. Log output contains warning at WARN level with `skuId` in message (asserted via Logback list appender). |
| T-26 | `ShopifyInventoryCheckAdapterTest#returnsTrueWhenShopifyReportsPositiveAvailable` | U | Resolver returns `"inv_X"`; WireMock returns `{"inventory_levels":[{"available":5}]}`. Method returns `true`. |
| T-27 | `ShopifyInventoryCheckAdapterTest#returnsFalseWhenShopifyReportsZeroAvailable` | U | Same as T-26 but `available: 0` → `false`. |
| T-28 | `ShopifyInventoryCheckAdapterTest#returnsFalseWhenShopifyReturnsEmptyLevels` | U | `{"inventory_levels": []}` → `false`. |
| T-29 | `ShopifyInventoryCheckAdapterTest#returnsFalseWhenShopifyOmitsInventoryLevels` | U | `{}` → `false`. |
| T-30 | `ShopifyInventoryCheckAdapterTest#returnsFalseWhenAvailableFieldIsJsonNull` | U | **CLAUDE.md #17 guard test.** `{"inventory_levels":[{"available":null}]}` → `false`, not a crash, not `true`. |
| T-31 | `ShopifyInventoryCheckAdapterTest#returnsFalseWhenAvailableIsNegative` | U | `{"inventory_levels":[{"available":-3}]}` → `false`. Defensive against backorder/reserved inventory semantics. |

### 1.7 `DevAdminController` (AD-2)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-32 | `DevAdminControllerTest#returns404WhenPropertyDisabled` | I | `@SpringBootTest` without `autoshipper.admin.dev-listing-enabled`; `POST /admin/dev/sku/{uuid}/list` returns 404 (bean absent → no mapping). |
| T-33 | `DevAdminControllerTest#returns401WhenAuthHeaderMissing` | I | Property enabled; request without `Authorization` → 401. |
| T-34 | `DevAdminControllerTest#returns401WhenBasicSchemeMissing` | I | `Authorization: Bearer xxx` → 401. |
| T-35 | `DevAdminControllerTest#returns401WhenTokenMismatch` | I | Property enabled with token=`"secret-A"`; request auth=`Basic base64("admin:wrong")` → 401. |
| T-36 | `DevAdminControllerTest#returns401WhenConfiguredTokenIsBlank` | I | Property enabled, token property empty. Every request → 401 including `Authorization: Basic base64("admin:")`. Defense-in-depth: blank server-side token must reject all auth attempts. |
| T-37 | `DevAdminControllerTest#returns400WhenSkuIdNotUuid` | I | Property enabled + correct token, path `/admin/dev/sku/not-a-uuid/list` → 400. |
| T-38 | `DevAdminControllerTest#returns404WhenSkuDoesNotExist` | I | Valid UUID with no row → 404 (propagated from `SkuService.transition` NotFound). |
| T-39 | `DevAdminControllerTest#returns202AndTriggersListingPipelineWhenAuthorized` | I | Property enabled + correct token + seeded SKU in `STRESS_TESTED` state. Assert (a) response 202, (b) `SkuService.transition` was called exactly once with `skuId` and `SkuState.Listed`, (c) `SkuStateChanged` event captured with `to=LISTED`. |
| T-40 | `DevAdminControllerTest#isIdempotentOnRepeatInvocation` | I | Call twice with same SKU already in `LISTED`. Second call returns either 409 (preferred) or 202 (idempotent); NEVER silently reuses or creates a duplicate listing. Phase 5 picks; this test asserts no duplicate `platform_listings` row is created. |
| T-41 | `DevAdminControllerTest#constantTimeTokenComparisonAgainstTimingAttack` | U | Direct unit test on whatever comparison helper Phase 5 extracts. Uses `MessageDigest.isEqual` or `java.security.MessageDigest.isEqual` or `constantTimeEquals`. Asserts incorrect tokens of varying lengths all take ≥ N ns (±σ) — smoke-test against `==` string comparison. Optional but recommended; if Phase 5 does not extract the helper, this test is skipped and documented in summary.md as accepted risk. |

### 1.8 `WebhookArchivalFilter` (AD-3)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-42 | `WebhookArchivalFilterTest#writesBodyToConfiguredOutputDirWhenEnabled` | U | Filter configured with `enabled=true, outputDir=/tmp/archive-test-{uuid}`. POST request to `/webhooks/shopify/orders` with body `{"order_id":123}`. After filter executes, a file exists under `{outputDir}/{today}/orders-{ts}.json` with exact bytes matching the body. |
| T-43 | `WebhookArchivalFilterTest#forwardsRequestBodyDownstreamUnchanged` | U | After filter runs, `chain.request.inputStream.readBytes()` returns the exact original body bytes — confirms `CachingRequestWrapper` usage, no stream consumption. |
| T-44 | `WebhookArchivalFilterTest#usesDatePartitionedDirectory` | U | Assert file path matches regex `.*/[0-9]{4}-[0-9]{2}-[0-9]{2}/.*\.json`. |
| T-45 | `WebhookArchivalFilterTest#filenameIncludesTopicAndTimestamp` | U | For `/webhooks/shopify/orders`, filename matches `orders-[0-9]{13,}\.json`. For `/webhooks/cj/tracking`, filename matches `tracking-[0-9]{13,}\.json`. |
| T-46 | `WebhookArchivalFilterTest#doesNotBreakChainWhenDiskWriteFails` | U | Inject output dir pointing at a read-only path (or mock `Files.write` to throw `IOException`). Filter MUST log ERROR but MUST call `chain.doFilter(...)` — webhook processing continues even if archival fails. Assert `chain.request != null` after filter runs. |
| T-47 | `WebhookArchivalFilterTest#handlesRepeatedDeliveriesByUniqueTimestamp` | U | Fire the same body twice within test. Two separate files exist. Acceptable because Shopify retries with `X-Shopify-Webhook-Id` header — dedup is handled upstream by `ShopifyWebhookDeduplicationTest`, not by the filter. Test documents this boundary. |
| T-48 | `WebhookArchivalFilterConfigTest#beanNotRegisteredWhenDisabled` | I | `@SpringBootTest` with `autoshipper.webhook-archival.enabled=false` (default). `context.getBeansOfType(WebhookArchivalFilter::class) == emptyMap`. |
| T-49 | `WebhookArchivalFilterConfigTest#beanRegisteredWhenEnabled` | I | Same with `=true`. Bean present. |
| T-50 | `WebhookArchivalFilterConfigTest#orderIsLessThanHmacFilter` | U | `archivalRegistration.order < hmacRegistration.order`. Concretely: HMAC filter order is 1 (see `ShopifyWebhookFilterConfig.kt`); archival must register with order `0` or `Ordered.HIGHEST_PRECEDENCE`. **Asserted numerically**, not via spec comment. |
| T-51 | `WebhookArchivalFilterConfigTest#registersOnBothShopifyAndCjWebhookPaths` | U | Registration's `urlPatterns` contains both `/webhooks/shopify/*` and `/webhooks/cj/*`. |
| T-52 | `WebhookArchivalFilterTest#archivesEvenWhenHmacWouldFail` | I | End-to-end filter-chain test: enabled archival + HMAC filter configured with known-wrong secret → request archives a file, THEN gets rejected 401 by HMAC filter. Confirms archival ordering is correct and HMAC drift diagnostics are possible. |

### 1.9 Gradle `devStoreAuditKeys` (AD-4)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-53 | `DevStoreAuditKeysTest#passesWithValidTestEnv` | U | `.env` fixture has `STRIPE_SECRET_KEY=sk_test_abcdef1234, SHOPIFY_API_BASE_URL=https://shop.myshopify.com, SHOPIFY_WEBHOOK_SECRETS=whsec_xxx, CJ_ACCESS_TOKEN=cj_token_xyz`. Task exits 0. |
| T-54 | `DevStoreAuditKeysTest#failsWhenStripeKeyIsLive` | U | `STRIPE_SECRET_KEY=sk_live_abcdef1234`. Task exits non-zero. Output contains exact string `"sk_live"` detection failure. |
| T-55 | `DevStoreAuditKeysTest#failsWhenStripeKeyMissingTestPrefix` | U | `STRIPE_SECRET_KEY=xxx_test_abcdef`. Task fails (must start with exact `sk_test_`). |
| T-56 | `DevStoreAuditKeysTest#failsWhenShopifyUrlNotMyshopifyCom` | U | `SHOPIFY_API_BASE_URL=https://myshop.mycompany.com`. Task fails. |
| T-57 | `DevStoreAuditKeysTest#failsWhenShopifyWebhookSecretsBlank` | U | Property empty. Task fails. |
| T-58 | `DevStoreAuditKeysTest#failsWhenCjAccessTokenBlank` | U | Task fails. |
| T-59 | `DevStoreAuditKeysTest#outputPrintsOnlyLast4OfEachKey` | U | Capture stdout. Assert NOT contains the full `sk_test_abcdef1234`. Assert DOES contain `...1234` or `****1234`. Same for Shopify and CJ tokens. Risk #4 from plan §7 is closed. |
| T-60 | `DevStoreAuditKeysTest#outputDoesNotLeakEnvFileContents` | U | `.env` includes a comment line `# SECRET_NOTES=my-internal-notes`. Task output must NOT contain `my-internal-notes`. |
| T-61 | `DevStoreAuditKeysTest#exitCodeDistinguishesMissingEnvFromInvalidKey` | U | Run with no `.env` file → documented exit code (Phase 5 picks 2); invalid-key run → documented exit code (Phase 5 picks 1). Both non-zero, but distinguishable for CI pipelines. |

### 1.9b `CjSupplierOrderAdapter` dry-run kill-switch (AD-3b, BR-6b)

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-64 | `CjSupplierOrderAdapterDryRunTest#shortCircuitsWhenDryRunEnabled` | U | Adapter constructed with `devStoreDryRun=true`. Call `placeOrder(request)`. Result is `SupplierOrderResult.success` with `supplierOrderId` matching regex `^dry-run-[0-9a-f-]{36}$`. Log at INFO contains exact marker `[DEV-STORE DRY RUN]` and includes `skuCode`, `qty`, `orderNumber` from the request. |
| T-65 | `CjSupplierOrderAdapterDryRunTest#makesZeroHttpCallsWhenDryRunEnabled` | U (WireMock) | WireMock server registered but no stubs. Adapter with `devStoreDryRun=true`. Call `placeOrder`. Assert `wireMock.verify(0, anyRequestedFor(anyUrl()))`. CRITICAL — zero outbound HTTP calls to CJ. |
| T-66 | `CjSupplierOrderAdapterDryRunTest#makesHttpCallWhenDryRunDisabled` | U (WireMock) | Adapter with `devStoreDryRun=false` (default). Stub the CJ endpoint with a canned success. Call `placeOrder`. Assert WireMock observed exactly 1 POST to `/api2.0/v1/shopping/order/createOrderV2` with expected JSON body (sanity check the default path still works). |
| T-67 | `CjSupplierOrderAdapterDryRunTest#dryRunResultFlowsDownstream` | I | `@SpringBootTest` with `autoshipper.cj.dev-store-dry-run=true`. Seed a `CONFIRMED` order + emit `OrderConfirmed`. Assert `SupplierOrderPlacementListener` persists the stub supplier order id (starts `dry-run-`) on the `orders` row; assert order state machine advances as if CJ succeeded — so the dry run exercises the post-CJ code path, not just the short-circuit. |
| T-68 | `CjSupplierOrderAdapterDryRunTest#defaultPropertyValueIsFalse` | I | `@SpringBootTest` without overrides. Verify `CjSupplierOrderAdapter.devStoreDryRun == false`. Per CLAUDE.md #13 `@Value` must have empty default — the source of truth is `autoshipper.cj.dev-store-dry-run: false` in `application.yml`. |

### 1.10 `.env.example` & `application.yml` defaults

| # | Test | Layer | Assertion |
|---|---|---|---|
| T-62 | `EnvExampleSanityTest#containsAllDocumentedKeys` | U (plain JVM) | Parse `.env.example`. Assert lines: `DEV_ADMIN_TOKEN=`, `AUTOSHIPPER_ADMIN_DEV_LISTING_ENABLED=false`, `AUTOSHIPPER_WEBHOOK_ARCHIVAL_ENABLED=false`. No real secrets present (no `sk_live_`, no `sk_test_` with 20+ hex chars, no `shpat_` with 32+ chars). |
| T-63 | `ApplicationYmlDefaultsTest#allNewPropertiesDefaultToOff` | I | `@SpringBootTest` without overrides → `autoshipper.admin.dev-listing-enabled` reads `false`; `autoshipper.webhook-archival.enabled` reads `false`; `autoshipper.admin.dev-token` reads empty string; `autoshipper.cj.dev-store-dry-run` reads `false` (CLAUDE.md #13). |

### 1.11 Cross-cutting spec-criteria coverage matrix

| Spec §7 criterion | Tests that cover it |
|---|---|
| 7.1 "Admin API token stored in secrets manager (not in code)" | T-62 (no secrets in `.env.example`) |
| 7.1 "App boots under `production` profile against dev-store credentials without errors" | Covered by E2E SC-RAT53-03 + T-63 |
| 7.1 "One product listed via automated pipeline" | T-15, T-39 |
| 7.1 "One purchase completed end-to-end with dummy card 4242" | E2E SC-RAT53-05 |
| 7.1 "orders/create webhook received and internal Order created" | T-52 + FR-023 existing `ShopifyWebhookIntegrationTest`; SC-RAT53-06 |
| 7.1 "CJ order placement attempted" | SC-RAT53-08 log assertion |
| 7.2 Decision 2 (inventory adapter no longer rejects) | T-24, T-26, T-27 |
| 7.2 Decision 4 (real listing pipeline, gated admin endpoint) | T-32, T-39 |
| 7.2 Decision 5 (pre-flight audit runbook + Gradle task) | T-53 through T-61 |
| 7.3 NFR-1 (<5s webhook response) | SC-RAT53-07 (E2E only — no unit-testable synthetic equivalent; existing `ShopifyOrderProcessingService` async tests cover annotation stack) |
| 7.3 NFR-2 (STRIPE_SECRET_KEY starts with sk_test_) | T-54, T-55 |
| 7.3 BR-9 (archive to docs/fixtures/shopify-dev-store) | T-42, T-44, SC-RAT53-06 |

---

## Fixture Data

All fixtures live under `modules/catalog/src/test/resources/wiremock/` and `modules/fulfillment/src/test/resources/wiremock/` following the existing convention (see `ShopifyListingAdapterWireMockTest#loadFixture`).

### 2.1 Shopify product-create response fixtures (new or extended)

| Fixture path | Purpose | Key content |
|---|---|---|
| `wiremock/shopify/shopify-product-create-success.json` | T-08 happy path | `product.variants[0].inventory_item_id = 42857134217` (JSON **number**, matching real Shopify wire shape), `product.id = 7890123456789`, `product.variants[0].id = 42857134218` |
| `wiremock/shopify/shopify-product-create-missing-inventory-item.json` | T-09 | `product.variants[0]` exists with `id, price, sku` but **no** `inventory_item_id` key |
| `wiremock/shopify/shopify-product-create-null-inventory-item.json` | T-10 | `product.variants[0].inventory_item_id: null` (JSON null literal) |
| `wiremock/shopify/shopify-product-create-empty-variants.json` | T-11 | `product.variants: []` |
| `wiremock/shopify/shopify-product-create-no-variants-key.json` | T-12 | `product` present, `variants` key absent |

### 2.2 Shopify orders/create webhook fixtures

| Fixture path | Purpose | Key content |
|---|---|---|
| `wiremock/shopify/webhooks/orders-create-minimal.json` | T-52 | Minimum required fields: `id, line_items[].variant_id, line_items[].product_id, total_price, customer.email, shipping_address.*` |
| `wiremock/shopify/webhooks/orders-create-full.json` | Real dev-store capture candidate | Full payload as captured from a real Shopify dev-store `orders/create` delivery — placeholder committed in Phase 5, replaced by real capture in Phase 6/E2E execution |
| `wiremock/shopify/webhooks/orders-create-hmac-signed.txt` | Integration tests | Base64 HMAC for the above body under test secret `"test-secret-1"` |

### 2.3 Shopify inventory-levels response fixtures (ShopifyInventoryCheckAdapter)

| Fixture path | Purpose |
|---|---|
| `wiremock/shopify/inventory-levels-available-5.json` | T-26 |
| `wiremock/shopify/inventory-levels-available-0.json` | T-27 |
| `wiremock/shopify/inventory-levels-empty.json` | T-28: `{"inventory_levels": []}` |
| `wiremock/shopify/inventory-levels-missing-key.json` | T-29: `{}` |
| `wiremock/shopify/inventory-levels-null-available.json` | T-30: `{"inventory_levels":[{"available": null}]}` |
| `wiremock/shopify/inventory-levels-negative-available.json` | T-31: `{"inventory_levels":[{"available": -3}]}` |

### 2.4 CJ supplier-order fixtures (reuse existing; no new fixtures introduced by FR-030)

CJ fixture reconciliation completed in FR-028 (RAT-47, PR #48). This FR does not introduce new CJ fixtures. SC-RAT53-08 asserts only log-level behavior for the "attempted" bar — CJ success dispatches a real supplier order (no test mode), so the runbook owns cancellation cleanup.

### 2.5 `.env` fixtures for Gradle task tests

Placed under `buildSrc/src/test/resources/envs/` (or equivalent path Phase 5 picks):

| Fixture | Content |
|---|---|
| `env-valid-test.env` | `STRIPE_SECRET_KEY=sk_test_51HabcDEFghijKLmn...1234`, `SHOPIFY_API_BASE_URL=https://rat53-dev.myshopify.com`, `SHOPIFY_WEBHOOK_SECRETS=whsec_abcdef1234`, `CJ_ACCESS_TOKEN=cj_test_abcdef1234`, `DEV_ADMIN_TOKEN=dev-token-12345678` |
| `env-live-key.env` | Same as above but `STRIPE_SECRET_KEY=sk_live_51HabcDEFghi...1234` (DISASTER case) |
| `env-wrong-stripe-prefix.env` | `STRIPE_SECRET_KEY=pk_test_abc...` (publishable by mistake) |
| `env-custom-shopify-domain.env` | `SHOPIFY_API_BASE_URL=https://shop.mycompany.com` |
| `env-blank-webhook-secret.env` | `SHOPIFY_WEBHOOK_SECRETS=` |
| `env-missing-cj-token.env` | `CJ_ACCESS_TOKEN` line absent |
| `env-with-comment.env` | Includes `# SECRET_NOTES=my-internal-notes` — T-60 |

### 2.6 Edge-case payload values (reusable across tests)

| Value | Used by |
|---|---|
| `"inventory_item_id": 42857134217` (number) | T-08, T-13 |
| `"inventory_item_id": "42857134217"` (string) | Verify adapter accepts both via `asText()` |
| `"inventory_item_id": null` | T-10 |
| `sk_test_` prefix with 20 hex chars | T-53 pass |
| `sk_live_` prefix | T-54 fail |
| `sk_test` (no trailing underscore) | T-55 fail |
| `sk_test_` prefix with length < 16 | Optional T-55 variant; accept for now (Phase 5 may add length check) |

---

## Boundary Cases

### 3.1 Every field extraction point (FR-030 new code)

Per CLAUDE.md #17, every `get()?.asText()` on external JSON must have a JSON-null test. Enumerated here so Phase 5 cannot miss one.

| Extraction point | File | Boundary cases required |
|---|---|---|
| `variants[0].inventory_item_id` | `ShopifyListingAdapter.listSku()` | present+string, present+number, **absent (missing key)**, **JSON null**, empty variants array, variants key missing |
| `variants[0].id` | existing `ShopifyListingAdapter.listSku()` | Already covered by existing tests — regression only (T-14) |
| `product.id` | existing | Regression only |
| `inventory_levels[].available` | `ShopifyInventoryCheckAdapter.isAvailable()` | positive int, zero, negative, **JSON null**, absent, non-number (string) |
| `inventory_levels` (array itself) | same | present+populated, **empty array**, **key missing**, **JSON null** |
| `.env` line parsing | Gradle `devStoreAuditKeys` | present+value, present+empty, absent entirely, commented out (leading `#`), whitespace-only value, value with surrounding quotes |
| `Authorization` header | `DevAdminController` | absent, `Basic ` scheme, `Bearer ` scheme, malformed Base64, credentials with missing `:`, empty password, empty username, whitespace in token |
| SKU UUID path variable | `DevAdminController` | valid UUID, invalid format, valid UUID not in DB |

### 3.2 Threshold values

| Field | Boundaries to test | Which test |
|---|---|---|
| Stripe key prefix | `sk_test_` (pass), `sk_live_` (fail), `sk_test` (fail — missing underscore), `sk_TEST_` (fail — case sensitive), empty (fail) | T-53, T-54, T-55 |
| Shopify domain suffix | `.myshopify.com` (pass), `.mycompany.com` (fail), `.myshopify.co` (fail typo), case variations | T-56 |
| Stripe key length | Phase 5 decision — suggested ≥ 30 chars after prefix; test asserts documented floor | T-55 variant |
| Filter registration order | HMAC filter order = 1 (existing); archival filter order < 1 (e.g. 0 or `HIGHEST_PRECEDENCE`) | T-50 |
| Webhook HTTP response time | ≤ 5000 ms (NFR-1) | SC-RAT53-07 only (E2E) |
| HTTP Basic token length | token==expected passes; differ-by-1-byte fails; longer-than-expected fails | T-35 |

### 3.3 Empty / missing collections

| Collection | Empty case | Test |
|---|---|---|
| `product.variants` | `[]` | T-11 |
| `inventory_levels` | `[]` | T-28 |
| `platform_listings` matching SKU | no rows | T-19 |
| `platform_listings` matching SKU + ACTIVE | only inactive rows exist | T-21 |

### 3.4 Negative / invalid values

| Value | Expected behavior | Test |
|---|---|---|
| `inventory_levels[0].available = -3` | `isAvailable == false` | T-31 |
| SKU UUID = random non-existent | DevAdminController → 404 | T-38 |
| Filter output dir = read-only | Filter logs ERROR but continues chain | T-46 |

---

## E2E Playbook Scenarios

Draft for `docs/e2e-test-playbook.md` (Phase 5 appends verbatim as new section).

Phase 5 MUST add these scenarios to `docs/e2e-test-playbook.md` under a new section `## Phase 9: Shopify Dev Store + Stripe Test Mode Validation (FR-030 / RAT-53)`. The scenarios also cross-link to `docs/live-e2e-runbook.md` Section 12.

Each scenario is atomic: preconditions, action, observable outcomes, abort criteria. Scenarios execute in order.

### SC-RAT53-01: Dev store provisioning (ops-only)

- **Preconditions:** Operator has a Shopify Partners account; no dev store yet exists for this test.
- **Action:** Create development store via Partners dashboard; note the `*.myshopify.com` subdomain; install Custom App with scopes `write_products, write_orders, read_orders, write_fulfillments, read_fulfillments`; capture `shpat_…` Admin API access token and `whsec_…` webhook signing secret into local `.env` (never committed).
- **Observable outcomes:** `curl -s https://<subdomain>.myshopify.com/` returns HTTP 200 with storefront HTML (spec §7.1 criterion 1). `.env` file exists and is in `.gitignore`.
- **Abort criteria:** Shopify Partners approval delayed > 24h → file RAT-53 blocker, switch to alternate dev account, do not proceed to SC-02.

### SC-RAT53-02: Stripe test mode activation (ops-only)

- **Preconditions:** SC-RAT53-01 complete.
- **Action:** In Stripe dashboard, switch to test mode; obtain `sk_test_…` and `pk_test_…`; configure as payment provider in Shopify dev-store admin → Settings → Payments.
- **Fallback action:** If Stripe partner approval delays config, enable Shopify "Bogus Gateway" as documented fallback (spec §2.1 BR-3). Record fallback usage in the run log with rationale.
- **Observable outcomes:** Dev-store checkout page shows Stripe (test mode) or Bogus Gateway as the active payment option. `.env` contains `STRIPE_SECRET_KEY=sk_test_…` (NOT `sk_live_…`).
- **Abort criteria:** Neither Stripe test mode nor Bogus Gateway is available → abort; real money is the only remaining path and that violates NFR-3.

### SC-RAT53-03: Pre-flight key audit + clean app boot under `production` profile

- **Preconditions:** `.env` populated from SC-01 and SC-02; V23 migration merged; new admin + archival properties present (but not yet enabled).
- **Action:** `./gradlew devStoreAuditKeys` → expected pass. `./gradlew flywayMigrate` → V23 applies. `SPRING_PROFILES_ACTIVE=production ./gradlew bootRun`.
- **Observable outcomes:**
  - `devStoreAuditKeys` prints key last-4 and exits 0.
  - `GET /actuator/health` → `{"status":"UP"}` within 30s.
  - Startup logs show no `IllegalStateException` from `@Value` bean wiring (CLAUDE.md #13 validation).
  - `SHOW columns FROM platform_listings` includes `shopify_inventory_item_id`.
- **Abort criteria:** `devStoreAuditKeys` fails → do NOT proceed; fix `.env`; rerun. App fails to start under `production` profile → open Phase 6 bug, do not proceed.

### SC-RAT53-04: Automated listing via DevAdminController

- **Preconditions:** SC-03 complete; operator has set `autoshipper.admin.dev-listing-enabled=true` and `autoshipper.admin.dev-token=<rotating-secret>` for this session. A SKU exists in `STRESS_TESTED` state.
- **Action:** `curl -u "admin:$DEV_ADMIN_TOKEN" -X POST http://localhost:8080/admin/dev/sku/$SKU_ID/list`.
- **Observable outcomes:**
  - HTTP 202.
  - Logs: `Creating Shopify listing for SKU {}`.
  - `SELECT * FROM platform_listings WHERE sku_id = $SKU_ID` shows one row with `status='ACTIVE'`, `external_listing_id` non-null, **`shopify_inventory_item_id` non-null** (AC 2 of spec §7.2).
  - Hosted storefront: `curl -s https://<subdomain>.myshopify.com/products/<handle>.json` returns the product JSON (spec §7.1 criterion 4).
- **Abort criteria:** Listing row present but `shopify_inventory_item_id IS NULL` → AD-1 implementation is broken; do not proceed to purchase. Investigate `ShopifyListingAdapter` response parsing.

### SC-RAT53-05: Dummy-card purchase

- **Preconditions:** SC-04 complete; product visible on storefront; `autoshipper.webhook-archival.enabled=true` set in `.env` and app restarted (archival filter is eager-registered, requires restart).
- **Action:** Human operator navigates browser to storefront → adds product to cart → checks out with test card `4242 4242 4242 4242` (expiry any future date, CVC any 3 digits, ZIP any 5 digits).
- **Observable outcomes:**
  - Shopify order confirmation page reached, no error (spec §7.1 criterion 5).
  - Stripe test dashboard shows the test charge.
  - Stripe **live** dashboard shows zero activity for the window (spec §7.1 criterion 11).
- **Abort criteria:** Card rejected → check Stripe test mode is actually active; if Bogus Gateway is in use, substitute `Bogus Gateway: 1` per Shopify docs.

### SC-RAT53-06: Webhook receipt + archival

- **Preconditions:** SC-05 complete; ngrok tunneled and registered in Shopify webhook admin.
- **Action:** Inspect ngrok log and database.
- **Observable outcomes:**
  - ngrok inspector shows `POST /webhooks/shopify/orders` returning 200.
  - File exists at `docs/fixtures/shopify-dev-store/YYYY-MM-DD/orders-<ts>.json` with the exact bytes ngrok recorded (BR-9).
  - `SELECT * FROM webhook_events WHERE channel='shopify'` shows a row for this delivery.
  - `SELECT * FROM orders WHERE channel_order_id = '<shopify_order_id>'` shows `status='CONFIRMED'` (spec §7.1 criterion 6).
- **Abort criteria:** No archival file — confirm `autoshipper.webhook-archival.enabled=true` and app was restarted after flipping. No `orders` row → FR-023 webhook pipeline is broken; triage before proceeding.

### SC-RAT53-07: NFR-1 response-time verification

- **Action:** Read ngrok inspector timing for the `orders/create` POST observed in SC-06.
- **Observable outcomes:** Total response duration < 5000 ms. Record the exact value in the run log. Confirms PM-015 `@Async + AFTER_COMMIT + REQUIRES_NEW` stack is working against real traffic.
- **Abort criteria:** Duration ≥ 5000 ms → Shopify will eventually disable the endpoint; file immediate PM-015-redux postmortem, pause the test.

### SC-RAT53-08: CJ order-placement attempt (log-only assertion)

- **Preconditions:** SC-06 complete. `.env` configured per one of the two safe paths (BR-3a sandbox OR BR-6b dry-run).
- **Action:** Watch application logs for `SupplierOrderPlacementService` / `CjSupplierOrderAdapter` activity.
- **Observable outcomes (ANY of these three passes — operator records WHICH in run log):**
  - (a) **Sandbox path:** `CJ order placed successfully` log line with CJ sandbox order id. Verify via CJ sandbox dashboard that the order landed AND that it is marked as a sandbox order (no real supplier dispatch).
  - (b) **Dry-run path:** log line contains `[DEV-STORE DRY RUN] would have placed CJ order: skuCode=… qty=… orderNumber=…` AND stubbed supplier order id has prefix `dry-run-`. Verify via ngrok / WireMock / network tap that **zero outbound HTTP calls were made to `developers.cjdropshipping.cn`** during the window.
  - (c) **Error path (sandbox only):** `CJ order placement failed: <reason>` log line with specific failure detail — AND `orders.failure_reason` column populated for this order.
- **Abort criteria:** None of the three log lines appears within 2 minutes → the `OrderConfirmed` listener chain is broken, file bug, do not proceed. Spec §7.1 criterion 7 explicitly permits CJ **failure**, but does NOT permit **silence** (NFR-6).
- **Safety assertion:** If dry-run path (b) was chosen, MUST also verify CJ dashboard (both sandbox and production) shows NO new order created in the test window. If sandbox path (a) was chosen, MUST verify the production CJ dashboard shows NO new order.

### SC-RAT53-09: Fulfillment sync (best-effort, optional)

- **Preconditions:** SC-08 placed a real CJ order (success case).
- **Action:** Wait for CJ tracking webhook OR manually trigger a simulated tracking webhook via `curl` to `/webhooks/cj/tracking` (HMAC-signed with `.env` secret).
- **Observable outcomes (EITHER passes):**
  - `OrderShipped` event published, `ShopifyFulfillmentSyncListener` logs a successful Shopify fulfillment creation; `orders.status='SHIPPED'` with `tracking_number` populated.
  - OR: Operator records explicit reason in run log why fulfillment was not exercised (e.g. CJ order rejected at SC-08, preventing tracking).
- **Abort criteria:** Fulfillment sync attempted but Shopify rejects → RAT-43 (refund) follow-up; does not block FR-030 completion if SC-08 passed.
- **Note:** `DELIVERED` transition remains out of scope (documented known gap).

### SC-RAT53-10: Post-test cleanup + archival commit + key rotation

- **Action:** Copy committed archival files from `docs/fixtures/shopify-dev-store/{date}/` into the feature branch; redact any PII (buyer email, shipping address) per BR-9 policy in the directory README. If CJ sandbox path (SC-08a) was used, verify the sandbox order via CJ dashboard and confirm no production-account order was dispatched (sandbox orders do not need cancellation). If dry-run path (SC-08b) was used, no CJ cleanup needed. Rotate `DEV_ADMIN_TOKEN` and Shopify webhook secret per operator-security hygiene. Delete the test dev store in Shopify Partners if it is single-use.
- **Observable outcomes:**
  - `git diff docs/fixtures/shopify-dev-store/` shows 1+ committed JSON files.
  - CJ dashboard reflects the chosen path — sandbox order present (path a), or no CJ activity at all (path b).
  - Audit log shows the dev-listing-enabled property returned to `false` and `DEV_ADMIN_TOKEN` cleared from `.env`.
- **Abort criteria:** Archival files contain PII that cannot be redacted safely → do NOT commit; instead note in the run log that raw payloads are retained locally only.

---

## Contract Test Candidates

Per Phase 4 rules, contract tests are **only** for pure domain types that compile meaningfully against stubs. For FR-030 the production surface is almost entirely Spring-integrated (controllers, filters, adapters, resolvers, Gradle tasks). Nearly every assertion requires production code to exist.

### 5.1 Candidates — write NOW

**None**, with the following rationale:

- `PlatformListingResult` gains a new nullable field `inventoryItemId: String?`. A contract test asserting `PlatformListingResult(...).inventoryItemId == null` when omitted is an assertion about Kotlin default-parameter semantics, not about our logic. It would be `assert(true)`-flavored and is banned.
- Entity `PlatformListingEntity` gains a field; its persistence requires Spring context (see T-05/T-06). Not a pure contract test.
- HTTP Basic token-comparison helper (T-41) could theoretically be a pure contract test IF Phase 5 extracts a `ConstantTimeEquals` utility in `shared`. Decision deferred to Phase 5 — if extracted, T-41 becomes a contract test in `shared/src/test`; if not, it stays at the controller unit level.
- Gradle task key-prefix validator logic CAN be a pure Kotlin function (given `.env` content as a String, return `List<ValidationFailure>`). If Phase 5 factors it this way, tests T-53 through T-61 become pure contract tests driven by fixture strings. This is the strongly-recommended refactor.

### 5.2 Deferred to Phase 5 TDD

All listed assertions in §1 (T-01 through T-68) are deferred to Phase 5 to implement alongside the production code in TDD order. Writing them now against non-existent production code would require stubs that couldn't meaningfully assert behavior (banned `assert(true)` pattern).

### 5.3 Assertions NOT specifiable deterministically (documented gaps)

These are the genuine unknowns — Phase 5 must resolve each with an implementation decision, and the test spec accommodates both outcomes:

- **T-22 (`PlatformListingResolver` multiple-active-listings behavior):** Phase 5 chooses deterministic tiebreaker (`ORDER BY created_at ASC`) vs. throw. Spec requires one of the two; nondeterministic return is banned.
- **T-40 (DevAdminController repeat-invocation semantics):** Phase 5 chooses 409 Conflict vs. 202 idempotent. Spec requires no duplicate `platform_listings` row; HTTP status is operator ergonomics.
- **T-41 (constant-time token comparison):** Phase 5 decides whether to extract the helper. If not extracted, T-41 is skipped and marked as accepted risk in `summary.md`.
- **T-61 (Gradle task exit-code distinction):** Phase 5 assigns specific exit codes (suggested: 0 pass, 1 invalid, 2 missing file). Test asserts the chosen convention, whatever Phase 5 picks.
- **NFR-1 unit-test equivalent:** There is no deterministic unit test for "< 5 seconds end-to-end". The existing `@Async + AFTER_COMMIT + REQUIRES_NEW` annotation stack on `ShopifyOrderProcessingService` is covered by FR-023 tests; this FR adds only the E2E assertion (SC-RAT53-07).

---

## 6. Phase 5 Implementation Order (TDD guidance)

The strongly-recommended implementation order for Phase 5, driven by this test spec:

1. **V23 migration** → T-01 through T-04 pass (Testcontainers, fast).
2. **`PlatformListingEntity` field** → T-05 through T-07 pass.
3. **`PlatformListingResult` field + `StubPlatformAdapter`** → compiles.
4. **`ShopifyListingAdapter` extraction** (one test at a time, CLAUDE.md #17 NullNode guard in place from the start) → T-08 through T-14 pass.
5. **`PlatformListingListener` persistence** → T-15 through T-17 pass.
6. **`PlatformListingResolver.resolveInventoryItemId`** → T-18 through T-23 pass.
7. **`ShopifyInventoryCheckAdapter` refactor** → T-24 through T-31 pass.
8. **`DevAdminController`** → T-32 through T-41 pass.
9. **`WebhookArchivalFilter` + config** → T-42 through T-52 pass.
10. **Gradle `devStoreAuditKeys` task** → T-53 through T-61 pass.
11. **`.env.example` + `application.yml` defaults** → T-62 and T-63 pass.
12. **CJ dry-run kill-switch** → T-64, T-65, T-66, T-67, T-68 pass. T-65 specifically asserts zero HTTP calls when dry-run is on — the load-bearing safety guarantee.
12. **Runbook Section 0 + Section 12 + docs/fixtures directory skeleton.**
13. **`docs/e2e-test-playbook.md` Phase 9 — SC-RAT53-01 through SC-RAT53-10 added** (copy from §4 above, do not paraphrase — the scenarios are binding).

Each step: write the failing test, implement minimal code, confirm pass, commit. No single-session "vibe coding" (CLAUDE.md #18).

---

## 7. Validation Checklist for Phase 4 Deliverables

- [x] Acceptance Criteria section with 68 testable assertions (T-01 to T-68).
- [x] Fixture Data section enumerates all new JSON fixtures and `.env` fixtures with exact content expectations.
- [x] Boundary Cases section enumerates every field extraction point with NullNode, missing, wrong-type, empty variants.
- [x] E2E Playbook Scenarios section drafts SC-RAT53-01 through SC-RAT53-10 for Phase 5 to append to `docs/e2e-test-playbook.md`.
- [x] Contract Test Candidates section documents the decision to defer all tests to Phase 5 TDD (with rationale per candidate).
- [x] CLAUDE.md #17 (NullNode guard) enforced via T-10 and T-30.
- [x] No `assert(true)`, no `payload.contains(...)`, no `// Phase 5:` TODOs in this spec.
- [x] Deterministic-unknown assertions (T-22, T-40, T-41, T-61) flagged explicitly in §5.3 so Phase 5 does not silently drop them.
