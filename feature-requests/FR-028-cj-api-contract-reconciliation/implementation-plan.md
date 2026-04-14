# FR-028: CJ API Contract Reconciliation — Implementation Plan

**Linear ticket:** RAT-47
**Feature name:** `cj-api-contract-reconciliation`
**Phase:** 3 (Implementation Planning)
**Created:** 2026-04-14

---

## Technical Design

This is a verification-and-reconciliation task, not new feature development. The work follows the PM-020 precedent established during FR-027: every CJ API field name in production code, WireMock fixtures, and tests must trace back to verified API documentation or live API responses.

### Two-Phase Approach

**Phase A — API Verification:** Obtain a fresh CJ access token, consult CJ Shopping API documentation at `https://developers.cjdropshipping.cn/en/api/api2/api/order.html`, hit each Shopping API endpoint against the live API where possible, and build a verified OpenAPI YAML spec (`docs/api/cj_shopping_api.yaml`). For webhook payloads (push-based, cannot be triggered on demand), use CJ's webhook documentation and mark as `x-cj-verified: docs-only`.

**Phase B — Code Reconciliation:** Diff each production class, fixture, and test against the verified YAML. Fix every field name, nesting structure, error code, and carrier string mismatch. No code changes for fields that are already correct (NFR-3).

### Architecture Impact

- No new modules, services, or database tables
- No migration files
- No new dependencies
- All changes are internal contract alignment within existing `fulfillment` and `portfolio` modules
- Existing class structure and responsibilities are preserved; only field names, JSON shapes, and assertion values change

### Key Risk: Webhook Verification Gap

CJ webhooks are push-based — we cannot trigger a webhook delivery on demand. The webhook payload structure must be verified from documentation rather than live API calls. The YAML spec will use `x-cj-verified: docs-only` for webhook schemas. If CJ documentation is insufficient, the existing payload structure will be preserved with a `x-cj-verified: unverified` marker and a comment noting the gap.

---

## Architecture Decisions

### AD-1: Verification-first, not implementation-first

Unlike typical feature work, this FR reverses the normal flow. Instead of writing code and then tests, we first produce a verified API spec (YAML) and then reconcile existing code against it. This follows the PM-020 postmortem finding: WireMock tests passing against fabricated fixtures provides zero assurance of production correctness. The YAML spec becomes the single source of truth.

**Why not just fix code directly?** Without a verified spec artifact, reconciliation becomes a game of whack-a-mole — each developer guesses what CJ's API looks like. The YAML spec is the durable artifact that prevents future fabrication.

### AD-2: Single YAML spec for Shopping API (not per-endpoint files)

All Shopping API endpoints (order creation, order query, webhook payloads) go into one `cj_shopping_api.yaml` file, matching the precedent set by `cj_product_api.yaml` which covers all Product API endpoints in one file. Alternative: separate files per endpoint. Rejected because the CJ docs page groups these together and cross-references between them.

### AD-3: Preserve existing class structure

No refactoring of class responsibilities. `CjSupplierOrderAdapter` stays as the order placement adapter, `CjTrackingProcessingService` stays as the webhook processor, etc. The goal is surgical field-name fixes, not architectural changes. This minimizes diff size and regression risk.

### AD-4: Bearer token auth as default assumption

The `CjWebhookTokenVerificationFilter` assumes Bearer token authentication. If CJ documentation reveals a different mechanism (HMAC, custom header), the filter will be rewritten. But the constant-time comparison pattern (`MessageDigest.isEqual()`) and `CachingRequestWrapper` will be preserved regardless — these are security best practices independent of the specific auth mechanism.

---

## Layer-by-Layer Implementation

### Layer 1: API Verification (docs + live calls)

**Purpose:** Produce the verified `cj_shopping_api.yaml` spec that all subsequent layers depend on.

**Inputs:** CJ Shopping API documentation at `https://developers.cjdropshipping.cn/en/api/api2/api/order.html`, CJ access token from `.env`, existing `cj_product_api.yaml` as format reference.

**Outputs:** `docs/api/cj_shopping_api.yaml` with OpenAPI 3.0.3 format, `x-cj-verified` markers on every endpoint and schema.

