# FR-026: CJ Tracking Webhook + Shopify Fulfillment Sync -- Test Specification

## Acceptance Criteria

Each criterion maps directly to a success criterion from spec.md and specifies the exact assertions Phase 5 tests must make.

### AC-1: CJ Webhook Endpoint Operational (SC-1)

- `POST /webhooks/cj/tracking` with a valid CJ LOGISTIC webhook payload returns HTTP 200.
- Response body contains `{"status": "accepted"}`.
- `WebhookEventPersister.tryPersist()` is called with a `WebhookEvent` where `eventId == "cj:{cjOrderId}:{trackNumber}"`, `topic == "tracking/update"`, `channel == "cj"`.
- `ApplicationEventPublisher.publishEvent()` is called with a `CjTrackingReceivedEvent` carrying the raw payload and the dedup key.

### AC-2: Order Matched and Shipped (SC-2)

- When `CjTrackingProcessingService.onTrackingReceived()` processes a valid `CjTrackingReceivedEvent`:
  - `orderRepository.findById(UUID)` is called with the UUID parsed from `params.orderId`.
  - `orderService.markShipped(orderId, trackNumber, normalizedCarrier)` is called with exact values from the payload.
  - After `markShipped()`, the order has `status == SHIPPED`, `shipmentDetails.trackingNumber == "1Z999AA10123456784"`, `shipmentDetails.carrier == "UPS"`.

### AC-3: OrderShipped Event Published (SC-3)

- `OrderService.markShipped()` calls `eventPublisher.publishEvent()` with an `OrderShipped` event.
- The event contains: `orderId == order.orderId()`, `skuId == order.skuId()`, `trackingNumber == "1Z999AA10123456784"`, `carrier == "UPS"`.
- `occurredAt` is non-null and approximately `Instant.now()`.

### AC-4: Shopify Fulfillment Created (SC-4)

- `ShopifyFulfillmentSyncListener.onOrderShipped()` receives the `OrderShipped` event.
- It looks up the order by `event.orderId.value`.
- It calls `shopifyFulfillmentAdapter.createFulfillment(channelOrderId, trackingNumber, carrier)` with the order's `channelOrderId`, the event's tracking number, and carrier.

### AC-5: Shopify Fulfillment API Call (SC-4, SC-5)

- The `ShopifyFulfillmentAdapter` POSTs to `/admin/api/2024-01/graphql.json`.
- The request includes header `X-Shopify-Access-Token: {configured-token}`.
- The request body contains a `fulfillmentCreateV2` GraphQL mutation with:
  - `trackingInfo.number` matching the tracking number
  - `trackingInfo.company` matching the carrier name
  - `notifyCustomer: true`

### AC-6: Deduplication Works (SC-8)

- When `webhookEventRepository.existsByEventId("cj:{cjOrderId}:{trackNumber}")` returns `true`, the controller returns HTTP 200 with `{"status": "already_processed"}`.
- `eventPublisher.publishEvent()` is never called.
- `webhookEventPersister.tryPersist()` is never called.

### AC-7: Concurrent Duplicate via Persister (SC-8)

- When `existsByEventId()` returns `false` but `tryPersist()` returns `false` (concurrent insert), the controller returns HTTP 200 with `{"status": "already_processed"}`.
- `eventPublisher.publishEvent()` is never called.

### AC-8: CJ Webhook Token Authentication (SC-1)

- Requests with a valid `Authorization: Bearer {token}` header matching the configured secret pass through the filter to the controller.
- Requests with an invalid token receive HTTP 401 with `{"error": "Invalid webhook token"}`.
- Requests with no `Authorization` header receive HTTP 401.
- When the configured secret is blank (empty string), ALL requests receive HTTP 401 with `{"error": "CJ webhook secret not configured"}`.
- Token comparison uses `MessageDigest.isEqual()` (constant-time).

### AC-9: Carrier Name Normalization (BR-6)

- `CjCarrierMapper.normalize("usps")` returns `"USPS"`.
- `CjCarrierMapper.normalize("USPS")` returns `"USPS"`.
- `CjCarrierMapper.normalize("FedEx")` returns `"FedEx"`.
- `CjCarrierMapper.normalize("fedex")` returns `"FedEx"`.
- `CjCarrierMapper.normalize("ups")` returns `"UPS"`.
- `CjCarrierMapper.normalize("4px")` returns `"4PX"`.
- `CjCarrierMapper.normalize("yanwen")` returns `"Yanwen"`.
- `CjCarrierMapper.normalize("yunexpress")` returns `"YunExpress"`.
- `CjCarrierMapper.normalize("SomeUnknownCarrier")` returns `"SomeUnknownCarrier"` (pass-through).

### AC-10: Edge Case -- Webhook with Unknown orderNumber (SC-11)

