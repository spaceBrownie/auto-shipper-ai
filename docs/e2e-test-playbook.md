# E2E Test Playbook

End-to-end manual test script for the full SKU lifecycle through capital protection, compliance guards, and portfolio orchestration.
Covers: SKU creation, compliance checks, state machine, cost gate, stress test, pricing, platform listing (FR-020), Shopify webhook order creation (FR-023), CJ supplier order placement (FR-025), CJ tracking webhook + Shopify fulfillment sync (FR-026), orders, reserve management, margin monitoring, automated shutdown rules, portfolio experiments, kill window monitoring, priority ranking, demand scan job, and demand signal smoke test (FR-017).

**Last validated:** 2026-04-02 on branch `main` (FR-026 scenarios added)

---

## Prerequisites

| Requirement | Check command | Expected |
|---|---|---|
| PostgreSQL running | `pg_isready -h localhost -p 5432` | `accepting connections` |
| Database exists | `PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "SELECT 1"` | `1` |
| App running (local profile) | `curl -s http://localhost:8080/actuator/health` | `{"status":"UP"}` |

### Start the application

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Wait ~10s for startup, then verify:
```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

### Reset test data (optional)

If re-running the playbook, truncate all tables first:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  TRUNCATE TABLE capital_rule_audit, margin_snapshots, capital_order_records,
                 reserve_accounts, sku_state_history, orders, skus,
                 sku_cost_envelopes, sku_stress_test_results, sku_prices, sku_pricing_history,
                 vendors, vendor_sku_assignments, vendor_breach_log,
                 compliance_audit, experiments, kill_recommendations,
                 priority_ranking_log, scaling_flags, refund_alerts, discovery_blacklist,
                 demand_candidates, candidate_rejections, demand_scan_runs,
                 platform_listings, webhook_events
  CASCADE;
"
```

---

## Phase 1: SKU Lifecycle (Ideation to Listed)

This phase walks a SKU through the full state machine. Each step has a single curl command and expected output.

### 1.1 Create SKU

```bash
curl -s -X POST http://localhost:8080/api/skus \
  -H "Content-Type: application/json" \
  -d '{"name":"E2E Test SKU","category":"Electronics"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `currentState` | `IDEATION` |

Save the `id` — all subsequent commands use it:
```bash
SKU_ID="<id from response>"
```

### 1.2 Compliance Check (FR-011 gate)

Run compliance checks on the SKU while it's in `IDEATION`. This tests all 4 check services (IP, claims, processor, sourcing) running concurrently.

**Passing case** — "E2E Test SKU" in "Electronics" should clear all checks:

```bash
curl -s -X POST "http://localhost:8080/api/compliance/skus/$SKU_ID/check" \
  -H "Content-Type: application/json" \
  -d '{"productDescription":"High-quality wireless Bluetooth speaker with 20-hour battery life"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `latestResult` | `CLEARED` |
| `auditHistory` | 4 entries, all with `result: "CLEARED"` |
| HTTP status | 200 |

**Side effect:** `ComplianceCleared` event fires → `CatalogComplianceListener` auto-transitions SKU to `VALIDATION_PENDING`. Verify:
```bash
curl -s "http://localhost:8080/api/skus/$SKU_ID" | python3 -m json.tool
# currentState → "VALIDATION_PENDING"
```

**Checkpoint:** If SKU is still in `IDEATION`, the `CatalogComplianceListener` AFTER_COMMIT + REQUIRES_NEW pattern is broken.

### 1.2b Compliance Check — Failing Case (optional, separate SKU)

To test the failure path, create a separate SKU with a trademarked name:

```bash
# Create SKU with trademarked name
curl -s -X POST http://localhost:8080/api/skus \
  -H "Content-Type: application/json" \
  -d '{"name":"Nike Air Max Clone","category":"Footwear"}' | python3 -m json.tool

FAIL_SKU_ID="<id from response>"

# Run compliance — should fail IP check
curl -s -X POST "http://localhost:8080/api/compliance/skus/$FAIL_SKU_ID/check" \
  -H "Content-Type: application/json" \
  -d '{"productDescription":"Athletic shoes"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `latestResult` | `FAILED` |
| `latestReason` | `IP_INFRINGEMENT` |

**Side effect:** `ComplianceFailed` event → SKU terminated with `COMPLIANCE_VIOLATION`.
```bash
curl -s "http://localhost:8080/api/skus/$FAIL_SKU_ID" | python3 -m json.tool
# currentState → "TERMINATED", terminationReason → "COMPLIANCE_VIOLATION"
```

### 1.2c Verify Compliance Audit History

```bash
curl -s "http://localhost:8080/api/compliance/skus/$SKU_ID" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `auditHistory` length | 4 (one per check type) |
| Each entry `checkType` | `IP_CHECK`, `CLAIMS_CHECK`, `PROCESSOR_CHECK`, `SOURCING_CHECK` |

### 1.2d Compliance Re-Check (Cross-SKU Isolation)

Verify that compliance failures on one SKU do not contaminate another SKU's compliance results.

**Step 1:** Create a SKU with a trademarked name and run compliance (should fail):

```bash
curl -s -X POST http://localhost:8080/api/skus \
  -H "Content-Type: application/json" \
  -d '{"name":"Adidas Ultraboost Replica","category":"Footwear"}' | python3 -m json.tool

CONTAMINATION_FAIL_SKU_ID="<id from response>"

curl -s -X POST "http://localhost:8080/api/compliance/skus/$CONTAMINATION_FAIL_SKU_ID/check" \
  -H "Content-Type: application/json" \
  -d '{"productDescription":"Athletic running shoes"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `latestResult` | `FAILED` |

Verify SKU is terminated:
```bash
curl -s "http://localhost:8080/api/skus/$CONTAMINATION_FAIL_SKU_ID" | python3 -m json.tool
# currentState -> "TERMINATED", terminationReason -> "COMPLIANCE_VIOLATION"
```

**Step 2:** Create a NEW SKU with a clean name and run compliance (should pass):

```bash
curl -s -X POST http://localhost:8080/api/skus \
  -H "Content-Type: application/json" \
  -d '{"name":"Portable LED Desk Lamp","category":"Home Office"}' | python3 -m json.tool

CLEAN_SKU_ID="<id from response>"

curl -s -X POST "http://localhost:8080/api/compliance/skus/$CLEAN_SKU_ID/check" \
  -H "Content-Type: application/json" \
  -d '{"productDescription":"Adjustable brightness LED lamp with USB charging port"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `latestResult` | `CLEARED` |

Verify the clean SKU advanced to VALIDATION_PENDING (not affected by the failed SKU):
```bash
curl -s "http://localhost:8080/api/skus/$CLEAN_SKU_ID" | python3 -m json.tool
# currentState -> "VALIDATION_PENDING"
```

**Step 3:** Query compliance audit for the clean SKU and verify no contamination:

```bash
curl -s "http://localhost:8080/api/compliance/skus/$CLEAN_SKU_ID" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `latestResult` | `CLEARED` |
| `auditHistory` | 4 entries, all `CLEARED` — no `FAILED` entries from the other SKU |

**Step 4:** Query the failed SKU audit to confirm it still shows failure:

```bash
curl -s "http://localhost:8080/api/compliance/skus/$CONTAMINATION_FAIL_SKU_ID" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `latestResult` | `FAILED` |
| `auditHistory` | Contains at least one `FAILED` entry with `IP_INFRINGEMENT` |

**Checkpoint:** If the clean SKU's audit contains any FAILED entries, compliance state is leaking between SKUs.

### 1.3 Advance: VALIDATION_PENDING to COST_GATING

**Note:** If step 1.2 auto-advanced the SKU to `VALIDATION_PENDING` via the compliance listener, skip the manual transition below and go straight to 1.3.

```bash
curl -s -X POST "http://localhost:8080/api/skus/$SKU_ID/state" \
  -H "Content-Type: application/json" \
  -d '{"state":"COST_GATING"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `currentState` | `COST_GATING` |

### 1.4 Verify Cost Envelope

This calls the cost gate with local stub providers (no external API keys needed).

```bash
curl -s -X POST "http://localhost:8080/api/skus/$SKU_ID/verify-costs" \
  -H "Content-Type: application/json" \
  -d '{
    "vendorQuote": {"amount": 25.00, "currency": "USD"},
    "packageDimensions": {"lengthCm": 30, "widthCm": 20, "heightCm": 15, "weightKg": 1.2},
    "origin": {"street": "123 Supplier St", "city": "Los Angeles", "stateOrProvince": "CA", "postalCode": "90210", "countryCode": "US"},
    "destination": {"street": "456 Customer Ave", "city": "New York", "stateOrProvince": "NY", "postalCode": "10001", "countryCode": "US"},
    "cacEstimate": {"amount": 8.00, "currency": "USD"},
    "jurisdiction": "US",
    "warehouseCost": {"amount": 2.00, "currency": "USD"},
    "customerServiceCost": {"amount": 1.50, "currency": "USD"},
    "packagingCost": {"amount": 0.75, "currency": "USD"},
    "returnHandlingCost": {"amount": 3.00, "currency": "USD"},
    "refundAllowanceRatePercent": 5.0,
    "chargebackAllowanceRatePercent": 2.0,
    "taxesAndDuties": {"amount": 1.00, "currency": "USD"},
    "estimatedOrderValue": {"amount": 89.99, "currency": "USD"}
  }' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `fullyBurdened` | ~58.45 (varies slightly with stub rates) |
| `verifiedAt` | Non-null timestamp |
| HTTP status | 200 |

**Side effect:** SKU auto-transitions to `STRESS_TESTING`. Verify:
```bash
curl -s "http://localhost:8080/api/skus/$SKU_ID" | python3 -m json.tool
# currentState → "STRESS_TESTING"
```