**Approach:**
1. Authenticate with CJ API to get a fresh access token
2. Read the Shopping API documentation page for all endpoint definitions
3. Where possible, make live API calls to verify response shapes (createOrderV2 may require valid product data — use a known CJ product from prior listV2 verification)
4. For webhooks, use documentation only (mark `x-cj-verified: docs-only`)
5. Write YAML spec following `cj_product_api.yaml` conventions

### Layer 2: Production Code Reconciliation

**Purpose:** Fix every CJ field name in production adapters/services to match the verified YAML spec.

**Inputs:** `cj_shopping_api.yaml` from Layer 1, current production source files.

**Files touched:**
- `modules/fulfillment/src/main/kotlin/.../proxy/supplier/CjSupplierOrderAdapter.kt` — request body field names (L50-67), response parsing (L92-110)
- `modules/fulfillment/src/main/kotlin/.../domain/service/CjTrackingProcessingService.kt` — webhook field parsing (L29-39)
- `modules/fulfillment/src/main/kotlin/.../handler/webhook/CjTrackingWebhookController.kt` — webhook field parsing (L28-32)
- `modules/fulfillment/src/main/kotlin/.../handler/webhook/CjWebhookTokenVerificationFilter.kt` — auth mechanism (L40-66)
- `modules/fulfillment/src/main/kotlin/.../proxy/carrier/CjCarrierMapper.kt` — carrier name mapping entries (L5-15)