- When `params.orderId` is a valid UUID but `orderRepository.findById()` returns empty, the processing service logs a warning and returns without calling `markShipped()`.

### AC-11: Edge Case -- Order Not in CONFIRMED Status (SC-11)

- When the order exists but `order.status != CONFIRMED` (e.g., `SHIPPED`, `DELIVERED`, `PENDING`), the processing service logs a warning and returns without calling `markShipped()`.

### AC-12: Edge Case -- Missing trackNumber in Webhook (SC-11)

- When `params.trackingNumber` is `null` or absent from the CJ payload, the controller returns HTTP 200 with `{"status": "ignored"}`.
- No `WebhookEvent` is persisted and no internal event is published.

### AC-13: Edge Case -- Shopify API Failure (SC-11)

- When `shopifyFulfillmentAdapter.createFulfillment()` throws an exception, `ShopifyFulfillmentSyncListener` catches it, logs a warning, and does NOT rethrow.
- The order remains in `SHIPPED` status (the exception does not roll back the order transition because the listener is AFTER_COMMIT + REQUIRES_NEW).

### AC-14: Edge Case -- Order Without channelOrderId (SC-11, BR-11)

- When `order.channelOrderId` is `null`, `ShopifyFulfillmentSyncListener` logs a warning and returns without calling `shopifyFulfillmentAdapter.createFulfillment()`.

### AC-15: Edge Case -- Invalid UUID in orderNumber (SC-11)

- When `params.orderId` is not a valid UUID (e.g., `"not-a-uuid"`), the processing service catches the `IllegalArgumentException` from `UUID.fromString()`, logs a warning, and returns without calling `markShipped()`.

### AC-16: CachingRequestWrapper Passthrough (NFR-1)

- The `CjWebhookTokenVerificationFilter` wraps the request in `CachingRequestWrapper` before passing to the filter chain, so the controller can re-read the body.

---

## Fixture Data

### CJ LOGISTIC Webhook -- Happy Path