### 1.5 Run Stress Test

The estimated price must be high enough for stressed margins to clear 50% gross / 30% net.
With the cost envelope above, `$199.99` works; `$89.99` fails.

```bash
curl -s -X POST "http://localhost:8080/api/skus/$SKU_ID/stress-test" \
  -H "Content-Type: application/json" \
  -d '{"estimatedPriceAmount": 199.99, "currency": "USD"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `passed` | `true` |
| `grossMarginPercent` | ~62% |
| `netMarginPercent` | ~62% |

**Side effect:** SKU auto-transitions to `LISTED`.

### 1.6 Verify LISTED State and Pricing Initialization

```bash
# SKU state
curl -s "http://localhost:8080/api/skus/$SKU_ID" | python3 -m json.tool
# currentState → "LISTED"

# Pricing (initialized via PricingInitializer AFTER_COMMIT listener)
curl -s "http://localhost:8080/api/skus/$SKU_ID/pricing" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `currentState` | `LISTED` |
| Pricing `currentPrice` | `199.99` |
| Pricing `history[0].signalType` | `INITIAL` |
| Pricing HTTP status | `200` (not 404) |

**Checkpoint:** If pricing returns 404, the `PricingInitializer` AFTER_COMMIT listener is broken (see PM-001).

### 1.7 Verify Platform Listing (FR-020)

The `PlatformListingListener` fires on the same `SkuStateChanged(toState=LISTED)` event via `AFTER_COMMIT` + `REQUIRES_NEW`. It creates a `platform_listings` record with the stub adapter's deterministic external IDs.

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT sku_id, platform, external_listing_id, external_variant_id, current_price_amount, currency, status FROM platform_listings WHERE sku_id = '$SKU_ID';"
```

| Column | Expected |
|---|---|
| `platform` | `SHOPIFY` |
| `external_listing_id` | Non-null UUID (stub deterministic) |
| `external_variant_id` | Non-null UUID (stub deterministic) |
| `current_price_amount` | `199.9900` |
| `currency` | `USD` |
| `status` | `ACTIVE` |

**Checkpoint:** If no row exists, the `PlatformListingListener` AFTER_COMMIT + REQUIRES_NEW pattern is broken.

---

## Phase 1b: Shopify Webhook Order Creation (FR-023)

This phase tests the Shopify `orders/create` webhook flow: HMAC verification, deduplication, order creation, and idempotency. Requires Phase 1 to complete (SKU must be LISTED with an active platform listing).

### Prerequisites

Set the webhook secret in the environment before starting the application:

```bash
export SHOPIFY_WEBHOOK_SECRETS="e2e-test-webhook-secret"
```

Restart the application if it was already running:

```bash
pkill -f "bootRun"
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

### 1b.1 Compute HMAC for Test Payload

The test payload must be signed with the configured secret. Compute the HMAC using `openssl`:

```bash
# Define the test payload
WEBHOOK_PAYLOAD='{
  "id": 820982911946154508,
  "name": "#1001",
  "currency": "USD",
  "customer": {"email": "buyer@example.com"},
  "line_items": [{
    "product_id": 788032119674292922,
    "variant_id": 788032119674292923,
    "quantity": 1,
    "price": "199.99",
    "title": "E2E Test SKU"
  }]
}'

# Compute HMAC-SHA256 and Base64-encode
HMAC=$(printf '%s' "$WEBHOOK_PAYLOAD" | openssl dgst -sha256 -hmac "e2e-test-webhook-secret" -binary | openssl base64)

echo "HMAC: $HMAC"
```

### 1b.2 Send Valid Webhook

```bash
curl -s -X POST http://localhost:8080/webhooks/shopify/orders \
  -H "Content-Type: application/json" \
  -H "X-Shopify-Topic: orders/create" \
  -H "X-Shopify-Event-Id: e2e-webhook-001" \
  -H "X-Shopify-Hmac-SHA256: $HMAC" \
  -d "$WEBHOOK_PAYLOAD" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `accepted` |
| HTTP status | `200` |

### 1b.3 Verify Webhook Event Persisted

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT event_id, topic, channel FROM webhook_events WHERE event_id = 'e2e-webhook-001';"
```

| Column | Expected |
|---|---|
| `event_id` | `e2e-webhook-001` |
| `topic` | `orders/create` |
| `channel` | `shopify` |

### 1b.4 Verify Internal Order Created

If the platform listing from Phase 1.7 has `external_listing_id` matching the webhook's `product_id`, an internal Order should be created in PENDING status:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT id, status, channel, channel_order_id, channel_order_number FROM orders WHERE channel = 'shopify' AND channel_order_id = '820982911946154508';"
```

| Column | Expected |
|---|---|
| `status` | `PENDING` |
| `channel` | `shopify` |
| `channel_order_id` | `820982911946154508` |
| `channel_order_number` | `#1001` |

**Note:** If no order is created, check that `PlatformListingResolver` can resolve the `product_id` from the webhook to an internal SKU. The stub adapter's deterministic IDs may not match the test payload's `product_id` — adjust the payload accordingly.

### 1b.5 Verify Idempotency (Send Same Webhook Again)

```bash
curl -s -X POST http://localhost:8080/webhooks/shopify/orders \
  -H "Content-Type: application/json" \
  -H "X-Shopify-Topic: orders/create" \
  -H "X-Shopify-Event-Id: e2e-webhook-001" \
  -H "X-Shopify-Hmac-SHA256: $HMAC" \
  -d "$WEBHOOK_PAYLOAD" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `already_processed` |
| HTTP status | `200` |

Verify no duplicate order was created:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT COUNT(*) FROM orders WHERE channel = 'shopify' AND channel_order_id = '820982911946154508';"
```

| Expected | `1` (not 2) |
|---|---|

### 1b.6 Verify HMAC Rejection (Wrong Signature)

```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/webhooks/shopify/orders \
  -H "Content-Type: application/json" \
  -H "X-Shopify-Topic: orders/create" \
  -H "X-Shopify-Event-Id: e2e-webhook-002" \
  -H "X-Shopify-Hmac-SHA256: aW52YWxpZC1zaWduYXR1cmU=" \
  -d "$WEBHOOK_PAYLOAD"
```

| Expected HTTP status | `401` |
|---|---|