**Constraints:** Preserve all NullNode guards (CLAUDE.md #17), `get()` vs `path()` usage (CLAUDE.md #15), injected ObjectMapper (CLAUDE.md #20), and existing error handling patterns (try-catch around markShipped per CLAUDE.md #19).

### Layer 3: Fixture Reconciliation

**Purpose:** Align all CJ WireMock fixture JSON files with verified response shapes and add documentation headers.

**Inputs:** `cj_shopping_api.yaml` from Layer 1, FR-027 `_comment` header pattern.

**Files touched:**
- `modules/fulfillment/src/test/resources/wiremock/cj/create-order-success.json`
- `modules/fulfillment/src/test/resources/wiremock/cj/create-order-auth-failure.json`
- `modules/fulfillment/src/test/resources/wiremock/cj/create-order-invalid-address.json`
- `modules/fulfillment/src/test/resources/wiremock/cj/create-order-null-fields.json`
- `modules/fulfillment/src/test/resources/wiremock/cj/create-order-out-of-stock.json`
- `modules/portfolio/src/test/resources/wiremock/cj/error-401.json`
- `modules/portfolio/src/test/resources/wiremock/cj/error-429.json`

**Pattern:** Each fixture gets `_comment` (API doc URL) and `_comment_verified` (verification date) fields at the top of the JSON object, following the FR-027 convention.

### Layer 4: Test Reconciliation

**Purpose:** Update all test assertions and inline payloads to match the reconciled production code and fixtures.

**Inputs:** Updated production code from Layer 2, updated fixtures from Layer 3.

**Files touched:**
- `modules/fulfillment/src/test/kotlin/.../proxy/supplier/CjSupplierOrderAdapterWireMockTest.kt` (16 tests)
- `modules/fulfillment/src/test/kotlin/.../domain/service/CjTrackingProcessingServiceTest.kt` (13 tests)
- `modules/fulfillment/src/test/kotlin/.../handler/webhook/CjTrackingWebhookControllerTest.kt` (8 tests)
- `modules/fulfillment/src/test/kotlin/.../handler/webhook/CjWebhookTokenVerificationFilterTest.kt` (8 tests)
- `modules/fulfillment/src/test/kotlin/.../integration/CjTrackingWebhookIntegrationTest.kt` (3 tests)
- `modules/fulfillment/src/test/kotlin/.../proxy/carrier/CjCarrierMapperTest.kt` (14 tests)

**Strategy:** No new tests unless verification reveals a field that has no existing test coverage. Default is reconciliation of existing tests.

### Layer 5: Stale Build Artifact Cleanup

**Purpose:** Ensure `build/` and `bin/` directories do not contain stale compiled fixtures from before reconciliation.

**Approach:** Run `./gradlew clean test` as the final verification gate. This ensures tests run against the reconciled fixtures, not cached copies.

---

## Task Breakdown

### Layer 1 — API Verification (docs + live calls)

These tasks produce the verified YAML spec that all subsequent layers depend on.

- [x] **1.1** Get fresh CJ access token via `POST /authentication/getAccessToken` using credentials from `.env`. Verify token works with a simple product list call.

- [x] **1.2** Fetch and read CJ Shopping API documentation at `https://developers.cjdropshipping.cn/en/api/api2/api/order.html`. Document all Shopping API endpoints, their request/response schemas, field names, types, and error codes.

- [x] **1.3** Verify `createOrderV2` endpoint against live API (or documentation if live call requires real product data):
  - Request body field names: `orderNumber`, `shippingCountryCode`, `shippingCountry`, `shippingCustomerName`, `shippingAddress`, `shippingCity`, `shippingProvince`, `shippingZip`, `shippingPhone`, `fromCountryCode`, `products[].vid`, `products[].quantity`, `logisticName`
  - Response envelope: `code`, `result`, `message`, `data` structure
  - Response data fields: `orderId`, `orderNum` (verify names and nesting)
  - Error codes: `1600001` (auth), `1600501` (out of stock), `1600502` (invalid address), `1600200` (rate limit)

- [x] **1.4** Verify order query endpoints (order list, order detail by ID/number) from documentation. Record request parameters and response schemas.

- [x] **1.5** Verify webhook payload structure from CJ webhook documentation:
  - Top-level envelope fields (`messageId`, `type`, `messageType`, `openId`)
  - `params` object fields (`orderId`, `trackingNumber`, `logisticName`, `trackingStatus`, `logisticsTrackEvents`)
  - Authentication mechanism (Bearer token? HMAC signature? Custom header?)

- [x] **1.6** Create `docs/api/cj_shopping_api.yaml` with:
  - OpenAPI 3.0.3 format matching `cj_product_api.yaml` conventions
  - `CJ-Access-Token` header authentication model
  - `x-cj-verified: true` for endpoints verified against live API or documentation
  - `x-cj-verified: docs-only` for webhook schemas verified from docs only
  - `x-cj-doc-source` citations with page and section references
  - All component schemas for request/response bodies
  - Error code enumeration in component schemas

### Layer 2 — Production Code Reconciliation

Each task compares one production class against the verified YAML spec. Changes are made only where mismatches are found.

- [x] **2.1** Reconcile `CjSupplierOrderAdapter` request body construction (L50-67):
  - Compare each field name in the `body` map against `cj_shopping_api.yaml` createOrderV2 request schema
  - Fix any mismatched field names (e.g., if CJ uses `consigneeName` instead of `shippingCustomerName`)
  - Add any required fields documented in the API that are currently missing
  - Verify `products[].vid` is the correct variant identifier field name
  - Preserve existing NullNode guards and logisticName conditional logic

- [x] **2.2** Reconcile `CjSupplierOrderAdapter` response parsing (L92-110):
  - Verify `result` (boolean), `code` (int), `message` (string) envelope fields
  - Verify `data.orderId` path and field name for the supplier order ID
  - Verify success condition (`result == true && code == 200`)
  - Preserve NullNode guards per CLAUDE.md #17

- [x] **2.3** Reconcile `CjTrackingProcessingService` webhook field parsing (L29-39):
  - Verify `params` is the correct parent node for tracking fields
  - Verify `orderId`, `trackingNumber`, `logisticName` field names within `params`
  - If webhook structure differs, update all `get()` calls to match verified structure
  - Preserve all NullNode guards, try-catch around markShipped, and error logging

- [x] **2.4** Reconcile `CjTrackingWebhookController` webhook structure (L28-32):
  - Same field structure as 2.3 — webhook controller and processing service must parse the same structure
  - Verify `params.orderId` and `params.trackingNumber` paths for dedup key construction
  - Preserve existing dedup pattern (`cj:{orderId}:{trackingNumber}`)

- [x] **2.5** Reconcile `CjWebhookTokenVerificationFilter` auth mechanism (L40-66):
  - If CJ uses Bearer token in Authorization header: add `x-cj-verified: true` comment to the filter class, no code change
  - If CJ uses HMAC signature: rewrite filter to compute HMAC over request body and compare
  - If CJ uses a different header or mechanism: rewrite to match
  - Preserve constant-time comparison (`MessageDigest.isEqual()`) regardless of mechanism
  - Preserve `CachingRequestWrapper` usage for body re-reading

- [x] **2.6** Reconcile `CjCarrierMapper` carrier name strings (L5-15):
  - Compare each mapping entry against `logisticName` values documented in CJ API
  - Add any carrier names CJ documents that are missing
  - Remove any entries CJ does not use
  - Verify casing: does CJ send `"UPS"`, `"ups"`, or `"Ups"`?
  - The mapper already lowercases input before lookup, so casing in the map keys should match lowercase

### Layer 3 — Fixture Reconciliation

Each fixture must match the verified response/error shape from `cj_shopping_api.yaml`. All fixtures get documentation header comments.

- [x] **3.1** Fix `modules/fulfillment/src/test/resources/wiremock/cj/create-order-success.json`:
  - Verify `code: 200`, `result: true`, `message: "success"` envelope
  - Verify `data.orderId` and `data.orderNum` field names and types
  - Verify `requestId` field exists in real responses
  - Add `_comment` and `_comment_verified` headers citing order.html

- [x] **3.2** Fix `modules/fulfillment/src/test/resources/wiremock/cj/create-order-auth-failure.json`:
  - Verify error code `1600001` for authentication failure
  - Verify `result: false`, `message` text, `data: null` shape
  - Add documentation headers

- [x] **3.3** Fix `modules/fulfillment/src/test/resources/wiremock/cj/create-order-invalid-address.json`:
  - Verify error code `1600502` for invalid address
  - Verify error message text matches CJ documentation
  - Add documentation headers

- [x] **3.4** Fix `modules/fulfillment/src/test/resources/wiremock/cj/create-order-null-fields.json`:
  - Verify the `data.orderId: null` shape is realistic (does CJ actually return `code: 200` with null data fields?)
  - If not realistic, adjust to match a real edge case from CJ docs
  - Add documentation headers

- [x] **3.5** Fix `modules/fulfillment/src/test/resources/wiremock/cj/create-order-out-of-stock.json`:
  - Verify error code `1600501` for out-of-stock
  - Verify error message text
  - Add documentation headers

- [x] **3.6** Fix `modules/portfolio/src/test/resources/wiremock/cj/error-401.json`:
  - Same auth error code verification as 3.2
  - Add documentation headers

- [x] **3.7** Fix `modules/portfolio/src/test/resources/wiremock/cj/error-429.json`:
  - Verify error code `1600200` for rate limiting
  - Verify message text (currently "Too much request" — verify against CJ docs)
  - Add documentation headers

### Layer 4 — Test Reconciliation

Tests must assert against the verified field names and response shapes. Changes flow from Layers 2 and 3.

- [x] **4.1** Fix `CjSupplierOrderAdapterWireMockTest` (16 tests):
  - If Layer 2 renamed request body fields, update `withRequestBody(containing(...))` assertions in:
    - `request body verification` test (L194-209): update field name strings
    - `maps address line2 into shippingAddress field` test (L274-316)
    - `handles null address line2 in shippingAddress` test (L319-361)
  - If response parsing changed, update success/failure assertions
  - `successful order placement` test (L75-91): verify orderId value matches updated fixture
  - `out of stock error` test (L94-110): verify failure reason matches updated fixture message
  - `invalid address error` test (L112-129): verify failure reason matches updated fixture message

- [x] **4.2** Fix `CjTrackingProcessingServiceTest` (13 tests):
  - If Layer 2 changed webhook structure, update all inline JSON payloads in:
    - `happy path` test (L59-69): update payload field names/structure
    - `order not found` test (L72-83)
    - `order not CONFIRMED` test (L86-99)
    - `order in PENDING status` test (L102-114)
    - `missing trackingNumber` test (L117-127)
    - `missing orderId` test (L130-140)
    - `invalid UUID orderId` test (L143-153)
    - `null logisticName` test (L156-167)
    - `carrier normalization` test (L170-181)
    - `missing params node` test (L184-194)
    - `NullNode orderId` test (L197-207)
    - `markShipped failure` test (L210-223)
    - `NullNode trackingNumber` test (L226-236)

- [x] **4.3** Fix `CjTrackingWebhookControllerTest` (8 tests):
  - Update `validPayload` constant (L37) if webhook structure changed
  - Update all inline payloads in edge-case tests:
    - `missing trackingNumber` test (L111-125)
    - `missing orderId` test (L128-142)
    - `missing params node` test (L145-158)
    - `empty params node` test (L160-174)

- [x] **4.4** Fix `CjWebhookTokenVerificationFilterTest` (8 tests):
  - If auth mechanism changed (Layer 2.5), rewrite test setup and assertions
  - If Bearer token is confirmed correct, no changes needed — tests already verify Bearer flow
  - Add comment noting CJ auth mechanism is verified

- [x] **4.5** Fix `CjTrackingWebhookIntegrationTest` (3 tests):
  - Update `buildCjTrackingPayload()` method (L84-94) if webhook structure changed
  - This method currently builds `{"params":{"orderId":"...","trackingNumber":"...","logisticName":"..."}}` — verify this matches the verified webhook structure
  - Note: this test uses a bare `ObjectMapper()` (L57) — evaluate whether this is acceptable per CLAUDE.md #20 (test code can use bare ObjectMapper if adapters are constructed directly)

- [x] **4.6** Fix `CjCarrierMapperTest` (14 tests):
  - If Layer 2.6 added/removed carrier names, add/remove corresponding test cases
  - If casing of CJ's `logisticName` values changed, update test inputs
  - Existing case-insensitivity tests should be preserved — the mapper lowercases before lookup

- [x] **4.7** Run `./gradlew test` — all modules must be green. Fix any remaining test failures introduced by reconciliation.

### Layer 5 — Stale Build Artifact Cleanup

- [x] **5.1** Clean stale compiled fixtures in `build/` and `bin/` directories:
  - Run `./gradlew clean` before final test run (per NFR-5)
  - Verify `bin/test/resources/wiremock/cj/` fixtures are not stale after rebuild
  - This prevents false passes from tests loading old cached fixtures

---

## Dependency Order

```
Layer 1 (API Verification)
  |
  v
Layer 2 (Production Code) + Layer 3 (Fixtures)   [can run in parallel — both depend only on Layer 1]
  |
  v
Layer 4 (Tests)   [depends on Layers 2 + 3 — tests assert against updated code and fixtures]
  |
  v
Layer 5 (Clean build + final test)   [depends on all prior layers]
```

Layers 2 and 3 can be executed in parallel because production code changes and fixture changes are independent — they both derive from the verified YAML spec, not from each other.

---

## CLAUDE.md Constraint Checklist

| Constraint | Relevance | How Enforced |
|---|---|---|
| #15 (Jackson get vs path) | Any corrected JSON parsing must use `get()` for null-coalescing | Review all `get()`/`path()` calls in touched classes |
| #17 (NullNode guard) | All `get()?.asText()` calls must use the full NullNode guard | Preserve existing guards; add to any new parsing |
| #20 (No bare ObjectMapper) | Verify no bare `ObjectMapper()` in production code | `CjSupplierOrderAdapter` already injects it (FR-027 fix); verify `CjTrackingProcessingService` does too |
| Feedback: WireMock fixtures | Fixtures must be based on real API docs, not reverse-engineered | All fixtures derived from `cj_shopping_api.yaml` which is derived from docs/live API |
| #12 (URL-encode user values) | Not applicable — CJ API uses JSON bodies, not form-encoded | No action |
| #13 (@Value empty defaults) | Verify all @Value annotations use `${key:}` syntax | Already correct in `CjSupplierOrderAdapter` |

---

## Testing Strategy

No new tests are created — this is reconciliation of existing tests against verified API contracts. The verification gate is:

1. All 16 `CjSupplierOrderAdapterWireMockTest` tests pass
2. All 13 `CjTrackingProcessingServiceTest` tests pass
3. All 8 `CjTrackingWebhookControllerTest` tests pass
4. All 8 `CjWebhookTokenVerificationFilterTest` tests pass
5. All 3 `CjTrackingWebhookIntegrationTest` tests pass
6. All 14 `CjCarrierMapperTest` tests pass
7. Full `./gradlew test` passes across all modules (no regressions in non-CJ code)

If verification reveals that the existing test count is insufficient to cover a corrected field name or new required field, a targeted test may be added. But the default is reconciliation, not expansion.

---

## Rollout Plan

- **Single PR** — all changes in one PR for atomic review
- **No database migration** — no schema changes, no Flyway files
- **No configuration changes** — existing `.env` credentials and application properties are sufficient
- **No runtime behavior change for correct fields** (NFR-3) — if a field name is already correct, the code is not touched
- **Branch name:** `fix/RAT-47-cj-api-contract-reconciliation`
