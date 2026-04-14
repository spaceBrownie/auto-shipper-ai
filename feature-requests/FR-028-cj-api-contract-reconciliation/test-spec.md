# FR-028: CJ API Contract Reconciliation — Test Specification

**Linear ticket:** RAT-47
**Phase:** 4 (Test Specification)
**Created:** 2026-04-14

---

## Overview

This is a reconciliation task, not new feature development. All test files already exist. This test-spec defines what the corrected tests must assert after production code, fixtures, and tests are reconciled against the verified `cj_shopping_api.yaml` spec produced in Layer 1.

**Key principle:** No test changes are made until Layer 1 (API verification) produces the verified YAML spec. Field names, response shapes, error codes, and carrier strings in this document are based on what the current code assumes. Anywhere that verification may change the expected values, the field is marked `PENDING_VERIFICATION`.

---

## Acceptance Criteria

### AC-1: CjSupplierOrderAdapter — Request Body Field Names

The corrected `CjSupplierOrderAdapterWireMockTest` must verify that the request body sent to `POST /api2.0/v1/shopping/order/createOrderV2` contains exactly these fields (names subject to change per verified spec):

| Current field name | Type | Required | Verified? |
|---|---|---|---|
| `orderNumber` | string | yes | PENDING_VERIFICATION |
| `shippingCountryCode` | string | yes | PENDING_VERIFICATION |
| `shippingCountry` | string | yes | PENDING_VERIFICATION |
| `shippingCustomerName` | string | yes | PENDING_VERIFICATION |
| `shippingAddress` | string (concatenated line1 + line2) | yes | PENDING_VERIFICATION |
| `shippingCity` | string | yes | PENDING_VERIFICATION |
| `shippingProvince` | string | yes | PENDING_VERIFICATION |
| `shippingZip` | string | yes | PENDING_VERIFICATION |
| `shippingPhone` | string | yes | PENDING_VERIFICATION |
| `fromCountryCode` | string | yes | PENDING_VERIFICATION |
| `products[].vid` | string | yes | PENDING_VERIFICATION |
| `products[].quantity` | integer | yes | PENDING_VERIFICATION |
| `logisticName` | string | conditional (omitted when blank) | PENDING_VERIFICATION |

**What tests must assert after verification:**

- `request body verification` test: `withRequestBody(containing(...))` assertions must use the verified field names from `cj_shopping_api.yaml`. If CJ uses `consigneeName` instead of `shippingCustomerName`, the assertion string must change to `consigneeName`.
- `maps address line2 into shippingAddress field` test: field name in `withRequestBody(containing(...))` must match the verified address field name.
- `handles null address line2 in shippingAddress` test: same field name update.
- `US warehouse mapping sends fromCountryCode US` test: field name in `withRequestBody(containing(...))` must match verified field.
- `null warehouse mapping falls back to fromCountryCode CN` test: same.
- `logisticName configured - included in request body` test: field name must match verified field.
- `logisticName blank - omitted from request body` test: `doesNotContain` string must match verified field name.

### AC-2: CjSupplierOrderAdapter — Response Parsing

The corrected tests must assert against the verified response envelope:

| Current field | Expected type | Verified? |
|---|---|---|
| `result` | boolean | PENDING_VERIFICATION |
| `code` | integer (200 = success) | PENDING_VERIFICATION |
| `message` | string | PENDING_VERIFICATION |
| `data.orderId` | string | PENDING_VERIFICATION |
| `data.orderNum` | string (present in fixture, not parsed by adapter) | PENDING_VERIFICATION |
| `requestId` | string (present in fixture, not parsed by adapter) | PENDING_VERIFICATION |

**What tests must assert:**

- `successful order placement returns Success with supplierOrderId`: `success.supplierOrderId` must equal the `data.orderId` value from the verified success fixture.
- `out of stock error returns Failure with reason`: `failure.reason` must contain the verified error message text for the out-of-stock error code.
- `invalid address error returns Failure with reason`: `failure.reason` must contain the verified error message text for the invalid-address error code.
- `null orderId in success response returns Failure via NullNode guard`: unchanged behavior (NullNode guard is a code pattern, not API-dependent).
- `null data node in response returns Failure`: unchanged behavior.

### AC-3: CjTrackingProcessingService — Webhook Payload Fields

The corrected `CjTrackingProcessingServiceTest` must use inline payloads matching the verified webhook structure from `cj_shopping_api.yaml`.

**Current assumed webhook structure:**