Based on CJ Dropshipping webhook API documentation (https://developers.cjdropshipping.com/en/api/start/webhook.html). The CJ webhook uses a `LOGISTIC` message type with tracking data inside a `params` object. The `orderId` field inside `params` is the order number sent to CJ at placement time (our internal order UUID).

```json
{
  "messageId": "msg-cj-tracking-abc123def456",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "logisticName": "UPS",
    "trackingNumber": "1Z999AA10123456784",
    "trackingStatus": 1,
    "logisticsTrackEvents": "[{\"status\":\"In Transit\",\"activity\":\"Package picked up\",\"location\":\"Shenzhen, CN\",\"eventTime\":\"2026-04-01T10:30:00Z\",\"statusDesc\":\"Shipment picked up by carrier\"}]"
  }
}
```

**Field notes (from CJ docs):**
- `messageId`: Unique message identifier (string, max 200 chars)
- `type`: Message category -- `"LOGISTIC"` for tracking updates
- `messageType`: `"UPDATE"` for status changes
- `openId`: CJ API user identifier (number)
- `params.orderId`: The `orderNumber` we sent to CJ at order placement (our internal Order UUID as string)
- `params.logisticName`: Carrier name, free-text (e.g., "UPS", "USPS", "FedEx", "4PX", "YunExpress")
- `params.trackingNumber`: Carrier tracking number (string)
- `params.trackingStatus`: Integer status code: 0=No info, 1=In transit, 12=Delivered, 13=Exception, 14=Return
- `params.logisticsTrackEvents`: JSON string array of tracking events (not parsed by our system in Phase 1)

**IMPORTANT -- Spec-to-docs reconciliation:** The spec (BR-1) uses field names `orderNumber`, `cjOrderId`, `trackNumber`, `logisticName`. The actual CJ webhook docs use `orderId`, `trackingNumber`, `logisticName` inside a `params` envelope. The implementation must use the real CJ field names from the docs. The dedup key format should use `cj:{messageId}` or `cj:{orderId}:{trackingNumber}` using the real field names.

### CJ LOGISTIC Webhook -- USPS Carrier

```json
{
  "messageId": "msg-cj-tracking-usps-789",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "logisticName": "USPS",
    "trackingNumber": "9400111899223100043697",
    "trackingStatus": 1,
    "logisticsTrackEvents": "[]"
  }
}
```

### CJ LOGISTIC Webhook -- Unknown Carrier (4PX)

```json
{
  "messageId": "msg-cj-tracking-4px-456",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "logisticName": "4PX",
    "trackingNumber": "4PX000123456789",
    "trackingStatus": 1,
    "logisticsTrackEvents": "[]"
  }
}
```

### CJ LOGISTIC Webhook -- Missing trackingNumber (null)

```json
{
  "messageId": "msg-cj-tracking-no-tracking",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": "d4e5f6a7-b8c9-0123-defa-234567890123",
    "logisticName": "UPS",
    "trackingNumber": null,
    "trackingStatus": 0,
    "logisticsTrackEvents": "[]"
  }
}
```

### CJ LOGISTIC Webhook -- Missing orderId (null)

```json
{
  "messageId": "msg-cj-tracking-no-order",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": null,
    "logisticName": "FedEx",
    "trackingNumber": "794644790138",
    "trackingStatus": 1,
    "logisticsTrackEvents": "[]"
  }
}
```

### CJ LOGISTIC Webhook -- orderId is Non-UUID String

```json
{
  "messageId": "msg-cj-tracking-bad-uuid",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": "not-a-valid-uuid",
    "logisticName": "UPS",
    "trackingNumber": "1Z999AA10123456784",
    "trackingStatus": 1,
    "logisticsTrackEvents": "[]"
  }
}
```

### CJ LOGISTIC Webhook -- logisticName is JSON null

```json
{
  "messageId": "msg-cj-tracking-null-carrier",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {
    "orderId": "e5f6a7b8-c9d0-1234-efab-345678901234",
    "logisticName": null,
    "trackingNumber": "1Z999AA10123456784",
    "trackingStatus": 1,
    "logisticsTrackEvents": "[]"
  }
}
```

### CJ LOGISTIC Webhook -- params Node Missing

```json
{
  "messageId": "msg-cj-tracking-no-params",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890
}
```

### CJ LOGISTIC Webhook -- params Fields Absent (not null, just missing)

```json
{
  "messageId": "msg-cj-tracking-empty-params",
  "type": "LOGISTIC",
  "messageType": "UPDATE",
  "openId": 1234567890,
  "params": {}
}
```

### Shopify fulfillmentCreateV2 -- Success Response

Based on Shopify Admin GraphQL API documentation (https://shopify.dev/docs/api/admin-graphql/2024-01/mutations/fulfillmentCreateV2). The mutation creates a fulfillment with tracking info and returns the fulfillment object.

```json
{
  "data": {
    "fulfillmentCreateV2": {
      "fulfillment": {
        "id": "gid://shopify/Fulfillment/4216547893",
        "status": "SUCCESS",
        "createdAt": "2026-04-02T14:30:00Z",
        "trackingInfo": [
          {
            "company": "UPS",
            "number": "1Z999AA10123456784",
            "url": "https://www.ups.com/track?tracknum=1Z999AA10123456784"
          }
        ]
      },
      "userErrors": []
    }
  }
}
```

### Shopify fulfillmentCreateV2 -- Error Response (Validation Error)

```json
{
  "data": {
    "fulfillmentCreateV2": {
      "fulfillment": null,
      "userErrors": [
        {
          "field": ["fulfillment", "lineItemsByFulfillmentOrder"],
          "message": "The fulfillment order does not exist."
        }
      ]
    }
  }
}
```

### Shopify fulfillmentCreateV2 -- Error Response (Already Fulfilled)

```json
{
  "data": {
    "fulfillmentCreateV2": {
      "fulfillment": null,
      "userErrors": [
        {
          "field": ["fulfillment"],
          "message": "All line items have already been fulfilled."
        }
      ]
    }
  }
}
```

### Shopify GraphQL -- HTTP-Level Error (Auth Failure)

```json
{
  "errors": [
    {
      "message": "[API] Invalid API key or access token (unrecognized login or wrong password)",
      "extensions": {
        "code": "ACCESS_DENIED"
      }
    }
  ]
}
```

---

## Boundary Cases

### JSON null Boundary Cases (CLAUDE.md #17 -- NullNode Guard)

Every external API field extraction must have a JSON `null` test case. The NullNode guard `?.let { if (!it.isNull) it.asText() else null }` must be applied to all fields.

| Field | Fixture | Expected Behavior |
|---|---|---|
| `params.orderId` = JSON `null` | `CJ LOGISTIC Webhook -- Missing orderId (null)` | Controller: build dedup key without orderId -- processing service: `orderId == null`, logs warning, returns early |
| `params.trackingNumber` = JSON `null` | `CJ LOGISTIC Webhook -- Missing trackingNumber (null)` | Controller: `trackNumber == null`, returns HTTP 200 `{"status": "ignored"}` |
| `params.logisticName` = JSON `null` | `CJ LOGISTIC Webhook -- logisticName is JSON null` | Processing service: `logisticName == null`, `CjCarrierMapper.normalize("unknown")` used as fallback |
| `params` node absent | `CJ LOGISTIC Webhook -- params Node Missing` | Controller: cannot extract orderId or trackingNumber, returns HTTP 200 `{"status": "ignored"}` |
| `params` node present but fields absent | `CJ LOGISTIC Webhook -- params Fields Absent` | Controller: `get("orderId")` returns `null` (Kotlin null, not NullNode), returns HTTP 200 `{"status": "ignored"}` |
| `params.trackingStatus` = JSON `null` | Not used for business logic in Phase 1 | No impact -- field not extracted |
| `params.logisticsTrackEvents` = JSON `null` | Not used for business logic in Phase 1 | No impact -- field not extracted |

### Missing Fields (Field Absent from JSON)

| Field | Expected Behavior |
|---|---|
| `params` key absent from root | Same as `params` = `null`: controller returns `{"status": "ignored"}` |
| `orderId` key absent from `params` | `get("orderId")` returns Kotlin `null`, controller dedup key cannot be built, returns `{"status": "ignored"}` |
| `trackingNumber` key absent from `params` | `get("trackingNumber")` returns Kotlin `null`, controller returns `{"status": "ignored"}` |
| `logisticName` key absent from `params` | Processing service falls back to `"unknown"` for carrier normalization |
| `messageId` key absent from root | Not used for dedup key -- no impact on business logic |

### Type Mismatches

| Scenario | Expected Behavior |
|---|---|
| `params.orderId` is a number instead of string | `asText()` converts number to string; `UUID.fromString()` will fail; processing service catches exception, logs warning, returns |
| `params.trackingNumber` is a number | `asText()` converts number to string; tracking number stored as string -- functions correctly |
| `params.trackingStatus` is a string instead of int | Not extracted in Phase 1 -- no impact |
| Root payload is not valid JSON | Jackson `readTree()` throws `JsonProcessingException`; controller should return HTTP 400 or catch and return HTTP 200 with error status |

### Order State Boundary Cases

| Scenario | Expected Behavior |
|---|---|
| Order in PENDING status | Processing service: `order.status != CONFIRMED`, logs warning, returns without calling `markShipped()` |
| Order in SHIPPED status (already shipped) | Processing service: `order.status != CONFIRMED`, logs warning, returns -- idempotent handling |
| Order in DELIVERED status | Processing service: `order.status != CONFIRMED`, logs warning, returns |
| Order in REFUNDED status | Processing service: `order.status != CONFIRMED`, logs warning, returns |
| Order in FAILED status | Processing service: `order.status != CONFIRMED`, logs warning, returns |

### Shopify Fulfillment Boundary Cases

| Scenario | Expected Behavior |
|---|---|
| `accessToken` is blank | `ShopifyFulfillmentAdapter.createFulfillment()` returns `false` without making HTTP call |
| Shopify returns `userErrors` (non-empty array) | Adapter logs warning, returns `false`; listener logs warning, does not rethrow |
| Shopify returns HTTP 401 | RestClient throws exception; `@Retry` retries up to configured max; listener catches final exception |
| Shopify returns HTTP 429 (rate limit) | RestClient throws exception; `@Retry` retries with exponential backoff |
| Shopify returns HTTP 500 | RestClient throws exception; `@Retry` retries |
| `channelOrderId` format not a valid Shopify GID | Adapter sends it as-is; Shopify returns `userErrors`; adapter returns `false` |

### Filter Boundary Cases

| Scenario | Expected Behavior |
|---|---|
| `Authorization` header with `Bearer ` prefix and valid token | Filter passes request through to controller |
| `Authorization` header with valid token but no `Bearer ` prefix | Token comparison fails after `removePrefix("Bearer ")` returns the full string; HTTP 401 |
| `Authorization` header with extra whitespace: `Bearer  {token}` | `.trim()` handles it; token matches; filter passes through |
| Empty `Authorization` header | `removePrefix("Bearer ")?.trim()` results in empty string; comparison fails; HTTP 401 |
| Configured secret is blank string | Filter rejects ALL requests with HTTP 401 `"CJ webhook secret not configured"` before checking auth header |

---

## E2E Playbook Scenarios

### Scenario E2E-CJ-1: CJ Tracking Webhook -- Full Chain

**Prerequisite:** A CONFIRMED order with `channelOrderId` set (created via Shopify webhook + CJ supplier order placement).

**Setup:**
1. Ensure a CONFIRMED order exists in the database with a known UUID and a non-null `channelOrderId`.
2. Note the order UUID for use as `params.orderId` in the CJ webhook payload.

**Action:**
```bash
curl -s -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${CJ_WEBHOOK_SECRET}" \
  -d '{
    "messageId": "msg-e2e-tracking-001",
    "type": "LOGISTIC",
    "messageType": "UPDATE",
    "openId": 1234567890,
    "params": {
      "orderId": "{ORDER_UUID}",
      "logisticName": "UPS",
      "trackingNumber": "1Z999AA10123456784",
      "trackingStatus": 1,
      "logisticsTrackEvents": "[]"
    }
  }' | python3 -m json.tool
```

**Expected outcome:**
```json
{"status": "accepted"}
```

**Verification:**
```bash
# Order should now be SHIPPED with tracking number
curl -s http://localhost:8080/api/orders/{ORDER_UUID} | python3 -m json.tool
# Expect: status=SHIPPED, trackingNumber=1Z999AA10123456784, carrier=UPS
```

**Log verification:**
- `CJ tracking processed: order={ORDER_UUID}, tracking=1Z999AA10123456784, carrier=UPS`
- `Shopify fulfillment created for order {ORDER_UUID}` (if Shopify is configured)

### Scenario E2E-CJ-2: Duplicate CJ Webhook

**Prerequisite:** E2E-CJ-1 completed successfully.

**Action:** Re-send the exact same webhook payload.

**Expected outcome:**
```json
{"status": "already_processed"}
```

**Verification:** Order status remains SHIPPED (not re-processed). No second Shopify fulfillment call in logs.

### Scenario E2E-CJ-3: CJ Webhook Without Auth Token

**Action:**
```bash
curl -s -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -d '{"messageId":"test","type":"LOGISTIC","messageType":"UPDATE","openId":123,"params":{}}' \
  -w "\nHTTP_CODE:%{http_code}\n"
```

**Expected outcome:** HTTP 401 with `{"error": "..."}`.

### Scenario E2E-CJ-4: CJ Webhook with Unknown Order UUID

**Action:** Send a CJ webhook with a random UUID that does not match any order.

**Expected outcome:** HTTP 200 with `{"status": "accepted"}`. Log shows warning: "Order not found for orderNumber {UUID}". No order status change.

### Scenario E2E-CJ-5: ShipmentTracker Picks Up SHIPPED Order

**Prerequisite:** E2E-CJ-1 completed. Wait for the next ShipmentTracker poll cycle (30 minutes, or trigger manually).

**Expected outcome:** Logs show the ShipmentTracker polling the carrier API for the tracking number. If carrier returns delivery status, order transitions to DELIVERED and `OrderFulfilled` event fires.

---

## Contract Test Candidates

These are pure domain types whose contracts can be tested against production code without requiring Spring context or database.

### 1. CjCarrierMapper -- Carrier Name Normalization

**Type:** Kotlin `object` (singleton) with static `Map<String, String>`.

**Testable contract:**
- Known CJ carrier names (case-insensitive lookup) return normalized internal names.
- Unknown carrier names pass through unchanged.
- Null input handling (if applicable -- mapper receives non-null string per processing service guard).

**Tests:**
```
CjCarrierMapper.normalize("usps") == "USPS"
CjCarrierMapper.normalize("USPS") == "USPS"
CjCarrierMapper.normalize("Usps") == "USPS"
CjCarrierMapper.normalize("ups") == "UPS"
CjCarrierMapper.normalize("UPS") == "UPS"
CjCarrierMapper.normalize("fedex") == "FedEx"
CjCarrierMapper.normalize("FedEx") == "FedEx"
CjCarrierMapper.normalize("FEDEX") == "FedEx"
CjCarrierMapper.normalize("dhl") == "DHL"
CjCarrierMapper.normalize("4px") == "4PX"
CjCarrierMapper.normalize("4PX") == "4PX"
CjCarrierMapper.normalize("yanwen") == "Yanwen"
CjCarrierMapper.normalize("yunexpress") == "YunExpress"
CjCarrierMapper.normalize("cainiao") == "Cainiao"
CjCarrierMapper.normalize("ems") == "EMS"
CjCarrierMapper.normalize("SomeUnknownCarrier") == "SomeUnknownCarrier"
CjCarrierMapper.normalize("") == ""
CjCarrierMapper.normalize("unknown") == "unknown"
```

### 2. OrderShipped Domain Event -- Structure

**Type:** Data class implementing `DomainEvent`.

**Testable contract:**
- `OrderShipped` implements `DomainEvent` (has `occurredAt: Instant`).
- All fields are accessible: `orderId: OrderId`, `skuId: SkuId`, `trackingNumber: String`, `carrier: String`.
- `occurredAt` defaults to approximately `Instant.now()` when not specified.
- Data class equality/copy works correctly.

### 3. Order State Machine -- CONFIRMED to SHIPPED Transition

**Type:** `Order.updateStatus()` using `VALID_TRANSITIONS` map.

**Testable contract (already partially covered by existing OrderFailedTransitionTest, but new assertions needed):**
- `Order(status=CONFIRMED).updateStatus(SHIPPED)` succeeds.
- `Order(status=PENDING).updateStatus(SHIPPED)` throws `IllegalArgumentException`.
- `Order(status=SHIPPED).updateStatus(SHIPPED)` throws `IllegalArgumentException`.
- `Order(status=DELIVERED).updateStatus(SHIPPED)` throws `IllegalArgumentException`.

---

## Test-by-Test Specification

### Unit Tests

#### 1. CjCarrierMapperTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/carrier/CjCarrierMapperTest.kt`

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `normalize maps usps to USPS (case insensitive)` | -- | `CjCarrierMapper.normalize("usps")` | Returns `"USPS"` |
| `normalize maps UPS to UPS` | -- | `CjCarrierMapper.normalize("ups")` | Returns `"UPS"` |
| `normalize maps FedEx variants to FedEx` | -- | `CjCarrierMapper.normalize("fedex")` | Returns `"FedEx"` |
| `normalize maps dhl to DHL` | -- | `CjCarrierMapper.normalize("dhl")` | Returns `"DHL"` |
| `normalize maps 4px to 4PX` | -- | `CjCarrierMapper.normalize("4px")` | Returns `"4PX"` |
| `normalize maps yanwen to Yanwen` | -- | `CjCarrierMapper.normalize("yanwen")` | Returns `"Yanwen"` |
| `normalize maps yunexpress to YunExpress` | -- | `CjCarrierMapper.normalize("yunexpress")` | Returns `"YunExpress"` |
| `normalize maps cainiao to Cainiao` | -- | `CjCarrierMapper.normalize("cainiao")` | Returns `"Cainiao"` |
| `normalize maps ems to EMS` | -- | `CjCarrierMapper.normalize("ems")` | Returns `"EMS"` |
| `normalize passes through unknown carrier name unchanged` | -- | `CjCarrierMapper.normalize("SomeUnknownCarrier")` | Returns `"SomeUnknownCarrier"` |
| `normalize is case insensitive for known carriers` | -- | `CjCarrierMapper.normalize("USPS")`, `CjCarrierMapper.normalize("Usps")` | Both return `"USPS"` |
| `normalize handles empty string` | -- | `CjCarrierMapper.normalize("")` | Returns `""` |

#### 2. CjWebhookTokenVerificationFilterTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/webhook/CjWebhookTokenVerificationFilterTest.kt`

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `valid Bearer token chains request through` | Filter with `expectedToken = "test-cj-secret"` | `doFilter()` with `Authorization: Bearer test-cj-secret` | `chain.request != null`, `response.status != 401`, chained request is `CachingRequestWrapper` |
| `invalid Bearer token returns 401` | Filter with `expectedToken = "test-cj-secret"` | `doFilter()` with `Authorization: Bearer wrong-token` | `chain.request == null`, `response.status == 401`, body contains `"Invalid webhook token"` |
| `missing Authorization header returns 401` | Filter with `expectedToken = "test-cj-secret"` | `doFilter()` with no `Authorization` header | `chain.request == null`, `response.status == 401` |
| `blank configured secret rejects all requests` | Filter with `expectedToken = ""` | `doFilter()` with `Authorization: Bearer any-value` | `chain.request == null`, `response.status == 401`, body contains `"CJ webhook secret not configured"` |
| `token without Bearer prefix fails verification` | Filter with `expectedToken = "test-cj-secret"` | `doFilter()` with `Authorization: test-cj-secret` (no Bearer prefix) | `chain.request == null`, `response.status == 401` |
| `Bearer token with extra whitespace trimmed and matches` | Filter with `expectedToken = "test-cj-secret"` | `doFilter()` with `Authorization: Bearer  test-cj-secret` | `chain.request != null` (filter trims token) |
| `chained request is CachingRequestWrapper for body re-read` | Filter with valid token | `doFilter()` with valid auth and JSON body | Chained request is `CachingRequestWrapper`, body readable from wrapper matches original body |

#### 3. CjTrackingWebhookControllerTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/webhook/CjTrackingWebhookControllerTest.kt`

Uses MockMvc standalone setup (same pattern as `ShopifyWebhookControllerTest`).

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `valid webhook returns 200 with accepted status` | `existsByEventId` returns false, `tryPersist` returns true | POST `/webhooks/cj/tracking` with happy-path fixture | HTTP 200, `{"status": "accepted"}`, `tryPersist()` called with `WebhookEvent(eventId="cj:{orderId}:{trackingNumber}", topic="tracking/update", channel="cj")`, `publishEvent()` called with `CjTrackingReceivedEvent` |
| `duplicate event via existsByEventId returns 200 already_processed` | `existsByEventId` returns true | POST with happy-path fixture | HTTP 200, `{"status": "already_processed"}`, `publishEvent()` never called, `tryPersist()` never called |
| `concurrent duplicate via persister returns 200 already_processed` | `existsByEventId` returns false, `tryPersist` returns false | POST with happy-path fixture | HTTP 200, `{"status": "already_processed"}`, `publishEvent()` never called |
| `missing trackingNumber returns 200 ignored` | -- | POST with null-trackingNumber fixture | HTTP 200, `{"status": "ignored"}`, `tryPersist()` never called, `publishEvent()` never called |
| `missing orderId returns 200 ignored` | -- | POST with null-orderId fixture | HTTP 200, `{"status": "ignored"}`, no dedup persisted, no event published |
| `missing params node returns 200 ignored` | -- | POST with no-params fixture | HTTP 200, `{"status": "ignored"}` |
| `empty params returns 200 ignored` | -- | POST with empty-params fixture | HTTP 200, `{"status": "ignored"}` |
| `dedup key format is cj colon orderId colon trackingNumber` | `existsByEventId` returns false, `tryPersist` returns true | POST with happy-path fixture | `tryPersist()` called with `argThat { eventId == "cj:a1b2c3d4-e5f6-7890-abcd-ef1234567890:1Z999AA10123456784" }` |

#### 4. CjTrackingProcessingServiceTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/CjTrackingProcessingServiceTest.kt`

Uses Mockito with mocked `OrderRepository`, `OrderService`, `ObjectMapper` (real instance).

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `happy path - parses payload and calls markShipped with correct args` | Order in CONFIRMED status, `findById` returns it | `onTrackingReceived()` with happy-path payload | `markShipped(orderId, "1Z999AA10123456784", "UPS")` called with exact values |
| `order not found logs warning and returns` | `findById` returns `Optional.empty()` | `onTrackingReceived()` with valid payload | `markShipped()` never called |
| `order not in CONFIRMED status logs warning and returns` | Order in SHIPPED status | `onTrackingReceived()` with valid payload | `markShipped()` never called |
| `order in PENDING status logs warning and returns` | Order in PENDING status | `onTrackingReceived()` with valid payload | `markShipped()` never called |
| `missing trackingNumber in payload returns early` | -- | `onTrackingReceived()` with null-trackingNumber payload | `markShipped()` never called, `findById()` never called |
| `missing orderId in payload returns early` | -- | `onTrackingReceived()` with null-orderId payload | `markShipped()` never called |
| `invalid UUID orderId returns early` | -- | `onTrackingReceived()` with bad-UUID payload | `markShipped()` never called |
| `null logisticName defaults to unknown carrier` | Order in CONFIRMED, payload has `logisticName: null` | `onTrackingReceived()` | `markShipped(orderId, trackingNumber, "unknown")` -- carrier is `CjCarrierMapper.normalize("unknown")` which passes through as `"unknown"` |
| `carrier normalization applied - usps becomes USPS` | Order in CONFIRMED, payload has `logisticName: "usps"` | `onTrackingReceived()` | `markShipped(orderId, trackingNumber, "USPS")` |
| `missing params node returns early` | -- | `onTrackingReceived()` with no-params payload | `markShipped()` never called |
| `NullNode guard on orderId - JSON null coalesces to Kotlin null` | -- | `onTrackingReceived()` with `orderId: null` in params | `markShipped()` never called (orderId is null, early return) |
| `NullNode guard on trackingNumber - JSON null coalesces to Kotlin null` | -- | `onTrackingReceived()` with `trackingNumber: null` in params | `markShipped()` never called (trackingNumber is null, early return) |
| `NullNode guard on logisticName - JSON null coalesces to Kotlin null` | Order in CONFIRMED | `onTrackingReceived()` with `logisticName: null` in params | `markShipped()` called with carrier = `"unknown"` |

#### 5. OrderServiceTest (Updated -- New Tests Only)

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/OrderServiceTest.kt`

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `markShipped publishes OrderShipped event with correct fields` | CONFIRMED order, `findById` returns it, `save` returns it | `markShipped(orderId, "1Z999AA10123456784", "UPS")` | `eventPublisher.publishEvent()` called with `argThat<OrderShipped> { orderId.value == order.id && skuId.value == order.skuId && trackingNumber == "1Z999AA10123456784" && carrier == "UPS" }` |
| `markShipped publishes OrderShipped event with occurredAt set` | Same as above | `markShipped()` | `argThat<OrderShipped> { occurredAt != null }` |

#### 6. ShopifyFulfillmentSyncListenerTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/handler/ShopifyFulfillmentSyncListenerTest.kt`

Uses Mockito with mocked `ShopifyFulfillmentAdapter` and `OrderRepository`.

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `happy path - calls adapter with channelOrderId, trackingNumber, carrier` | Order with `channelOrderId = "gid://shopify/Order/12345"`, `findById` returns it | `onOrderShipped(OrderShipped(...))` | `adapter.createFulfillment("gid://shopify/Order/12345", "1Z999AA10123456784", "UPS")` called |
| `order not found logs warning and returns` | `findById` returns `Optional.empty()` | `onOrderShipped()` | `adapter.createFulfillment()` never called |
| `null channelOrderId skips Shopify sync with warning` | Order with `channelOrderId = null` | `onOrderShipped()` | `adapter.createFulfillment()` never called |
| `adapter exception caught and does not propagate` | Order with channelOrderId, adapter throws `RuntimeException` | `onOrderShipped()` | No exception propagated from listener; test completes normally |
| `adapter exception does not prevent listener from completing` | Adapter throws `HttpServerErrorException` | `onOrderShipped()` | Method returns normally (catch block handles exception) |

#### 7. ShopifyFulfillmentAdapterWireMockTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/proxy/platform/ShopifyFulfillmentAdapterWireMockTest.kt`

Uses WireMockExtension (same pattern as `CjSupplierOrderAdapterWireMockTest` and `ShopifyPriceSyncAdapterWireMockTest`).

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `successful fulfillment returns true` | WireMock stub: POST `/admin/api/2024-01/graphql.json` returns success fixture | `createFulfillment("gid://shopify/Order/12345", "1Z999AA10123456784", "UPS")` | Returns `true` |
| `GraphQL mutation body contains fulfillmentCreateV2 with tracking info` | WireMock stub: POST returns success | `createFulfillment(...)` | Request body contains `"fulfillmentCreateV2"`, `"1Z999AA10123456784"` (tracking number), `"UPS"` (company), `"notifyCustomer":true` |
| `request includes X-Shopify-Access-Token header` | WireMock stub with header expectation | `createFulfillment(...)` | WireMock verifies `X-Shopify-Access-Token: test-access-token` header |
| `userErrors in response returns false` | WireMock stub returns error fixture | `createFulfillment(...)` | Returns `false` |
| `blank access token returns false without HTTP call` | Adapter constructed with `accessToken = ""` | `createFulfillment(...)` | Returns `false`, WireMock verifies 0 requests |
| `HTTP 401 from Shopify throws exception` | WireMock stub returns 401 | `createFulfillment(...)` | Throws `HttpClientErrorException.Unauthorized` (or similar -- Resilience4j retries may wrap it) |
| `null fulfillment in success response with empty userErrors returns false` | WireMock stub returns `{ "data": { "fulfillmentCreateV2": { "fulfillment": null, "userErrors": [] } } }` | `createFulfillment(...)` | Returns `false` |

### Integration Test

#### 8. CjTrackingWebhookIntegrationTest

**File:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/integration/CjTrackingWebhookIntegrationTest.kt`

This is a unit-level integration test (same pattern as `SupplierOrderPlacementIntegrationTest`) that wires the full chain with mocks.

| Test Name | Setup | Action | Assertions |
|---|---|---|---|
| `full chain - CJ webhook to SHIPPED to Shopify fulfillment` | In-memory order store, CONFIRMED order with `channelOrderId`, mocked `WebhookEventRepository`, `WebhookEventPersister`, `ShopifyFulfillmentAdapter` | 1. Controller receives valid payload, dedup succeeds, publishes `CjTrackingReceivedEvent`. 2. Manually invoke `CjTrackingProcessingService.onTrackingReceived()` with the captured event. 3. Manually invoke `ShopifyFulfillmentSyncListener.onOrderShipped()` with the captured `OrderShipped` event. | Order status is `SHIPPED`, tracking number is `"1Z999AA10123456784"`, carrier is `"UPS"`, Shopify adapter called with `channelOrderId`, correct tracking number and carrier |
| `full chain - order without channelOrderId skips Shopify sync` | Same setup but order has `channelOrderId = null` | Same steps 1 and 2. Step 3: invoke listener. | Order is SHIPPED, Shopify adapter never called |
| `full chain - Shopify failure does not affect order status` | Same setup, adapter throws `RuntimeException` | Same steps 1-3. | Order remains SHIPPED despite Shopify failure |

---

## CLAUDE.md Constraint Verification Checklist

Tests must verify these constraints are enforced:

| # | Constraint | Test Coverage |
|---|---|---|
| 6 | `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` on `CjTrackingProcessingService` and `ShopifyFulfillmentSyncListener` | Integration test verifies the listener annotation pattern is correct by checking that Shopify failure does not affect order SHIPPED status. Unit tests directly invoke the listener methods. |
| 13 | `@Value` with empty defaults | `CjWebhookTokenVerificationFilterTest.blank configured secret rejects all requests`, `ShopifyFulfillmentAdapterWireMockTest.blank access token returns false without HTTP call` |
| 14 | No `internal` constructors on Spring components | Verified by ArchUnit (existing rule). All new `@Component`/`@Service` classes use public constructors. |
| 15 | `get()` not `path()` for JSON field extraction | `CjTrackingProcessingServiceTest` -- all NullNode guard tests verify that JSON `null` fields coalesce to Kotlin `null` (would fail if `path()` were used, since `path().asText()` returns `""` not `null`). |
| 17 | NullNode guard on all external JSON fields | `CjTrackingProcessingServiceTest` has dedicated test cases for `orderId: null`, `trackingNumber: null`, `logisticName: null`. `CjTrackingWebhookControllerTest` has test cases for null `orderId` and null `trackingNumber` in the dedup extraction. |