Verify the rejected event was NOT persisted:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT COUNT(*) FROM webhook_events WHERE event_id = 'e2e-webhook-002';"
```

| Expected | `0` |
|---|---|

**Checkpoint:** If the invalid HMAC returns 200 instead of 401, the `ShopifyHmacVerificationFilter` is not intercepting requests to `/webhooks/shopify/*`.

---

## Phase 1c: CJ Supplier Order Placement (FR-025)

This phase tests the CJ supplier order placement chain: when an order is confirmed, the system automatically places a supplier order with CJ Dropshipping. Tests cover the happy path, CJ rejection, missing variant mapping, idempotent retry, and NullNode guard on Shopify shipping address fields.

**Automated test coverage:** All scenarios below are covered by the fulfillment module test suite (`./gradlew :fulfillment:test`). No manual E2E steps are required — the scenarios are validated by unit and integration tests.

### 1c.1 Happy Path: Order Created -> Confirmed -> CJ Order Placed -> Supplier Order ID Stored

**Test:** `SupplierOrderPlacementIntegrationTest.full chain - order created, routed, supplier order placed successfully stores supplierOrderId`

| Step | Action | Assertion |
|---|---|---|
| 1 | Create order with shipping address and quantity=2 | Order in PENDING |
| 2 | Route to vendor | Order in CONFIRMED, `OrderConfirmed` event published |
| 3 | Call `placeSupplierOrder()` (simulates event listener) | Adapter called with correct request |
| 4 | Verify final state | `supplierOrderId = "cj-supplier-order-99"`, status = CONFIRMED |

### 1c.2 CJ Rejection: Order Confirmed -> CJ Rejects -> Order FAILED with Reason

**Test:** `SupplierOrderPlacementIntegrationTest.full chain - CJ rejects order, order marked FAILED with failureReason`

| Step | Action | Assertion |
|---|---|---|
| 1 | Create order and route to vendor | Order in CONFIRMED |
| 2 | Adapter returns `Failure("product out of stock")` | Order status = FAILED |
| 3 | Verify failure details | `failureReason = "product out of stock"`, `supplierOrderId` is null |

### 1c.3 Missing Variant Mapping: Order Confirmed -> No Mapping -> Order FAILED

**Test:** `SupplierOrderPlacementServiceTest.missing mapping - resolver returns null sets order to FAILED`

| Step | Action | Assertion |
|---|---|---|
| 1 | Confirmed order, resolver returns null | Order status = FAILED |
| 2 | Verify failure reason | `failureReason` contains "No supplier product mapping" |
| 3 | Verify adapter not called | `verify(adapter, never()).placeOrder(any())` |

### 1c.4 Idempotent Retry: Order Already Has Supplier Order ID -> Skip Placement

**Test:** `SupplierOrderPlacementServiceTest.idempotency - order already has supplierOrderId skips adapter call`

| Step | Action | Assertion |
|---|---|---|
| 1 | Order with `supplierOrderId = "existing-123"` | Adapter never called |
| 2 | Verify order unchanged | `supplierOrderId` still "existing-123", no save call |

### 1c.5 NullNode Guard: Shopify Webhook with JSON Null Shipping Fields -> Kotlin Null

**Test:** `ShopifyOrderAdapterTest.JSON null shipping address fields return Kotlin null NOT string null -- PR 39 bug regression`

| Step | Action | Assertion |
|---|---|---|
| 1 | Parse webhook with `"last_name": null, "address2": null, "province": null, "phone": null` | All four fields are Kotlin `null` (not the string `"null"`) |
| 2 | Assert with `isNull()` not `isNotEqualTo("null")` | Catches the `NullNode.asText()` trap per CLAUDE.md constraint 17 |

**Additional shipping address tests:**

| Test | Scenario | Assertion |
|---|---|---|
| `full shipping address extracted correctly with all 11 fields` | Complete shipping address | All 11 fields match expected values |
| `missing shipping_address node returns null shippingAddress` | No `shipping_address` key | `shippingAddress` is null |
| `shipping_address is JSON null returns null shippingAddress` | `"shipping_address": null` | `shippingAddress` is null |
| `mixed null and present shipping fields correctly mapped` | Some null, some present | Non-null fields correct, null fields are Kotlin null |

### 1c.6 CJ API Contract Tests (WireMock)

**Test class:** `CjSupplierOrderAdapterWireMockTest` (8 tests)

| Test | Scenario | Assertion |
|---|---|---|
| `successful order placement returns Success with supplierOrderId` | CJ returns code=200 with orderId | `Success("2011152148163605")` |
| `out of stock error returns Failure with reason` | CJ returns code=1600501 | `Failure` with "product out of stock" |
| `invalid address error returns Failure with reason` | CJ returns code=1600502 | `Failure` with "invalid shipping address" |
| `auth failure 401 propagates as HttpClientErrorException` | CJ returns HTTP 401 | Exception propagates (Resilience4j handles) |
| `null orderId in success response returns Failure via NullNode guard` | code=200 but `data.orderId` is null | `Failure` with "no orderId" |
| `null data node in response returns Failure` | code=200 but `data` is null | `Failure` |
| `credential guard - blank credentials return Failure with no HTTP call` | Empty baseUrl or accessToken | `Failure` with "credentials", no HTTP call |
| `request body verification - correct headers and body structure` | Valid request | CJ-Access-Token header, correct JSON body shape |

### 1c.7 Order FAILED State Machine Tests

**Test class:** `OrderFailedTransitionTest` (8 tests)

| Test | Assertion |
|---|---|
| `confirmed order can transition to FAILED` | Valid |
| `pending order can transition to FAILED` | Valid |
| `FAILED is terminal -- cannot transition to CONFIRMED` | `IllegalArgumentException` |
| `FAILED is terminal -- cannot transition to PENDING` | `IllegalArgumentException` |
| `FAILED is terminal -- cannot transition to SHIPPED` | `IllegalArgumentException` |
| `SHIPPED order cannot transition to FAILED` | `IllegalArgumentException` |
| `DELIVERED order cannot transition to FAILED` | `IllegalArgumentException` |
| `REFUNDED order cannot transition to FAILED` | `IllegalArgumentException` |

### 1c.8 Quantity and Shipping Address Flow

**Test class:** `LineItemOrderCreatorTest`

| Test | Assertion |
|---|---|
| `quantity flows from lineItem to CreateOrderCommand` | `command.quantity == lineItem.quantity` |
| `shippingAddress maps from ChannelShippingAddress to ShippingAddress` | All fields correctly mapped |
| `null shippingAddress passes through as null in CreateOrderCommand` | `command.shippingAddress` is null |

**Test class:** `OrderServiceTest`

| Test | Assertion |
|---|---|
| `routeToVendor publishes OrderConfirmed event` | Event contains correct orderId and skuId |

---

## Phase 1d: CJ Tracking Webhook + Shopify Fulfillment Sync (FR-026)

This phase tests the CJ Dropshipping tracking webhook flow: token verification, deduplication, order status transition to SHIPPED, and Shopify fulfillment sync. Requires a CONFIRMED order (created via Phases 1b + 1c, or via Phase 2 manual order creation).

**Automated test coverage:** Core scenarios are covered by the fulfillment module test suite (`./gradlew :fulfillment:test`). The manual E2E steps below validate the full HTTP chain including the `CjWebhookTokenVerificationFilter`, controller, dedup, `CjTrackingProcessingService` AFTER_COMMIT listener, and `ShopifyFulfillmentSyncListener`.

### Prerequisites

Set the CJ webhook secret in the environment before starting the application:

```bash
export CJ_WEBHOOK_SECRET="e2e-test-cj-secret"
```

Restart the application if it was already running:

```bash
pkill -f "bootRun"
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

You need a CONFIRMED order with a known UUID. **Important:** Confirming an order via `POST /api/orders/{id}/confirm` fires the `SupplierOrderPlacementListener` (FR-025), which may set the order to FAILED if no `supplier_product_mappings` row exists for the SKU. To avoid this race condition, insert a CONFIRMED order directly into the database:

```bash
# Insert a CONFIRMED order directly (bypasses SupplierOrderPlacementListener)
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  INSERT INTO orders (id, sku_id, vendor_id, customer_id, total_amount, total_currency, status, quantity, payment_intent_id, idempotency_key, created_at, updated_at, version)
  VALUES (
    'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    '$SKU_ID'::uuid,
    '$VENDOR_ID'::uuid,
    '$(python3 -c \"import uuid; print(uuid.uuid4())\")'::uuid,
    199.9900, 'USD', 'CONFIRMED', 1,
    'pi_test_cj_001', 'e2e-cj-order-001',
    NOW(), NOW(), 0
  );
"

CJ_ORDER_ID="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

# Verify the order exists in CONFIRMED state
curl -s "http://localhost:8080/api/orders/$CJ_ORDER_ID" | python3 -m json.tool
# Expected: status = "CONFIRMED"
```

### E2E-CJ-1: CJ Tracking Webhook -- Full Chain

**Action:**

```bash
curl -s -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer e2e-test-cj-secret" \
  -d "{
    \"messageId\": \"msg-e2e-tracking-001\",
    \"type\": \"LOGISTIC\",
    \"messageType\": \"UPDATE\",
    \"openId\": 1234567890,
    \"params\": {
      \"orderId\": \"$CJ_ORDER_ID\",
      \"logisticName\": \"UPS\",
      \"trackingNumber\": \"1Z999AA10123456784\",
      \"trackingStatus\": 1,
      \"logisticsTrackEvents\": \"[]\"
    }
  }" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `accepted` |
| HTTP status | `200` |

**Verification:** The `CjTrackingProcessingService` AFTER_COMMIT listener processes the event asynchronously. Wait 1-2 seconds, then verify:

```bash
# Order should now be SHIPPED with tracking number
curl -s "http://localhost:8080/api/orders/$CJ_ORDER_ID" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `SHIPPED` |
| `trackingNumber` | `1Z999AA10123456784` |
| `carrier` | `UPS` |

**Checkpoint:** If the order is still CONFIRMED, the `CjTrackingProcessingService` AFTER_COMMIT + REQUIRES_NEW pattern is broken (same class of bug as PM-001/PM-005).

```bash
# Verify webhook event was persisted for dedup
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT event_id, topic, channel FROM webhook_events WHERE event_id = 'cj:$CJ_ORDER_ID:1Z999AA10123456784';"
```

| Column | Expected |
|---|---|
| `event_id` | `cj:{CJ_ORDER_ID}:1Z999AA10123456784` |
| `topic` | `tracking/update` |
| `channel` | `cj` |

**Log verification:**
- `CJ tracking processed: order {CJ_ORDER_ID} marked SHIPPED with tracking 1Z999AA10123456784 via UPS`
- `Shopify fulfillment synced for order {CJ_ORDER_ID}` (stub adapter in local profile returns true)

### E2E-CJ-2: Duplicate CJ Webhook

**Prerequisite:** E2E-CJ-1 completed successfully.

**Action:** Re-send the exact same webhook payload:

```bash
curl -s -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer e2e-test-cj-secret" \
  -d "{
    \"messageId\": \"msg-e2e-tracking-001\",
    \"type\": \"LOGISTIC\",
    \"messageType\": \"UPDATE\",
    \"openId\": 1234567890,
    \"params\": {
      \"orderId\": \"$CJ_ORDER_ID\",
      \"logisticName\": \"UPS\",
      \"trackingNumber\": \"1Z999AA10123456784\",
      \"trackingStatus\": 1,
      \"logisticsTrackEvents\": \"[]\"
    }
  }" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `already_processed` |
| HTTP status | `200` |

**Verification:** Order status remains SHIPPED (not re-processed). No second Shopify fulfillment call in logs.

```bash
curl -s "http://localhost:8080/api/orders/$CJ_ORDER_ID" | python3 -m json.tool
# status should still be SHIPPED
```

**Checkpoint:** If status is `accepted` instead of `already_processed`, the dedup key (`cj:{orderId}:{trackingNumber}`) is not being checked correctly.

### E2E-CJ-3: CJ Webhook Without Auth Token

**Action:**

```bash
curl -s -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -d '{"messageId":"test","type":"LOGISTIC","messageType":"UPDATE","openId":123,"params":{}}' \
  -w "\nHTTP_CODE:%{http_code}\n"
```

| Expected HTTP status | `401` |
|---|---|
| Response body | `{"error": "Invalid webhook token"}` |

**Note:** In local dev with `CJ_WEBHOOK_SECRET` set to empty string (default), the filter rejects ALL requests with `{"error": "CJ webhook secret not configured"}`. When the secret is configured (as in the prerequisites above), requests without the `Authorization` header get `{"error": "Invalid webhook token"}`.

**Verification:** No webhook event was persisted:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT COUNT(*) FROM webhook_events WHERE channel = 'cj' AND event_id LIKE 'cj:%';"
```

The count should be exactly 1 (only the E2E-CJ-1 event).

**Checkpoint:** If the request returns 200, the `CjWebhookTokenVerificationFilter` is not intercepting requests to `/webhooks/cj/*`.

### E2E-CJ-4: CJ Webhook with Unknown Order UUID

**Action:** Send a CJ webhook with a random UUID that does not match any order:

```bash
UNKNOWN_UUID="$(python3 -c 'import uuid; print(uuid.uuid4())')"

curl -s -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer e2e-test-cj-secret" \
  -d "{
    \"messageId\": \"msg-e2e-tracking-unknown\",
    \"type\": \"LOGISTIC\",
    \"messageType\": \"UPDATE\",
    \"openId\": 1234567890,
    \"params\": {
      \"orderId\": \"$UNKNOWN_UUID\",
      \"logisticName\": \"FedEx\",
      \"trackingNumber\": \"794644790132\",
      \"trackingStatus\": 1,
      \"logisticsTrackEvents\": \"[]\"
    }
  }" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `accepted` |
| HTTP status | `200` |

The controller accepts the webhook (dedup + persist), but the `CjTrackingProcessingService` AFTER_COMMIT listener logs a warning and skips processing:

**Log verification:**
- `CJ tracking event cj:{UNKNOWN_UUID}:794644790132 references unknown order {UNKNOWN_UUID}, skipping`

**Verification:** No order status changed:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT COUNT(*) FROM orders WHERE id = '$UNKNOWN_UUID';"
```

| Expected | `0` |
|---|---|

### E2E-CJ-5: ShipmentTracker Picks Up SHIPPED Order

**Prerequisite:** E2E-CJ-1 completed — the order is now in SHIPPED status with tracking number `1Z999AA10123456784` and carrier `UPS`.

The `ShipmentTracker` polls every 30 minutes (`@Scheduled(fixedRate = 1_800_000)`). It queries all SHIPPED orders and checks carrier tracking APIs for delivery status.

**Verification (after next ShipmentTracker poll):**

```bash
# Check application logs for polling activity:
# "Polling tracking status for N shipped orders"
# If the stub carrier tracking provider returns delivered=true:
# "Order {CJ_ORDER_ID} delivered (tracking: 1Z999AA10123456784)"
```

If the carrier stub returns `delivered = true`, the order transitions to DELIVERED and `OrderFulfilled` event fires:

```bash
curl -s "http://localhost:8080/api/orders/$CJ_ORDER_ID" | python3 -m json.tool
```

| Field | Expected (if carrier stub returns delivered) |
|---|---|
| `status` | `DELIVERED` |

```bash
# Verify capital_order_records created via OrderFulfilled event
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT order_id, status FROM capital_order_records WHERE order_id = '$CJ_ORDER_ID';"
```

| Column | Expected |
|---|---|
| `status` | `DELIVERED` |

**Note:** The ShipmentTracker uses the carrier name to look up a `CarrierTrackingProvider`. In local profile, the stub provider may not return `delivered = true` on the first poll — this depends on stub implementation. The key validation is that the ShipmentTracker logs confirm the SHIPPED order is being polled. To force delivery for testing, use the REST endpoint:

```bash
curl -s -X POST "http://localhost:8080/api/orders/$CJ_ORDER_ID/deliver" | python3 -m json.tool
# Expected: status = "DELIVERED"
```

---

## Phase 2: Vendor and Order Setup

Orders require an active vendor. This phase creates a vendor, activates it, and creates an order.

### 2.1 Create and Activate Vendor

```bash
# Create vendor
curl -s -X POST "http://localhost:8080/api/vendors" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Vendor","contactEmail":"vendor@test.com","leadTimeDays":5}' | python3 -m json.tool
```

Save the vendor `id`:
```bash
VENDOR_ID="<id from response>"
```

```bash
# Complete checklist
curl -s -X PATCH "http://localhost:8080/api/vendors/$VENDOR_ID/checklist" \
  -H "Content-Type: application/json" \
  -d '{"slaConfirmed":true,"defectRateDocumented":true,"scalabilityConfirmed":true,"fulfillmentTimesConfirmed":true,"refundPolicyConfirmed":true}' | python3 -m json.tool

# Activate
curl -s -X POST "http://localhost:8080/api/vendors/$VENDOR_ID/activate" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `ACTIVE` |

### 2.2 Create Order

```bash
CUSTOMER_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"

curl -s -X POST "http://localhost:8080/api/orders" \
  -H "Content-Type: application/json" \
  -d "{
    \"skuId\": \"$SKU_ID\",
    \"vendorId\": \"$VENDOR_ID\",
    \"customerId\": \"$CUSTOMER_ID\",
    \"totalAmount\": \"199.99\",
    \"totalCurrency\": \"USD\",
    \"paymentIntentId\": \"pi_test_001\",
    \"idempotencyKey\": \"e2e-order-001\"
  }" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `PENDING` |
| HTTP status | `201` |

Save the order `id`:
```bash
ORDER_ID="<id from response>"
```

### 2.3 Advance Order to DELIVERED (via REST)

Use the order state transition REST endpoints to advance the order through its lifecycle. This exercises the full `OrderService` methods, including domain event publishing.

```bash
# Confirm order
curl -s -X POST "http://localhost:8080/api/orders/$ORDER_ID/confirm" | python3 -m json.tool
# Expected: status = "CONFIRMED"

# Ship order
curl -s -X POST "http://localhost:8080/api/orders/$ORDER_ID/ship" \
  -H "Content-Type: application/json" \
  -d '{"trackingNumber":"TRK123456","carrier":"UPS"}' | python3 -m json.tool
# Expected: status = "SHIPPED", trackingNumber = "TRK123456"

# Deliver order
curl -s -X POST "http://localhost:8080/api/orders/$ORDER_ID/deliver" | python3 -m json.tool
# Expected: status = "DELIVERED"
```

**Verification:** After delivery, the `OrderFulfilled` event fires via `AFTER_COMMIT`, and the `OrderEventListener` creates a `capital_order_records` entry and credits the reserve. Verify:

```bash
# Check that capital_order_records was created automatically
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT order_id, sku_id, total_amount, status, refunded FROM capital_order_records WHERE order_id = '$ORDER_ID';"
```

| Column | Expected |
|---|---|
| `order_id` | `$ORDER_ID` |
| `total_amount` | `199.9900` |
| `status` | `DELIVERED` |
| `refunded` | `false` |

**Checkpoint:** If no `capital_order_records` row exists, the `OrderEventListener` AFTER_COMMIT + REQUIRES_NEW pattern is broken.

---

## Phase 3: Capital Module — Reserve and P&L

### 3.1 Seed Capital Data

The `OrderEventListener` automatically creates the first `capital_order_records` entry and credits the reserve when the order is delivered via REST (Phase 2.3). To generate meaningful P&L data, add more orders:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  -- Add more orders for meaningful P&L data (9 additional orders over 9 days)
  INSERT INTO capital_order_records (id, order_id, sku_id, total_amount, currency, status, refunded, chargebacked, recorded_at)
  SELECT gen_random_uuid(), gen_random_uuid(), '$SKU_ID'::uuid, 199.9900, 'USD', 'DELIVERED', false, false, NOW() - (i || ' days')::interval
  FROM generate_series(1, 9) AS s(i);
"
```

If a reserve account does not yet exist (first run), create one:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  -- Create reserve account if none exists (10% of 10 orders x \$199.99 = \$199.99)
  INSERT INTO reserve_accounts (id, balance_amount, balance_currency, target_rate_min, target_rate_max, last_updated_at, version)
  SELECT gen_random_uuid(), 199.9900, 'USD', 10, 15, NOW(), 0
  WHERE NOT EXISTS (SELECT 1 FROM reserve_accounts);
"
```

### 3.2 Verify Reserve Balance

```bash
curl -s "http://localhost:8080/api/capital/reserve" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `balanceAmount` | `199.9900` |
| `balanceCurrency` | `USD` |
| `health` | `HEALTHY` |

### 3.3 Verify SKU P&L

```bash
FROM=$(date -v-30d +%Y-%m-%d)   # macOS; use date -d '-30 days' on Linux
TO=$(date +%Y-%m-%d)

curl -s "http://localhost:8080/api/capital/skus/$SKU_ID/pnl?from=$FROM&to=$TO" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `snapshotCount` | `0` (no margin sweep yet) |
| `totalRevenueAmount` | `0.0000` (P&L is snapshot-based) |

**Note:** P&L data comes from `margin_snapshots`, not from `capital_order_records` directly. Snapshots are created by the `MarginSweepJob` scheduled task.

---

## Phase 4: Shutdown Rules — Margin Breach Auto-Pause

This is the critical test for the AFTER_COMMIT + REQUIRES_NEW fix chain:

```
MarginSweepJob → MarginSweepSkuProcessor (@Transactional REQUIRES_NEW)
  → ShutdownRuleEngine.evaluate() (@Transactional, publishes ShutdownRuleTriggered)
    → [AFTER_COMMIT] ShutdownRuleListener (@TransactionalEventListener + @Transactional REQUIRES_NEW)
      → SkuService.transition() → SKU state changed to PAUSED
```

### 4.1 Insert Degraded Margin Snapshots

Insert 7 consecutive days with net margin below the 30% floor:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  INSERT INTO margin_snapshots (id, sku_id, snapshot_date, gross_margin, net_margin, revenue_amount, revenue_currency, total_cost_amount, total_cost_currency, refund_rate, chargeback_rate, cac_variance)
  SELECT gen_random_uuid(), '$SKU_ID'::uuid,
         (CURRENT_DATE - (i || ' days')::interval)::date,
         50.00, 25.00,
         1999.90, 'USD',
         1000.00, 'USD',
         0.01, 0.005, 0.05
  FROM generate_series(0, 6) AS s(i);
"
```

### 4.2 Trigger the Margin Sweep

The `MarginSweepJob` runs on `@Scheduled(fixedRate = 21_600_000)` (every 6 hours) and fires immediately on app startup. **Restart the application** to trigger it:

```bash
# Stop the app (Ctrl+C or pkill)
pkill -f "bootRun"

# Restart
./gradlew :app:bootRun --args='--spring.profiles.active=local'

# Wait for startup + sweep (~15s)
sleep 15
```

The sweep picks up the LISTED SKU, finds 7 snapshots with 25% net margin (below 30% floor for 7+ days), and fires `MARGIN_BREACH → PAUSE`.

### 4.3 Verify Auto-Pause

```bash
# SKU should now be PAUSED
curl -s "http://localhost:8080/api/skus/$SKU_ID" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `currentState` | `PAUSED` |

```bash
# Audit trail should show the rule that fired
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT rule, condition_value, action, fired_at FROM capital_rule_audit WHERE sku_id = '$SKU_ID';"
```

| Column | Expected |
|---|---|
| `rule` | `MARGIN_BREACH` |
| `condition_value` | `25.00%` |
| `action` | `PAUSE` |

### 4.3b Verify Platform Listing Paused (FR-020)

The `PlatformListingListener` also fires on `SkuStateChanged(toState=PAUSED)` and sets the listing status to `DRAFT`:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT status, updated_at FROM platform_listings WHERE sku_id = '$SKU_ID';"
```

| Column | Expected |
|---|---|
| `status` | `DRAFT` |
| `updated_at` | Updated since creation |

**Checkpoint:** If status is still `ACTIVE`, the `PlatformListingListener` is not reacting to PAUSED transitions.

### 4.4 Verify P&L Now Shows Data

```bash
FROM=$(date -v-30d +%Y-%m-%d)
TO=$(date +%Y-%m-%d)

curl -s "http://localhost:8080/api/capital/skus/$SKU_ID/pnl?from=$FROM&to=$TO" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `snapshotCount` | `7` (or 8 if sweep added today's) |
| `averageNetMarginPercent` | ~`25.00` |

---

## Phase 5: Refund / Chargeback Rate Breach (Alternate Shutdown Path)

To test the refund rate shutdown rule instead, skip Phase 4 and do:

```bash
# Insert 100 orders, 6 refunded (6% > 5% threshold)
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  INSERT INTO capital_order_records (id, order_id, sku_id, total_amount, currency, status, refunded, chargebacked, recorded_at)
  SELECT gen_random_uuid(), gen_random_uuid(), '$SKU_ID'::uuid, 100.0000, 'USD', 'DELIVERED',
         CASE WHEN i <= 6 THEN true ELSE false END,
         false, NOW()
  FROM generate_series(1, 100) AS s(i);
"
```

Then restart the app (to trigger sweep) and verify:

| Expected audit | `REFUND_RATE_BREACH` / `PAUSE` |
|---|---|
| Expected SKU state | `PAUSED` |

For chargeback breach, use `chargebacked = true` for 3 of 100 orders (3% > 2% threshold). Expected audit: `CHARGEBACK_RATE_BREACH` / `PAUSE_COMPLIANCE`.

---

## Phase 6: Portfolio Orchestration (FR-010)

This phase tests experiment tracking, portfolio KPIs, priority ranking, kill window monitoring, and refund pattern analysis.

### 6.1 Create an Experiment

```bash
curl -s -X POST "http://localhost:8080/api/portfolio/experiments" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bluetooth Speaker Hypothesis",
    "hypothesis": "Wireless speakers in the $150-200 range have high demand with 50%+ margins",
    "sourceSignal": "Google Trends",
    "validationWindowDays": 30
  }' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `ACTIVE` |
| `validationWindowDays` | `30` |
| HTTP status | `201` |

Save the experiment `id`:
```bash
EXPERIMENT_ID="<id from response>"
```

### 6.2 Validate Experiment (Link to SKU)

```bash
curl -s -X POST "http://localhost:8080/api/portfolio/experiments/$EXPERIMENT_ID/validate" \
  -H "Content-Type: application/json" \
  -d "{\"skuId\": \"$SKU_ID\"}" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `VALIDATED` |
| `launchedSkuId` | `$SKU_ID` |

### 6.3 Portfolio Summary

```bash
curl -s "http://localhost:8080/api/portfolio/summary" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `totalExperiments` | `≥ 1` |
| `activeExperiments` | `0` (the one we created was validated) |
| HTTP status | `200` |

### 6.4 Priority Ranking (Reallocation)

```bash
curl -s "http://localhost:8080/api/portfolio/reallocation" | python3 -m json.tool
```

Returns a list of SKUs ranked by risk-adjusted return. With margin snapshot data from Phase 4:

| Field | Expected |
|---|---|
| HTTP status | `200` |
| Response | Array of ranking entries with `skuId`, `avgNetMargin`, `riskAdjustedReturn` |

### 6.5 Kill Recommendations (Empty — No Qualifying SKUs Yet)

```bash
curl -s "http://localhost:8080/api/portfolio/kill-recommendations" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| HTTP status | `200` |
| Response | `[]` (empty — kill window monitor hasn't run yet, or no SKU has 30+ days negative) |

### 6.6 Refund Pattern Analysis

```bash
curl -s "http://localhost:8080/api/portfolio/refund-alerts" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| HTTP status | `200` |
| `elevatedSkuCount` | `0` (unless refund data from Phase 5 is present) |

### 6.7 Fail an Experiment

```bash
# Create another experiment to fail
curl -s -X POST "http://localhost:8080/api/portfolio/experiments" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Failed Hypothesis Test",
    "hypothesis": "Testing fail path",
    "sourceSignal": "Google Trends",
    "validationWindowDays": 14
  }' | python3 -m json.tool

FAIL_EXP_ID="<id from response>"

curl -s -X POST "http://localhost:8080/api/portfolio/experiments/$FAIL_EXP_ID/fail" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `status` | `FAILED` |

### 6.8 List All Experiments

```bash
curl -s "http://localhost:8080/api/portfolio/experiments" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| Response length | `≥ 2` |
| Statuses | Mix of `VALIDATED`, `FAILED` |

---

## Phase 7: Kill Window Monitor (Long-Term Termination)

This tests the kill window monitor — the complement to capital's short-term shutdown rules. Capital pauses SKUs after 7 days of bad margins; the kill window monitor terminates them after 30+ days.

### 7.1 Insert 31 Days of Negative Margin Snapshots

Use a separate SKU for this test (the Phase 4 SKU may already be PAUSED/TERMINATED):

```bash
# Create and fast-track a new SKU to LISTED via DB
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  INSERT INTO skus (id, name, category, current_state, version, created_at, updated_at)
  VALUES (gen_random_uuid(), 'Kill Window Test SKU', 'Gadgets', 'LISTED', 0, NOW(), NOW())
  RETURNING id;
"
KILL_SKU_ID="<id from response>"

# Insert 31 days of negative margin (net_margin < 0)
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  INSERT INTO margin_snapshots (id, sku_id, snapshot_date, gross_margin, net_margin, revenue_amount, revenue_currency, total_cost_amount, total_cost_currency, refund_rate, chargeback_rate, cac_variance)
  SELECT gen_random_uuid(), '$KILL_SKU_ID'::uuid,
         (CURRENT_DATE - (i || ' days')::interval)::date,
         20.00, -5.00,
         500.00, 'USD',
         525.00, 'USD',
         0.01, 0.005, 0.05
  FROM generate_series(0, 30) AS s(i);
"
```

### 7.2 Verify Kill Recommendation

The `KillWindowMonitor` runs daily at 1 AM. To test immediately, restart the app or wait for the next scheduled run. After it runs:

```bash
curl -s "http://localhost:8080/api/portfolio/kill-recommendations" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| Response | Array containing entry with `skuId: $KILL_SKU_ID` |
| `daysNegative` | `≥ 31` |

**Note:** With `portfolio.auto-terminate.enabled=false` (default), the monitor only writes a recommendation — it does NOT terminate the SKU. To test auto-termination, set `portfolio.auto-terminate.enabled=true` in `application.yml` and restart.

### 7.3 Manual Kill Confirmation (Flag OFF)

```bash
KILL_REC_ID="<id from kill-recommendations response>"

curl -s -X POST "http://localhost:8080/api/portfolio/kill-recommendations/$KILL_REC_ID/confirm" | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `confirmedAt` | Non-null timestamp |

**Note:** This endpoint confirms the recommendation record but does NOT currently trigger the SKU termination via catalog. To fully close the loop, the operator would call `POST /api/skus/$KILL_SKU_ID/state` with `{"state":"TERMINATED"}` separately.

---

## Phase 8: Demand Scan Job (FR-016)

This phase tests the autonomous demand signal pipeline: scan trigger, candidate scoring, experiment creation, deduplication, and idempotency.

### 8.1 Verify Empty State (Before Scan)

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/status | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `lastRunId` | `null` |
| `lastRunStatus` | `null` |
| `candidatesFound` | `0` |

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/candidates | python3 -m json.tool
# Expected: []

curl -s http://localhost:8080/api/portfolio/demand-scan/rejections | python3 -m json.tool
# Expected: []
```

### 8.2 Smoke Test Demand Signal Adapters (FR-017)

Before triggering the full scan, run the smoke test to verify all 4 demand signal adapters (CJ, Google Trends, YouTube, Reddit) can respond. This is a quick pre-flight check — rate limited to 1 request per 60 seconds.

```bash
curl -s -X POST http://localhost:8080/api/portfolio/demand-scan/smoke-test | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `overallHealthy` | `true` |
| `results` length | `4` (one per adapter) |
| Each result `healthy` | `true` (stubs always succeed in local profile) |
| Each result `source` | `CJ_DROPSHIPPING`, `GOOGLE_TRENDS`, `YOUTUBE_DATA`, `REDDIT` |

**Checkpoint:** If any adapter shows `healthy: false`, check the adapter's stub configuration. If `source` shows `UNKNOWN`, the smoke service has lost provider identity (see PM-014).

### 8.3 Trigger Demand Scan

```bash
curl -s -X POST http://localhost:8080/api/portfolio/demand-scan/trigger | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `message` | `"Demand scan triggered successfully"` |
| HTTP status | `200` |

### 8.4 Verify Scan Status

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/status | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `lastRunStatus` | `COMPLETED` |
| `sourcesQueried` | `4` (CJ, Google Trends, YouTube, Reddit — all stubs in local profile; Amazon deactivated) |
| `candidatesFound` | `≥ 5` (dedup may reduce from ~12 raw) |
| `experimentsCreated` | `≥ 1` (candidates above 0.6 threshold) |
| `rejections` | `≥ 1` (candidates below 0.6 threshold) |

### 8.5 Verify Scored Candidates

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/candidates | python3 -m json.tool
```

| Check | Expected |
|---|---|
| Response is non-empty array | Yes |
| Each entry has `demandScore`, `marginPotentialScore`, `competitionScore`, `compositeScore` | All present, 0-1 range |
| Entries with `passed: true` | Have `compositeScore >= 0.6` |
| Entries with `passed: false` | Have `compositeScore < 0.6` |
| `sourceType` values | Mix of `CJ_DROPSHIPPING`, `GOOGLE_TRENDS`, `YOUTUBE_DATA`, `REDDIT` |

### 8.6 Verify Rejections

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/rejections | python3 -m json.tool
```

| Check | Expected |
|---|---|
| Response is non-empty array | Yes |
| Each entry has `rejectionReason` | `"Below scoring threshold"` |
| Each entry has dimension scores | Present |

### 8.7 Verify Experiments Created

Passing candidates should have created `Experiment` records automatically:

```bash
curl -s http://localhost:8080/api/portfolio/experiments | python3 -m json.tool
```

| Check | Expected |
|---|---|
| Experiments with `sourceSignal` starting with `GOOGLE_TRENDS:`, `YOUTUBE_DATA:`, or `REDDIT:` | Present |
| `status` | `ACTIVE` |
| `validationWindowDays` | `30` |
| `hypothesis` | Contains `"Demand signal detected via"` |

### 8.8 Verify Cooldown / Idempotency

Trigger the scan again immediately — it should be silently skipped (within 20h cooldown):

```bash
curl -s -X POST http://localhost:8080/api/portfolio/demand-scan/trigger | python3 -m json.tool
# Expected: {"message": "Demand scan triggered successfully"} (returns OK but no new run)
```

```bash
# Verify only 1 scan run exists
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c \
  "SELECT COUNT(*) FROM demand_scan_runs;"
```

| Expected | `1` (cooldown prevented second run) |
|---|---|

### 8.9 Verify Blacklist Filtering (Optional)

Add a blacklist entry, clear scan data, and re-trigger to verify filtering:

```bash
# Add blacklist entry
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  INSERT INTO discovery_blacklist (id, keyword, reason, added_at)
  VALUES (gen_random_uuid(), 'electronics', 'High refund rate in category', NOW());
"

# Clear previous scan data so cooldown doesn't block
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  TRUNCATE demand_candidates, candidate_rejections, demand_scan_runs CASCADE;
"

# Trigger scan
curl -s -X POST http://localhost:8080/api/portfolio/demand-scan/trigger | python3 -m json.tool

# Verify no Electronics candidates passed
curl -s http://localhost:8080/api/portfolio/demand-scan/candidates | python3 -c "
import sys, json
candidates = json.load(sys.stdin)
electronics = [c for c in candidates if 'electronics' in c['category'].lower()]
print(f'Electronics candidates: {len(electronics)} (expected: 0)')
"
```

### 8.10 Verify pg_trgm Dedup (Optional)

Check that trigram similarity dedup is working at the database level:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  SELECT product_name, similarity(product_name, 'Silicone Collapsible Water Bottle')
  FROM demand_candidates
  WHERE similarity(product_name, 'Silicone Collapsible Water Bottle') > 0.3
  ORDER BY similarity DESC;
"
```

Expected: Similar product names from different sources (e.g., CJ and YouTube/Reddit) should appear with similarity > 0.3, validating the trigram index.

---

## Phase 9: Shopify Dev Store + Stripe Test Mode Validation (FR-030 / RAT-53)

Gate-zero, one-shot operator run validating the full listing → webhook → CJ chain against a real Shopify dev store in Stripe test mode. Each scenario is atomic: **preconditions → action → observable outcomes → abort criteria**. Scenarios execute in order.

Binding source: `feature-requests/FR-030-shopify-dev-store-stripe-test-mode/test-spec.md` §4. See also `docs/live-e2e-runbook.md` Section 12 for step-by-step ops commands. The automated unit/integration subset (T-01 through T-68) is covered by `./gradlew test`; this phase covers only the assertions that require a live dev store and real Shopify/Stripe traffic.

SC-RAT53-01 through SC-RAT53-05 are **operator-manual** (Shopify Partners dashboard, Stripe dashboard, browser checkout). SC-RAT53-06 through SC-RAT53-14 are **operator-executed observations** once the app is running under the dev-store config.

### 9.1 SC-RAT53-01: Dev store provisioning (ops-only)

- **Preconditions:** Operator has a Shopify Partners account; no dev store yet exists for this test.
- **Action:** Create development store via Partners dashboard; note the `*.myshopify.com` subdomain; install Custom App with scopes `write_products, write_orders, read_orders, write_fulfillments, read_fulfillments`; capture `shpat_…` Admin API access token and `whsec_…` webhook signing secret into local `.env` (never committed).
- **Observable outcomes:**
  - `curl -s https://<subdomain>.myshopify.com/` returns HTTP 200 with storefront HTML (spec §7.1 criterion 1).
  - `.env` file exists and is listed in `.gitignore`.
- **Abort criteria:** Shopify Partners approval delayed > 24h → file RAT-53 blocker, switch to alternate dev account, do not proceed to SC-02.

### 9.2 SC-RAT53-02: Stripe test mode activation (ops-only)

- **Preconditions:** SC-RAT53-01 complete.
- **Action:** In Stripe dashboard, switch to test mode; obtain `sk_test_…` and `pk_test_…`; configure as payment provider in Shopify dev-store admin → Settings → Payments.
- **Fallback action:** If Stripe partner approval delays config, enable Shopify "Bogus Gateway" as documented fallback (spec §2.1 BR-3). Record fallback usage in the run log with rationale.
- **Observable outcomes:**
  - Dev-store checkout page shows Stripe (test mode) or Bogus Gateway as the active payment option.
  - `.env` contains `STRIPE_SECRET_KEY=sk_test_…` (NOT `sk_live_…`).
- **Abort criteria:** Neither Stripe test mode nor Bogus Gateway is available → abort; real money is the only remaining path and that violates NFR-3.

### 9.3 SC-RAT53-03: Pre-flight key audit + clean app boot under `production` profile

- **Preconditions:** `.env` populated from SC-01 and SC-02; V23 migration merged; new admin + archival properties present (but not yet enabled).
- **Action:**
  ```bash
  ./gradlew devStoreAuditKeys
  ./gradlew flywayMigrate
  SPRING_PROFILES_ACTIVE=production ./gradlew bootRun
  ```
- **Observable outcomes:**

  | Check | Expected |
  |---|---|
  | `devStoreAuditKeys` exit code | 0, output prints key last-4 only |
  | `GET /actuator/health` | `{"status":"UP"}` within 30s |
  | Startup logs | No `IllegalStateException` from `@Value` bean wiring (CLAUDE.md #13) |
  | `\d platform_listings` (psql) | Column `shopify_inventory_item_id` present |

- **Abort criteria:** `devStoreAuditKeys` fails → do NOT proceed; fix `.env`; rerun. App fails to start under `production` profile → open Phase 6 bug, do not proceed.

### 9.4 SC-RAT53-04: Automated listing via DevAdminController

- **Preconditions:** SC-03 complete; operator has set `autoshipper.admin.dev-listing-enabled=true` and `autoshipper.admin.dev-token=<rotating-secret>` for this session. A SKU exists in `STRESS_TESTED` state.
- **Action:**
  ```bash
  curl -u "admin:$DEV_ADMIN_TOKEN" -X POST \
    http://localhost:8080/admin/dev/sku/$SKU_ID/list
  ```
- **Observable outcomes:**

  | Check | Expected |
  |---|---|
  | HTTP status | 202 |
  | App log | `Creating Shopify listing for SKU {}` |
  | `SELECT * FROM platform_listings WHERE sku_id = $SKU_ID` | One row, `status='ACTIVE'`, `external_listing_id` non-null, **`shopify_inventory_item_id` non-null** (AC 2 of spec §7.2) |
  | Storefront `GET /products/<handle>.json` | Returns product JSON (spec §7.1 criterion 4) |

- **Abort criteria:** Listing row present but `shopify_inventory_item_id IS NULL` → AD-1 implementation is broken; do not proceed to purchase. Investigate `ShopifyListingAdapter` response parsing.

### 9.5 SC-RAT53-05: Dummy-card purchase

- **Preconditions:** SC-04 complete; product visible on storefront; `autoshipper.webhook-archival.enabled=true` set in `.env` and app restarted (archival filter is eager-registered — requires restart).
- **Action:** Human operator navigates browser to storefront → adds product to cart → checks out with test card `4242 4242 4242 4242` (expiry any future date, CVC any 3 digits, ZIP any 5 digits).
- **Observable outcomes:**
  - Shopify order confirmation page reached, no error (spec §7.1 criterion 5).
  - Stripe test dashboard shows the test charge.
  - Stripe **live** dashboard shows zero activity for the window (spec §7.1 criterion 11).
- **Abort criteria:** Card rejected → check Stripe test mode is actually active; if Bogus Gateway is in use, substitute `Bogus Gateway: 1` per Shopify docs.

### 9.6 SC-RAT53-06: Webhook receipt + archival

- **Preconditions:** SC-05 complete; ngrok tunneled and registered in Shopify webhook admin.
- **Action:** Inspect ngrok log and database.
- **Observable outcomes:**

  | Check | Expected |
  |---|---|
  | ngrok inspector | `POST /webhooks/shopify/orders` returned 200 |
  | Archival file path | `docs/fixtures/shopify-dev-store/YYYY-MM-DD/orders-<ts>.json` with exact ngrok bytes (BR-9) |
  | `SELECT * FROM webhook_events WHERE channel='shopify'` | Row present for this delivery |
  | `SELECT * FROM orders WHERE channel_order_id = '<shopify_order_id>'` | `status='CONFIRMED'` (spec §7.1 criterion 6) |

- **Abort criteria:** No archival file — confirm `autoshipper.webhook-archival.enabled=true` and app was restarted after flipping. No `orders` row → FR-023 webhook pipeline is broken; triage before proceeding.

### 9.7 SC-RAT53-07: NFR-1 response-time verification

- **Action:** Read ngrok inspector timing for the `orders/create` POST observed in SC-06.
- **Observable outcomes:** Total response duration < 5000 ms. Record the exact value in the run log. Confirms the PM-015 `@Async + AFTER_COMMIT + REQUIRES_NEW` stack on `ShopifyOrderProcessingService` is working against real traffic.
- **Abort criteria:** Duration ≥ 5000 ms → Shopify will eventually disable the endpoint; file immediate PM-015-redux postmortem, pause the test.

### 9.8 SC-RAT53-08: CJ order-placement attempt (log-only assertion)

- **Preconditions:** SC-06 complete. `.env` configured per one of the two safe paths (BR-3a sandbox OR BR-6b dry-run).
- **Action:** Watch application logs for `SupplierOrderPlacementService` / `CjSupplierOrderAdapter` activity.
- **Observable outcomes (ANY of these three passes — operator records WHICH in run log):**
  - **(a) Sandbox path:** `CJ order placed successfully` log line with CJ sandbox order id. Verify via CJ sandbox dashboard that the order landed AND that it is marked as a sandbox order (no real supplier dispatch).
  - **(b) Dry-run path:** log line contains `[DEV-STORE DRY RUN] would have placed CJ order: skuCode=… qty=… orderNumber=…` AND stubbed supplier order id has prefix `dry-run-`. Verify via ngrok / WireMock / network tap that **zero outbound HTTP calls were made to `developers.cjdropshipping.cn`** during the window.
  - **(c) Error path (sandbox only):** `CJ order placement failed: <reason>` log line with specific failure detail — AND `orders.failure_reason` column populated for this order.
- **Abort criteria:** None of the three log lines appears within 2 minutes → the `OrderConfirmed` listener chain is broken, file bug, do not proceed. Spec §7.1 criterion 7 explicitly permits CJ **failure**, but does NOT permit **silence** (NFR-6).
- **Safety assertion:** If dry-run path (b) was chosen, MUST also verify CJ dashboard (both sandbox and production) shows NO new order created in the test window. If sandbox path (a) was chosen, MUST verify the production CJ dashboard shows NO new order.

### 9.9 SC-RAT53-09: Fulfillment sync (best-effort, optional)

- **Preconditions:** SC-08 placed a real CJ order (success case).
- **Action:** Wait for CJ tracking webhook OR manually trigger a simulated tracking webhook via `curl` to `/webhooks/cj/tracking` (HMAC-signed with `.env` secret).
- **Observable outcomes (EITHER passes):**
  - `OrderShipped` event published, `ShopifyFulfillmentSyncListener` logs a successful Shopify fulfillment creation; `orders.status='SHIPPED'` with `tracking_number` populated.
  - OR: Operator records explicit reason in run log why fulfillment was not exercised (e.g. CJ order rejected at SC-08, preventing tracking).
- **Abort criteria:** Fulfillment sync attempted but Shopify rejects → RAT-43 (refund) follow-up; does not block FR-030 completion if SC-08 passed.
- **Note:** `DELIVERED` transition remains out of scope (documented known gap).

### 9.10 SC-RAT53-10: Post-test cleanup + archival commit + key rotation

- **Action:**
  - Copy committed archival files from `docs/fixtures/shopify-dev-store/{date}/` into the feature branch; redact any PII (buyer email, shipping address) per BR-9 policy in the directory README.
  - If CJ sandbox path (SC-08a) was used, verify the sandbox order via CJ dashboard and confirm no production-account order was dispatched (sandbox orders do not need cancellation). If dry-run path (SC-08b) was used, no CJ cleanup needed.
  - Rotate `DEV_ADMIN_TOKEN` and Shopify webhook secret per operator-security hygiene.
  - Delete the test dev store in Shopify Partners if it is single-use.
- **Observable outcomes:**
  - `git diff docs/fixtures/shopify-dev-store/` shows 1+ committed JSON files.
  - CJ dashboard reflects the chosen path — sandbox order present (path a), or no CJ activity at all (path b).
  - Audit log shows the dev-listing-enabled property returned to `false` and `DEV_ADMIN_TOKEN` cleared from `.env`.
- **Abort criteria:** Archival files contain PII that cannot be redacted safely → do NOT commit; instead note in the run log that raw payloads are retained locally only.

### 9.11 SC-RAT53-11: Dedup-then-crash (orchestrator-augmented)

Stress test the archival filter's failure isolation: simulate a `WebhookArchivalFilter` I/O failure after the filter has already consumed the body (e.g. disk full, permissions changed mid-flight).

- **Preconditions:** Archival enabled and app restarted per SC-05. Pick a strategy to induce write failure:
  - `chmod a-w docs/fixtures/shopify-dev-store/` (revoke write permission), OR
  - Point `autoshipper.webhook-archival.output-dir` at a tmpfs / path that becomes unwritable, OR
  - On a test VM, fill the target disk to 100% before firing the webhook.
- **Action:** Trigger another `orders/create` delivery (place a second test purchase OR replay a captured payload through the Shopify webhook admin "Send test notification").
- **Observable outcomes:**

  | Check | Expected |
  |---|---|
  | Archival file | Absent (expected — write failed) |
  | App log | ERROR line from `WebhookArchivalFilter` referencing the I/O failure |
  | HMAC filter | Still executes (log shows HMAC accept/reject decision) |
  | `ShopifyWebhookController` | Still returns 200 within 5s SLO |
  | `orders` row | Created and transitions to `CONFIRMED` (processing chain unaffected) |

- **Abort criteria:** Any downstream consumer (HMAC filter, controller, processing listener) fails or the 5s SLO is breached → archival is incorrectly coupled to the critical path. File a P1 bug.

### 9.12 SC-RAT53-12: Response-timing SLO — PM-015 regression guard

Per-request response time assertion for every `orders/create` webhook observed during SC-RAT53-06. The `@Async + AFTER_COMMIT + REQUIRES_NEW` stack on `ShopifyOrderProcessingService` is first-exercised under real traffic here.

- **Action:**
  - Open ngrok inspector at `http://127.0.0.1:4040/inspect/http`.
  - For each `POST /webhooks/shopify/orders` delivery captured during the SC-06 run, record the response duration.
  - Export via `curl -s http://127.0.0.1:4040/api/requests/http | jq '.requests[] | select(.request.uri | contains("/webhooks/shopify")) | {uri: .request.uri, duration_ms: .duration}'` for a scripted capture.
- **Observable outcomes:**

  | Metric | Threshold |
  |---|---|
  | `max(duration_ms)` across all webhooks | < 5000 ms |
  | p50 (sanity) | < 500 ms (if higher, flag for follow-up profiling even if SLO passes) |

- **Abort criteria:** Any single delivery ≥ 5000 ms → PM-015 regression. The `@Async` annotation is not doing its job (check thread-pool config, `AFTER_COMMIT` ordering, synchronous blocking calls in the processing path). Escalate to PM-015-redux postmortem.

### 9.13 SC-RAT53-13: Contract verification — archived payload vs WireMock fixture

PM-013 red-flag check. After SC-06 captures a live `orders/create` body, diff its top-level keys against the committed WireMock fixture.

- **Preconditions:** SC-06 produced at least one `docs/fixtures/shopify-dev-store/YYYY-MM-DD/orders-<ts>.json` file.
- **Action:**
  ```bash
  LIVE=$(ls -t docs/fixtures/shopify-dev-store/*/orders-*.json | head -1)
  FIXTURE=modules/fulfillment/src/test/resources/wiremock/shopify/orders-create.json
  # If the fixture path above does not exist, substitute the closest committed equivalent.

  diff \
    <(jq -r 'keys[]' "$LIVE" | sort) \
    <(jq -r 'keys[]' "$FIXTURE" | sort)
  ```
- **Observable outcomes:**
  - Empty diff → fixture faithfully mirrors live Shopify wire shape. Proceed.
  - Non-empty diff → file a follow-up ticket to update the fixture. Capture the diff in the run log. Include both missing keys (live has, fixture doesn't) and extra keys (fixture has, live doesn't).
- **Abort criteria:** None (this is an advisory gate). Any drift is a PM-013 red-flag but does not block FR-030 completion — it opens follow-up work.

### 9.14 SC-RAT53-14: Async thread-boundary verification

After SC-06 returns 200 from the webhook, async processing continues on a separate thread. Verify the async path actually completes rather than silently dying after the 200 response.

- **Preconditions:** SC-06 complete and a `shopify_order_id` captured.
- **Action:** Within 30 seconds of the webhook response, poll:
  ```bash
  SHOPIFY_ORDER_ID="<id from ngrok inspector>"
  for i in {1..15}; do
    STATUS=$(PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -tAc \
      "SELECT status FROM orders WHERE channel_order_id = '$SHOPIFY_ORDER_ID';")
    echo "poll $i: status=$STATUS"
    [[ "$STATUS" == "CONFIRMED" ]] && break
    sleep 2
  done

  # Check the CJ log line
  grep -E "\[DEV-STORE DRY RUN\]|CJ order placed successfully|CJ order placement failed" \
    app.log | tail -5
  ```
- **Observable outcomes (BOTH must hold within 30s):**
  - (a) `orders` row for the Shopify order id has `status='CONFIRMED'`.
  - (b) Application log contains ONE of: `[DEV-STORE DRY RUN]` marker (dry-run path), `CJ order placed successfully` (sandbox success), or `CJ order placement failed:` (sandbox error).
- **Abort criteria:** Either (a) or (b) does not hold after 30s → the async path is broken. The webhook returned 200 but downstream processing never executed. This is the exact failure mode `@Async + AFTER_COMMIT + REQUIRES_NEW` is meant to prevent. Treat as P1 and tie to PM-015.

---

## Quick Reference: All Endpoints Used

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/actuator/health` | App health check |
| `POST` | `/api/skus` | Create SKU |
| `GET` | `/api/skus` | List all SKUs |
| `GET` | `/api/skus/{id}` | Get SKU state |
| `GET` | `/api/skus/{id}/state-history` | SKU state transition history |
| `POST` | `/api/skus/{id}/state` | Transition SKU (`{"state":"..."}`) |
| `POST` | `/api/skus/{id}/verify-costs` | Cost gate verification |
| `POST` | `/api/skus/{id}/stress-test` | Stress test (`{"estimatedPriceAmount":..., "currency":"USD"}`) |
| `GET` | `/api/skus/{id}/pricing` | Pricing data |
| `POST` | `/api/compliance/skus/{id}/check` | Trigger compliance check (FR-011) |
| `GET` | `/api/compliance/skus/{id}` | Compliance status + audit history |
| `POST` | `/api/vendors` | Create vendor |
| `PATCH` | `/api/vendors/{id}/checklist` | Update onboarding checklist |
| `POST` | `/api/vendors/{id}/activate` | Activate vendor |
| `POST` | `/api/vendors/{id}/score` | Trigger vendor reliability scoring |
| `POST` | `/api/orders` | Create order |
| `POST` | `/api/orders/{id}/confirm` | Confirm order (PENDING -> CONFIRMED) |
| `POST` | `/api/orders/{id}/ship` | Ship order with tracking (CONFIRMED -> SHIPPED) |
| `POST` | `/api/orders/{id}/deliver` | Deliver order (SHIPPED -> DELIVERED) |
| `GET` | `/api/capital/reserve` | Reserve balance + health |
| `GET` | `/api/capital/skus/{id}/pnl?from=&to=` | SKU P&L report |
| `GET` | `/api/capital/skus/{id}/margin-history` | SKU margin snapshot history |
| `GET` | `/api/portfolio/summary` | Portfolio KPIs (FR-010) |
| `GET` | `/api/portfolio/experiments` | List experiments |
| `POST` | `/api/portfolio/experiments` | Create experiment |
| `POST` | `/api/portfolio/experiments/{id}/validate` | Validate experiment, link to SKU |
| `POST` | `/api/portfolio/experiments/{id}/fail` | Mark experiment as failed |
| `GET` | `/api/portfolio/reallocation` | Priority ranking by risk-adjusted return |
| `GET` | `/api/portfolio/kill-recommendations` | Pending kill recommendations |
| `POST` | `/api/portfolio/kill-recommendations/{id}/confirm` | Confirm kill recommendation |
| `GET` | `/api/portfolio/refund-alerts` | Portfolio-wide refund pattern alerts |
| `GET` | `/api/portfolio/demand-scan/status` | Last scan run summary (FR-016) |
| `GET` | `/api/portfolio/demand-scan/candidates` | Scored candidates from latest run (FR-016) |
| `GET` | `/api/portfolio/demand-scan/rejections` | Rejections from latest run (FR-016) |
| `POST` | `/api/portfolio/demand-scan/trigger` | Manually trigger demand scan (FR-016) |
| `POST` | `/api/portfolio/demand-scan/smoke-test` | Smoke test all demand signal adapters (FR-017) |
| `POST` | `/webhooks/shopify/orders` | Receive Shopify orders/create webhook (FR-023) |
| `POST` | `/webhooks/cj/tracking` | Receive CJ tracking webhook (FR-026) |