```json
{
  "messageId": "msg-1",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": "...",
    "trackingNumber": "...",
    "logisticName": "...",
    "trackingStatus": 1,
    "logisticsTrackEvents": "[]"
  }
}
```

| Field path | Used by code? | Verified? |
|---|---|---|
| `params` (parent node) | yes — `root.get("params")` | PENDING_VERIFICATION |
| `params.orderId` | yes — extracted for order lookup | PENDING_VERIFICATION |
| `params.trackingNumber` | yes — extracted for shipping update | PENDING_VERIFICATION |
| `params.logisticName` | yes — extracted for carrier normalization | PENDING_VERIFICATION |
| `messageId` | no — present in test payloads only | PENDING_VERIFICATION |
| `type` | no — present in test payloads only | PENDING_VERIFICATION |
| `messageType` | no — present in test payloads only | PENDING_VERIFICATION |
| `openId` | no — present in test payloads only | PENDING_VERIFICATION |
| `params.trackingStatus` | no — present in test payloads only | PENDING_VERIFICATION |
| `params.logisticsTrackEvents` | no — present in test payloads only | PENDING_VERIFICATION |

**What all 13 tests must assert after verification:**

If the verified webhook structure differs from the current assumption (e.g., tracking fields are at root level instead of nested under `params`, or field names differ), every inline JSON payload across all 13 tests must be updated to match. The behavioral assertions (markShipped called/not called, carrier normalization, etc.) remain the same — only the payload shape changes.

Specific test-by-test requirements:

1. **happy path**: payload must use verified structure; `verify(orderService).markShipped(...)` with correct carrier from `CjCarrierMapper.normalize()`.
2. **order not found**: payload with verified structure; `verify(orderService, never()).markShipped(...)`.
3. **order not CONFIRMED**: payload with verified structure; skip processing for non-CONFIRMED orders.
4. **order in PENDING status**: payload with verified structure; skip processing for PENDING orders.
5. **missing trackingNumber**: payload with verified structure, tracking field absent; skip processing.
6. **missing orderId**: payload with verified structure, order ID field absent; skip processing.
7. **invalid UUID orderId**: payload with verified structure, non-UUID order ID; skip processing, no repo call.
8. **null logisticName**: payload with `logisticName: null` (JSON null); carrier defaults to `"unknown"`.
9. **carrier normalization**: payload with `logisticName: "fedex"`; carrier normalized to `"FedEx"`.
10. **missing params node**: payload without the params container (or verified equivalent); skip processing.
11. **NullNode orderId**: payload with `orderId: null` (JSON null); NullNode guard triggers, skip processing.
12. **markShipped failure**: payload with verified structure; exception caught and logged, does not propagate.
13. **NullNode trackingNumber**: payload with `trackingNumber: null` (JSON null); NullNode guard triggers, skip processing.

### AC-4: CjTrackingWebhookController — Webhook Payload Fields

The corrected `CjTrackingWebhookControllerTest` must use inline payloads matching the same verified webhook structure as AC-3.

**What all 8 tests must assert:**

- `validPayload` constant (L37) must be updated to match verified structure.
- Dedup key format `cj:{orderId}:{trackingNumber}` must use the verified field names to extract values.
- All 4 edge-case tests (`missing trackingNumber`, `missing orderId`, `missing params node`, `empty params node`) must use verified structure with appropriate fields missing/null.
- Response statuses remain `"accepted"`, `"already_processed"`, `"ignored"` — these are internal to our controller, not API-dependent.
- `WebhookEvent` topic `"tracking/update"` and channel `"cj"` remain unchanged.

### AC-5: CjWebhookTokenVerificationFilter — Auth Mechanism

The corrected `CjWebhookTokenVerificationFilterTest` depends entirely on the verified auth mechanism from `cj_shopping_api.yaml`:

**Scenario A: Bearer token confirmed correct**
- All 8 existing tests remain unchanged.
- Add a code comment `// x-cj-verified: true — CJ webhook uses Bearer token in Authorization header` to the test class.

**Scenario B: Auth mechanism differs (e.g., HMAC, custom header)**
- All 8 tests must be rewritten to test the new mechanism.
- The constant-time comparison test (`different length tokens rejected`) must be preserved regardless of mechanism.
- The `blank configured secret rejects all requests` and `whitespace-only configured secret rejects all requests` tests must be preserved (defense-in-depth).

### AC-6: CjCarrierMapper — Carrier Name Strings

The corrected `CjCarrierMapperTest` must cover all carrier names documented in CJ's API:

**Current carrier mapping (9 entries, all PENDING_VERIFICATION):**

| Map key (lowercase) | Normalized output | Verified? |
|---|---|---|
| `usps` | `USPS` | PENDING_VERIFICATION |
| `ups` | `UPS` | PENDING_VERIFICATION |
| `fedex` | `FedEx` | PENDING_VERIFICATION |
| `dhl` | `DHL` | PENDING_VERIFICATION |
| `4px` | `4PX` | PENDING_VERIFICATION |
| `yanwen` | `Yanwen` | PENDING_VERIFICATION |
| `yunexpress` | `YunExpress` | PENDING_VERIFICATION |
| `cainiao` | `Cainiao` | PENDING_VERIFICATION |
| `ems` | `EMS` | PENDING_VERIFICATION |

**What 14 tests must assert after verification:**

- One test per verified carrier name mapping (add tests for new carriers, remove tests for carriers CJ does not use).
- `unknown carrier passes through unchanged`: unchanged — passthrough behavior is independent of verified carrier list.
- `case insensitivity` tests: preserved — the mapper lowercases before lookup regardless of CJ's casing.
- `empty string passes through unchanged`: unchanged.

### AC-7: CjTrackingWebhookIntegrationTest — End-to-End Payload

The corrected `CjTrackingWebhookIntegrationTest` must produce payloads matching the verified structure:

- `buildCjTrackingPayload()` method (L84-94) currently produces `{"params":{"orderId":"...","trackingNumber":"...","logisticName":"..."}}`. If verification reveals additional required envelope fields (`messageId`, `type`, `messageType`, `openId`), they must be added.
- All 3 integration tests verify the logical chain (webhook -> markShipped -> OrderShipped -> Shopify); the assertions on `OrderStatus.SHIPPED`, `trackingNumber`, and `carrier` are business-logic assertions that remain unchanged.
- Note: this test uses bare `ObjectMapper()` (L57). Per CLAUDE.md #20, this is acceptable in tests that construct adapters directly. No change needed.

### AC-8: Build Gate

`./gradlew clean test` must pass with zero failures across all modules after reconciliation. The `clean` ensures no stale compiled fixtures in `build/` or `bin/` produce false passes (NFR-5).

---

## Fixture Data

### WireMock Fixture: `create-order-success.json`

**Current shape:**
```json
{
  "code": 200,
  "result": true,
  "message": "success",
  "data": {
    "orderId": "2011152148163605",
    "orderNum": "CJ2011152148163605"
  },
  "requestId": "a12bc34d-5678-90ef-ghij-klmnopqrstuv"
}
```

**What it SHOULD contain after verification:**
- Envelope fields (`code`, `result`, `message`, `requestId`) matching the verified `cj_shopping_api.yaml` response schema.
- `data` object with the verified field names for order ID and order number. If CJ uses `orderId` and `orderNum`, no change. If CJ uses different field names (e.g., `orderUuid`, `cjOrderNumber`), update accordingly. PENDING_VERIFICATION.
- `_comment` and `_comment_verified` headers per FR-027 convention:
  ```json
  {
    "_comment": "Source: https://developers.cjdropshipping.cn/en/api/api2/api/order.html",
    "_comment_verified": "2026-04-XX against CJ Shopping API documentation",
    ...
  }
  ```

### WireMock Fixture: `create-order-auth-failure.json`

**Current shape:**
```json
{
  "code": 1600001,
  "result": false,
  "message": "Invalid API key or access token",
  "data": null,
  "requestId": "..."
}
```

**What it SHOULD contain:** Error code PENDING_VERIFICATION. Message text PENDING_VERIFICATION. Envelope shape (`code`, `result`, `message`, `data: null`, `requestId`) PENDING_VERIFICATION.

### WireMock Fixture: `create-order-invalid-address.json`

**Current shape:**
```json
{
  "code": 1600502,
  "result": false,
  "message": "invalid shipping address",
  "data": null,
  "requestId": "..."
}
```

**What it SHOULD contain:** Error code `1600502` PENDING_VERIFICATION. Message text PENDING_VERIFICATION.

### WireMock Fixture: `create-order-null-fields.json`

**Current shape:**
```json
{
  "code": 200,
  "result": true,
  "message": "success",
  "data": {
    "orderId": null,
    "orderNum": null
  },
  "requestId": "..."
}
```