---

## Known Gaps

| Gap | Impact | Workaround |
|---|---|---|
| No REST endpoint to trigger `MarginSweepJob` on demand | Must restart app to trigger sweep | Restart the application; sweep fires immediately on startup |
| `MarginSweepSkuProcessor` estimates cost as `revenue * 0.50` | P&L cost figures are approximations, not from cost envelope | Acceptable for Phase 1; will use real cost envelope data later |
| No REST endpoint to trigger `KillWindowMonitor` on demand | Must restart app or wait for 1 AM cron | Restart the application; or insert qualifying data and check after next scheduled run |
| Kill recommendation confirm does not auto-terminate SKU | Manual step needed after confirming recommendation | Call `POST /api/skus/{id}/state` with `TERMINATED` after confirming |
| Compliance `SourcingCheckService` needs vendor data not in Sku entity | `vendorId` must be passed manually in the request body | Provide `vendorId` in `ManualCheckRequest` or use auto-check via `SkuReadyForComplianceCheck` event |
| CJ webhook secret defaults to empty in local dev | `CjWebhookTokenVerificationFilter` rejects ALL CJ webhooks when `CJ_WEBHOOK_SECRET` is blank | Set `CJ_WEBHOOK_SECRET=e2e-test-cj-secret` env var before starting app for E2E testing |
| ShipmentTracker poll interval is 30 minutes | Cannot trigger on-demand via REST; must wait for next poll cycle | Restart app or use `POST /api/orders/{id}/deliver` to manually advance order |