**What it SHOULD contain:** Verify whether CJ actually returns `code: 200` with `data.orderId: null`. PENDING_VERIFICATION. If this is unrealistic, adjust to a real edge case. The test that uses this fixture (`null orderId in success response returns Failure via NullNode guard`) tests a defensive code path — even if CJ never returns this shape, the NullNode guard is valuable. If unrealistic, add a `_comment_note` explaining the fixture is a defensive edge case.

### WireMock Fixture: `create-order-out-of-stock.json`

**Current shape:**
```json
{
  "code": 1600501,
  "result": false,
  "message": "product out of stock",
  "data": null,
  "requestId": "..."
}
```

**What it SHOULD contain:** Error code `1600501` PENDING_VERIFICATION. Message text PENDING_VERIFICATION.

### WireMock Fixture: `error-401.json` (portfolio module)

**Current shape:** Same as `create-order-auth-failure.json`. Must be reconciled in parallel. Error code `1600001` PENDING_VERIFICATION.

### WireMock Fixture: `error-429.json` (portfolio module)

**Current shape:**
```json
{
  "code": 1600200,
  "result": false,
  "message": "Too much request",
  "data": null,
  "requestId": "..."
}
```

**What it SHOULD contain:** Error code `1600200` PENDING_VERIFICATION. Message text "Too much request" vs "Too many requests" PENDING_VERIFICATION.

### All Fixtures — Documentation Headers

Every CJ fixture file must include:
```json
{
  "_comment": "Source: https://developers.cjdropshipping.cn/en/api/api2/api/order.html",
  "_comment_verified": "2026-04-XX against CJ Shopping API documentation / live API",
  ...remaining fields...
}
```

---

## Boundary Cases

### BC-1: JSON `null` for every external API field (NullNode guard per CLAUDE.md #17)

Every `get()?.asText()` call on external JSON payloads must use the NullNode guard: `?.let { if (!it.isNull) it.asText() else null }`.

**CjSupplierOrderAdapter response parsing (3 fields):**
- `root.get("result")?.let { if (!it.isNull) it.asBoolean() else null }` -- test: verified by existing inline response tests with `"result": null` edge case (implicit via `null data node` test).
- `root.get("code")?.let { if (!it.isNull) it.asInt() else null }` -- test: verified by code path (non-200 code triggers Failure).
- `data?.get("orderId")?.let { if (!it.isNull) it.asText() else null }` -- test: `null orderId in success response returns Failure via NullNode guard` (uses `create-order-null-fields.json` fixture).

**CjTrackingProcessingService webhook parsing (3 fields):**
- `params.get("orderId")?.let { if (!it.isNull) it.asText() else null }` -- test: `NullNode orderId in JSON is treated as null`.
- `params.get("trackingNumber")?.let { if (!it.isNull) it.asText() else null }` -- test: `NullNode trackingNumber in JSON is treated as null`.
- `params.get("logisticName")?.let { if (!it.isNull) it.asText() else null }` -- test: `null logisticName defaults to unknown carrier`.

**CjTrackingWebhookController webhook parsing (2 fields):**
- `params?.get("orderId")?.let { if (!it.isNull) it.asText() else null }` -- test: `missing orderId returns ignored` (uses `orderId: null`).
- `params?.get("trackingNumber")?.let { if (!it.isNull) it.asText() else null }` -- test: `missing trackingNumber returns ignored` (uses `trackingNumber: null`).

All NullNode guard tests must be preserved regardless of field name changes from verification.

### BC-2: Missing fields in webhook payloads

**CjTrackingProcessingService:**
- Missing `params` node entirely: test `missing params node` -- returns early with warning log.
- Missing `orderId` within `params`: test `missing orderId in payload` -- skips processing, no repo call.
- Missing `trackingNumber` within `params`: test `missing trackingNumber in payload` -- skips processing, no repo call.
- Missing `logisticName` within `params`: handled by null-coalescing to `"unknown"` carrier (test: `null logisticName defaults to unknown carrier`).

**CjTrackingWebhookController:**
- Missing `params` node: test `missing params node returns ignored`.
- Empty `params` node (`{}`): test `empty params node returns ignored`.
- Missing `orderId`: test `missing orderId returns ignored`.
- Missing `trackingNumber`: test `missing trackingNumber returns ignored`.

### BC-3: Unknown carrier names in CjCarrierMapper