---

## Transaction Safety Invariants (What This Playbook Validates)

These are the cross-module event listener patterns that must hold for the system to work correctly. All were validated by PM-001 and PM-005 postmortems.

| Listener | Event | Pattern | If broken |
|---|---|---|---|
| `PricingInitializer` | `SkuStateChanged` | `AFTER_COMMIT` + `REQUIRES_NEW` | Pricing never persisted (PM-001) |
| `ShutdownRuleListener` | `ShutdownRuleTriggered` | `AFTER_COMMIT` + `REQUIRES_NEW` | SKU never auto-paused (PM-005) |
| `VendorBreachListener` | `VendorSlaBreached` | `AFTER_COMMIT` + `REQUIRES_NEW` | SKU never paused on SLA breach (PM-005) |
| `OrderEventListener` | `OrderFulfilled` | `AFTER_COMMIT` + `REQUIRES_NEW` | Reserve never credited; fulfillment tx at risk |
| `PricingDecisionListener` | `PricingDecision` | `AFTER_COMMIT` + `REQUIRES_NEW` | Price sync / state transition lost |
| `CatalogComplianceListener` | `ComplianceCleared` | `AFTER_COMMIT` + `REQUIRES_NEW` | SKU stays in IDEATION after compliance passes (FR-011) |
| `CatalogComplianceListener` | `ComplianceFailed` | `AFTER_COMMIT` + `REQUIRES_NEW` | SKU not terminated on compliance failure (FR-011) |
| `CatalogKillWindowListener` | `KillWindowBreached` | `AFTER_COMMIT` + `REQUIRES_NEW` | SKU not auto-terminated after kill window breach (FR-010) |
| `ComplianceOrchestrator` | `SkuReadyForComplianceCheck` | `AFTER_COMMIT` + `REQUIRES_NEW` | Compliance check never triggered on SKU creation (FR-011) |
| `PlatformListingListener` | `SkuStateChanged` | `AFTER_COMMIT` + `REQUIRES_NEW` | Platform listing never created/paused/archived on SKU transition (FR-020) |
| `ShopifyOrderProcessingService` | `ShopifyOrderReceivedEvent` | `AFTER_COMMIT` + `REQUIRES_NEW` | Shopify webhook accepted but orders never created (FR-023) |
| `SupplierOrderPlacementListener` | `OrderConfirmed` | `AFTER_COMMIT` + `REQUIRES_NEW` | Confirmed orders never forwarded to CJ for supplier placement (FR-025) |
| `CjTrackingProcessingService` | `CjTrackingReceivedEvent` | `AFTER_COMMIT` + `REQUIRES_NEW` | CJ tracking webhook accepted but orders never transition to SHIPPED (FR-026) |
| `ShopifyFulfillmentSyncListener` | `OrderShipped` | `AFTER_COMMIT` + `REQUIRES_NEW` | Order shipped but Shopify fulfillment never synced (FR-026) |

**Rule:** Any `@TransactionalEventListener(AFTER_COMMIT)` handler that writes to the database **must** use `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Without it, JPA operations silently succeed but are never flushed.