- Test `unknown carrier passes through unchanged`: input `"SomeObscureCarrier"` returns `"SomeObscureCarrier"` unchanged.
- Test `empty string passes through unchanged`: input `""` returns `""`.
- The mapper lowercases input before lookup, so any unrecognized string (including casing variants not in the map) passes through unchanged.

### BC-4: Invalid UUID in orderId

- Test `invalid UUID orderId` in `CjTrackingProcessingServiceTest`: `UUID.fromString()` throws `IllegalArgumentException`, caught and logged, processing skipped.

### BC-5: markShipped failure after dedup commit

- Test `markShipped failure is caught and logged - does not propagate`: runtime exception during `markShipped()` is caught, logged at ERROR with dedup key, does not propagate. Per CLAUDE.md #19.

---

## E2E Playbook Scenarios

### E2E-1: CJ Order Placement with Verified Request Body

**Flow:** `CjSupplierOrderAdapter.placeOrder()` -> `POST /api2.0/v1/shopping/order/createOrderV2` -> parse response -> return `SupplierOrderResult`

**Test class:** `CjSupplierOrderAdapterWireMockTest`

**Scenarios:**

1. **Success path:** WireMock returns `create-order-success.json`. Adapter parses `data.orderId` (verified field name) and returns `SupplierOrderResult.Success(supplierOrderId = "2011152148163605")`.

2. **Error paths:** WireMock returns each error fixture. Adapter parses `result: false` and `message` field, returns `SupplierOrderResult.Failure(reason = <verified message>)`.

3. **Request body verification:** WireMock captures request. Test asserts:
   - Header `CJ-Access-Token` present with correct value.
   - Body contains all verified field names with correct values from `SupplierOrderRequest`.
   - `products` array with `vid` (verified field) and `quantity`.
   - `fromCountryCode` defaults to `"CN"` when `warehouseCountryCode` is null, uses provided value otherwise.
   - `logisticName` included when configured, omitted when blank.

4. **Address concatenation:** `addressLine1 + " " + addressLine2` sent as single string in the verified address field. Null `addressLine2` sends only `addressLine1`.

5. **HTTP error propagation:** 401 -> `HttpClientErrorException.Unauthorized`, 500 -> `HttpServerErrorException.InternalServerError` (propagated to Resilience4j for retry/circuit-breaking).

6. **Credential guard:** Blank `baseUrl` or `accessToken` returns `Failure("CJ API credentials not configured")` with zero HTTP calls.

### E2E-2: CJ Tracking Webhook Receipt and Processing

**Flow:** HTTP POST `/webhooks/cj/tracking` -> `CjWebhookTokenVerificationFilter` (auth) -> `CjTrackingWebhookController` (dedup + event publish) -> `CjTrackingProcessingService` (async, after-commit) -> `OrderService.markShipped()`

**Test classes:** `CjTrackingWebhookControllerTest`, `CjTrackingProcessingServiceTest`, `CjTrackingWebhookIntegrationTest`

**Scenarios:**

1. **Happy path (integration):** Confirmed order exists. CJ webhook with valid tracking info arrives. Controller dedup-checks, persists webhook event, publishes `CjTrackingReceivedEvent`. Processing service parses payload, finds order, normalizes carrier via `CjCarrierMapper`, calls `markShipped()`. Order transitions to SHIPPED. `OrderShipped` event fires. Shopify fulfillment sync triggered.

2. **Dedup - already processed:** `webhookEventRepository.existsByEventId(dedupKey)` returns true. Controller returns `{"status": "already_processed"}`. No event published.

3. **Dedup - concurrent duplicate:** `existsByEventId` returns false but `webhookEventPersister.tryPersist()` returns false (another thread won the race). Controller returns `{"status": "already_processed"}`. No event published.

4. **Missing required fields:** Missing `orderId` or `trackingNumber` in payload -> controller returns `{"status": "ignored"}`, no dedup check, no event. Processing service also guards independently — missing fields -> skip with warning log.

5. **Order not found:** Processing service receives event for unknown order UUID -> skip with warning log.

6. **Order wrong status:** Order exists but not in CONFIRMED status -> skip with warning log.

7. **Carrier normalization:** `logisticName` from webhook is normalized through `CjCarrierMapper` before passing to `markShipped()`.

8. **markShipped failure:** Exception during `markShipped()` is caught, logged at ERROR, does not propagate. Dedup record already committed means CJ retries will be blocked.

### E2E-3: CJ Webhook Auth Verification

**Flow:** HTTP request -> `CjWebhookTokenVerificationFilter.doFilter()` -> pass/reject

**Test class:** `CjWebhookTokenVerificationFilterTest`

**Scenarios:**

1. **Valid token:** Bearer token matches configured secret (constant-time comparison via `MessageDigest.isEqual()`). Request wrapped in `CachingRequestWrapper` and passed to filter chain. Response body readable from wrapper.

2. **Invalid token:** Bearer token does not match. 401 response with `{"error": "Invalid webhook token"}`. Request NOT passed to chain.

3. **Missing header:** No `Authorization` header. 401 response. Request NOT passed to chain.

4. **No Bearer prefix:** Token present without `Bearer ` prefix. 401 response.

5. **Blank secret configured:** All requests rejected with 401 and `"CJ webhook secret not configured"`.

6. **Whitespace padding:** `Bearer   <token>   ` is trimmed and accepted. Defense against minor formatting inconsistencies.

7. **Timing attack resistance:** Different-length tokens rejected via same constant-time comparison (no short-circuit).

---

## Contract Test Candidates

### CjCarrierMapper

**Status: Can be tested now.**

`CjCarrierMapper` is a pure mapping function with no external dependencies. The current 14 tests validate the existing 9-entry mapping table plus case insensitivity, unknown carrier passthrough, and empty string handling.

After Layer 1 verification:
- If carrier strings change, update the mapping entries and their test cases.
- If new carrier names are documented, add entries and test cases.
- If carrier names are removed, remove entries and test cases.
- Case insensitivity tests and passthrough behavior are mapper-internal and not API-dependent.

### All Other Classes

**Status: Cannot be contract-tested until Layer 1 completes.**

- `CjSupplierOrderAdapter`: request body field names and response parsing depend on verified `createOrderV2` spec.
- `CjTrackingProcessingService`: webhook field names depend on verified webhook payload spec.
- `CjTrackingWebhookController`: same webhook structure dependency.
- `CjWebhookTokenVerificationFilter`: auth mechanism depends on verified webhook auth spec.
- All WireMock fixtures: response shapes depend on verified API responses.

No contract test files should be written or modified until the verified `cj_shopping_api.yaml` spec is produced in Layer 1.

---

## CLAUDE.md Constraint Verification Checklist

These constraints must be verified in every touched file during Phase 5:

| # | Constraint | Files affected | How to verify |
|---|---|---|---|
| 15 | Jackson `get()` vs `path()` | CjSupplierOrderAdapter, CjTrackingProcessingService, CjTrackingWebhookController | All field extractions use `get()` (not `path()`) for null-coalescing |
| 17 | NullNode guard | Same 3 files | Every `get()?.asText()` uses `?.let { if (!it.isNull) it.asText() else null }` pattern |
| 20 | No bare ObjectMapper | CjSupplierOrderAdapter, CjTrackingProcessingService, CjTrackingWebhookController | Production code injects Spring-managed `ObjectMapper`; test code can use `jacksonObjectMapper()` |
| 6 | AFTER_COMMIT + REQUIRES_NEW + @Async | CjTrackingProcessingService | All three annotations present on `onCjTrackingReceived()` |
| 19 | Try-catch after dedup commit | CjTrackingProcessingService | `markShipped()` wrapped in try-catch with ERROR logging |

---

## Test Count Summary

| Test class | Current count | Expected count after reconciliation |
|---|---|---|
| `CjSupplierOrderAdapterWireMockTest` | 16 | 16 (no new tests unless verification reveals uncovered fields) |
| `CjTrackingProcessingServiceTest` | 13 | 13 |
| `CjTrackingWebhookControllerTest` | 8 | 8 |
| `CjWebhookTokenVerificationFilterTest` | 8 | 8 (or rewritten if auth mechanism changes) |
| `CjTrackingWebhookIntegrationTest` | 3 | 3 |
| `CjCarrierMapperTest` | 14 | 14 +/- (depends on verified carrier list) |
| **Total** | **62** | **62 +/-** |

---

## Verification Gate

Phase 5 is complete when:

1. `docs/api/cj_shopping_api.yaml` exists with `x-cj-verified` markers on all endpoints/schemas.
2. Every CJ field name in production code traces to a field in `cj_product_api.yaml` or `cj_shopping_api.yaml`.
3. Every WireMock fixture has `_comment` and `_comment_verified` headers.
4. Every WireMock fixture matches the verified response schema.
5. `./gradlew clean test` passes with zero failures.
6. All PENDING_VERIFICATION markers in this spec have been resolved by Layer 1 findings.
