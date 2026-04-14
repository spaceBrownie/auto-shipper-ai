# Live E2E Test Runbook

## 1. Overview

### What This Tests

This runbook walks through the **complete order pipeline** end-to-end against real external APIs:

1. Shopify `orders/create` webhook received, HMAC-verified, and deduplicated
2. Webhook payload parsed into an internal Order (PENDING -> CONFIRMED)
3. CJ Dropshipping supplier order placed via `createOrderV2` API
4. CJ tracking webhook received, Bearer-token-verified, and deduplicated
5. Order marked SHIPPED with tracking number and carrier
6. Shopify fulfillment synced via GraphQL `fulfillmentCreateV2` mutation
7. *(Gap)* DELIVERED transition and capital recording

### What This Does NOT Test

- SKU lifecycle (Ideation through Listed) -- assumes a SKU already exists in `Listed` state
- Demand signal scoring, stress testing, or pricing engine
- Carrier tracking providers (UPS/FedEx/USPS) -- no real implementations wired to the DELIVERED transition
- Stripe payment processing -- orders arrive via Shopify (payment already captured)
- Refund/return flows

### Why This Matters

PM-020 proved that adapter fixtures can be 100% wrong when not validated against real APIs. Subagents invented CJ response structures that passed unit tests but failed against live endpoints. This runbook exists to verify that **real API request/response contracts flow correctly through the pipeline** -- not just "does it 200", but that actual data shapes, field mappings, and error responses match what our adapters expect.

---

## 2. Prerequisites

### API Accounts

| Service | What You Need | Where to Get It |
|---------|--------------|-----------------|
| **Shopify** | Development store with API access | [partners.shopify.com](https://partners.shopify.com) -- create a dev store, then create a custom app with `write_orders`, `write_fulfillments`, `read_fulfillments` scopes |
| **CJ Dropshipping** | API access token | [developers.cjdropshipping.com](https://developers.cjdropshipping.com) -- register, get an access token from the dashboard |
| **PostgreSQL** | Local instance or Docker | Provided via `docker-compose.yml` |

### Tools

- **Docker** and **Docker Compose** -- for PostgreSQL
- **ngrok** -- for exposing localhost to receive webhooks ([ngrok.com](https://ngrok.com))
- **curl** -- for simulating webhooks and verifying endpoints
- **psql** -- for running verification queries (or any PostgreSQL client)
- **JDK 21+** -- to build and run the application

### Important: No `local` Profile

The application uses `@Profile("!local")` to gate real adapters. For live E2E testing, you must run **without** the `local` profile. When the `local` profile is active, stub implementations replace all external adapters:

- `StubSupplierOrderConfiguration` returns fake supplier order IDs
- `StubShopifyFulfillmentAdapter` logs but does not call Shopify
- `StubInventoryConfiguration` always returns `isAvailable = true`

---

## 3. Environment Setup

### 3.1 Start PostgreSQL

```bash
docker compose up -d
```

Verify it is running:

```bash
docker compose ps
# Should show autoshipper-postgres as "running"

psql -h localhost -U autoshipper -d autoshipper -c "SELECT 1;"
# Should return 1
```

### 3.2 Configure Environment Variables

Create a `.env` file from the example:

```bash
cp .env.example .env
```

Edit `.env` with your real credentials. The **minimum required** variables for the order pipeline:

```bash
# Database
DB_URL=jdbc:postgresql://localhost:5432/autoshipper
DB_USERNAME=autoshipper
DB_PASSWORD=autoshipper

# Shopify (required for webhook verification, order parsing, fulfillment sync)
SHOPIFY_API_BASE_URL=https://<your-store>.myshopify.com
SHOPIFY_ACCESS_TOKEN=<your-shopify-access-token>
SHOPIFY_WEBHOOK_SECRETS=<your-shopify-webhook-signing-secret>

# CJ Dropshipping (required for supplier order placement)
CJ_API_BASE_URL=https://developers.cjdropshipping.com/api2.0/v1
CJ_ACCESS_TOKEN=<your-cj-access-token>
CJ_DEFAULT_LOGISTIC_NAME=<preferred-logistic-name>
CJ_WEBHOOK_SECRET=<a-secret-you-choose-for-cj-webhooks>
```

**CJ_DEFAULT_LOGISTIC_NAME**: This is the shipping method name passed to CJ's `createOrderV2`. Examples: `"USPS"`, `"UPS"`, `"4PX"`. If blank, the adapter omits `logisticName` from the request body and CJ selects a default. Check CJ's logistics list for your warehouse country.

**CJ_WEBHOOK_SECRET**: CJ's webhook authentication mechanism is **unverified** (see Known Gaps). This value is a Bearer token you configure on both sides. You choose the value -- it just needs to match between your app config and whatever you configure in CJ's developer dashboard (or use in manual curl commands).

**SHOPIFY_WEBHOOK_SECRETS**: Comma-separated list of signing secrets. Supports multiple for key rotation. Get the signing secret from Shopify admin: Settings > Notifications > Webhooks (at the bottom of the page). The filter uses HMAC-SHA256 with constant-time comparison.

### 3.3 Run Flyway Migrations

```bash
source .env
./gradlew flywayMigrate
```

Verify migrations:

```bash
./gradlew flywayInfo
```

All migrations should show status `Success`.

### 3.4 Build and Start the Application

```bash
source .env
./gradlew build -x test
./gradlew bootRun
```

**Do NOT pass `--spring.profiles.active=local`** -- you need the real adapters.

### 3.5 Verify Health

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

Expected: `{"status": "UP", ...}` with database health showing `UP`.

---

## 4. Webhook Tunnel Setup (BR-5)

### 4.1 Install ngrok

```bash
# macOS
brew install ngrok

# Or download from https://ngrok.com/download
```

### 4.2 Start the Tunnel

```bash
ngrok http 8080
```

Note the **Forwarding** URL (e.g., `https://a1b2c3d4.ngrok-free.app`). This is your public webhook URL.

### 4.3 Verify the Tunnel

```bash
curl -s https://<your-ngrok-url>/actuator/health
```

Should return the same `{"status": "UP"}` response as localhost.

**Important**: Free ngrok URLs expire after ~2 hours. If the tunnel drops, you will need to restart ngrok and update your webhook registrations in Shopify/CJ.

---

## 5. Shopify Webhook Registration (BR-6)

### 5.1 Register via Shopify Admin

1. Go to your Shopify dev store admin
2. Navigate to **Settings > Notifications**
3. Scroll to the bottom: **Webhooks**
4. Click **Create webhook**
5. Configure:
   - **Event**: `Order creation`
   - **Format**: `JSON`
   - **URL**: `https://<your-ngrok-url>/webhooks/shopify/orders`
   - **Webhook API version**: `2024-01` or latest
6. Click **Save**

### 5.2 Note the Signing Secret

After saving, Shopify shows a signing secret at the bottom of the Webhooks section (shared across all webhooks for the store). Copy this value and set it as `SHOPIFY_WEBHOOK_SECRETS` in your `.env`.

If you already had the app running, you need to **restart** the application for the new secret to take effect (the `ShopifyHmacVerificationFilter` reads secrets at bean construction time).

### 5.3 Verify with Test Webhook

Shopify provides a "Send test notification" button next to each registered webhook.

1. Click **Send test notification** for the `Order creation` webhook
2. Check application logs for:

```
Accepted Shopify webhook event: <event-id>
```

If you see a 401 in the ngrok inspector (`http://localhost:4040`), the HMAC secret does not match. The filter logs:

```
Shopify webhook HMAC verification failed from remote address: <ip>
```

### 5.4 Manual Verification with curl

You can also verify the webhook endpoint directly. This bypasses HMAC verification concerns and tests the controller logic:

```bash
# Generate HMAC for a test payload
SECRET="<your-shopify-webhook-signing-secret>"
BODY='{"id":820982911946154508,"name":"#1001","email":"test@example.com","currency":"USD","line_items":[{"product_id":"632910392","variant_id":"808950810","quantity":1,"price":"24.99","title":"Test Product"}],"shipping_address":{"first_name":"John","last_name":"Doe","address1":"123 Main St","city":"Anytown","province":"CA","province_code":"CA","country":"United States","country_code":"US","zip":"90210","phone":"555-0100"}}'

HMAC=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)

curl -X POST http://localhost:8080/webhooks/shopify/orders \
  -H "Content-Type: application/json" \
  -H "X-Shopify-Topic: orders/create" \
  -H "X-Shopify-Event-Id: test-event-$(date +%s)" \
  -H "X-Shopify-Hmac-SHA256: $HMAC" \
  -d "$BODY"
```

Expected response: `{"status":"accepted"}`

Send the same request again to verify deduplication: `{"status":"already_processed"}`

---

## 6. CJ Webhook Registration (BR-7)

### 6.1 Register in CJ Developer Dashboard

1. Log in to [developers.cjdropshipping.com](https://developers.cjdropshipping.com)
2. Navigate to the webhook/callback configuration section
3. Set the tracking webhook URL to: `https://<your-ngrok-url>/webhooks/cj/tracking`
4. If CJ provides a token/secret configuration field, set it to the same value as your `CJ_WEBHOOK_SECRET`

### 6.2 Caveat: Unverified Authentication

The `CjWebhookTokenVerificationFilter` uses Bearer token authentication:

```
Authorization: Bearer <CJ_WEBHOOK_SECRET>
```

However, CJ's webhook documentation **does not document any authentication mechanism** for outgoing webhooks. The filter comment states: `x-cj-verified: unverified`. This means:

- If CJ sends webhooks **without** an `Authorization` header, they will be **rejected with 401**
- If CJ uses a different auth mechanism (e.g., signature header), the filter will not recognize it
- You may need to temporarily disable or modify the filter if CJ's actual behavior differs

### 6.3 Manual Verification with curl

Since CJ webhook delivery is not always immediate, you can simulate a tracking webhook:

```bash
CJ_SECRET="<your-cj-webhook-secret>"

curl -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $CJ_SECRET" \
  -d '{
    "params": {
      "orderId": "<order-uuid-from-step-8.4>",
      "trackingNumber": "9400111899223456789012",
      "logisticName": "USPS"
    }
  }'
```

Expected response: `{"status":"accepted"}`

---

## 7. Supplier Product Mapping Seeding (BR-8)

The `SupplierProductMappingResolver` queries the `supplier_product_mappings` table to link internal SKUs to CJ products. Without this row, CJ order placement **silently fails** -- the `SupplierOrderPlacementService` logs a warning and marks the order as `FAILED`.

### 7.1 Find a CJ Product

**Option A: CJ Dashboard**

Browse [cjdropshipping.com](https://cjdropshipping.com), find a product, and note its product ID and variant ID from the URL or product details page.

**Option B: CJ API**

```bash
CJ_TOKEN="<your-cj-access-token>"

curl -s "https://developers.cjdropshipping.com/api2.0/v1/product/list" \
  -H "CJ-Access-Token: $CJ_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"pageNum": 1, "pageSize": 5, "productNameEn": "phone case"}' \
  | python3 -m json.tool
```

From the response, note:
- `pid` -- the product ID (use as `supplier_product_id`)
- `variants[].vid` -- the variant ID (use as `supplier_variant_id`)

### 7.2 Insert the Mapping

You need a SKU that exists in the `skus` table and has a `platform_listings` entry (for the Shopify product ID resolution). If you do not have one, see Section 8 Step 1.

```sql
INSERT INTO supplier_product_mappings (id, sku_id, supplier_type, supplier_product_id, supplier_variant_id, warehouse_country_code)
VALUES (
    gen_random_uuid(),
    '<your-sku-uuid>',
    'CJ_DROPSHIPPING',
    '<cj-product-id>',
    '<cj-variant-id>',
    'US'
);
```

**warehouse_country_code**: Sets the `fromCountryCode` in the CJ order request. Use `'US'` for US-based warehouses (faster shipping), `'CN'` for China warehouses. If `NULL`, the adapter defaults to `'CN'`.

### 7.3 Verify the Mapping

```sql
SELECT spm.sku_id, spm.supplier_product_id, spm.supplier_variant_id, spm.warehouse_country_code,
       s.name as sku_name, s.status as sku_status
FROM supplier_product_mappings spm
JOIN skus s ON s.id = spm.sku_id
WHERE spm.supplier_type = 'CJ_DROPSHIPPING';
```

---

## 8. Pipeline Walkthrough (BR-9)

### Pre-Requisites Checklist

Before starting, verify all data dependencies are in place:

```sql
-- 1. SKU exists and is in Listed (or any active) state
SELECT id, name, status FROM skus WHERE id = '<your-sku-uuid>';

-- 2. Platform listing exists linking Shopify product ID to SKU
SELECT sku_id, platform, external_listing_id, external_variant_id, status
FROM platform_listings
WHERE sku_id = '<your-sku-uuid>' AND platform = 'SHOPIFY' AND status = 'ACTIVE';

-- 3. Vendor assignment exists for the SKU
SELECT sku_id, vendor_id, active FROM vendor_sku_assignments
WHERE sku_id = '<your-sku-uuid>' AND active = true;

-- 4. Supplier product mapping exists
SELECT sku_id, supplier_product_id, supplier_variant_id, warehouse_country_code
FROM supplier_product_mappings
WHERE sku_id = '<your-sku-uuid>' AND supplier_type = 'CJ_DROPSHIPPING';
```

If any of these return empty, the pipeline will fail at the corresponding step.

---

### Step 1: Trigger a Shopify Order

**Option A: Place a test order on your Shopify dev store**

1. Make sure the product in your dev store has the same product ID / variant ID as in your `platform_listings` table
2. Enable "Bogus Gateway" test payment in Settings > Payments
3. Place an order through your storefront using test card number `1` for success

**Option B: Send a synthetic webhook via curl** (from Section 5.4)

Use the product ID and variant ID that match your `platform_listings.external_listing_id` and `platform_listings.external_variant_id`.

**What to look for in logs:**

```
Accepted Shopify webhook event: <shopify-event-id>
```

**What a failure looks like:**

- 401 response: HMAC mismatch. Check `SHOPIFY_WEBHOOK_SECRETS` matches the signing secret in Shopify admin.
- `Missing HMAC signature`: The `X-Shopify-Hmac-SHA256` header is absent.
- `Unexpected Shopify webhook topic: <topic>`: Shopify sent a topic other than `orders/create`.
- `Missing X-Shopify-Event-Id header`: Deduplication cannot proceed without an event ID.

**Database verification:**

```sql
SELECT event_id, topic, channel, processed_at
FROM webhook_events
WHERE channel = 'shopify'
ORDER BY processed_at DESC
LIMIT 5;
```

---

### Step 2: Verify Webhook Deduplication

The `ShopifyWebhookController` persists a `WebhookEvent` record before publishing the `ShopifyOrderReceivedEvent`. If the same `X-Shopify-Event-Id` arrives again, it returns `{"status": "already_processed"}`.

**Verification:** Send the same webhook payload with the same `X-Shopify-Event-Id` header twice. The second call should return `already_processed`.

**What to look for in logs:**

```
Duplicate Shopify webhook event: <event-id>
```

---

### Step 3: Verify Internal Order Created

The `ShopifyOrderProcessingService` listens for `ShopifyOrderReceivedEvent` (via `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`) and delegates to `LineItemOrderCreator` for each line item.

**What to look for in logs:**

```
Created and confirmed order <order-uuid> for SKU <sku-uuid> from Shopify order <shopify-order-id>
Order <order-uuid> routed to vendor <vendor-uuid>, status -> CONFIRMED, OrderConfirmed event published
Processed Shopify order <shopify-order-id>: 1 of 1 line items resolved, 1 orders created
```

**What a failure looks like:**

- `Unresolvable line item: productId=<id>, variantId=<id>` -- No matching row in `platform_listings` for the Shopify product/variant ID. Verify `external_listing_id` and `external_variant_id` match the Shopify payload's `product_id` and `variant_id` fields.
- `No vendor assignment for SKU <uuid>` -- No active row in `vendor_sku_assignments` for this SKU.
- `Unsupported currency '<code>' in Shopify order` -- The order's currency is not in the `Currency` enum.

**Database verification:**

```sql
SELECT id, sku_id, vendor_id, status, channel, channel_order_id,
       total_amount, total_currency, quantity,
       shipping_customer_name, shipping_city, shipping_country_code
FROM orders
WHERE channel = 'shopify'
ORDER BY created_at DESC
LIMIT 5;
```

Verify:
- `status` = `CONFIRMED`
- `channel_order_id` matches the Shopify order `id` field
- `shipping_*` fields populated from the webhook payload
- `total_amount` = `price * quantity` from the line item
- `total_currency` matches the webhook `currency` field

**Contract verification point:** Compare the Shopify webhook payload (visible in ngrok inspector at `http://localhost:4040`) with the parsed order. Key field mappings:

| Shopify Payload | Order Column |
|----------------|-------------|
| `id` | `channel_order_id` |
| `name` | `channel_order_number` |
| `line_items[].product_id` | resolved via `platform_listings` -> `sku_id` |
| `line_items[].variant_id` | resolved via `platform_listings` |
| `line_items[].price * quantity` | `total_amount` |
| `currency` | `total_currency` |
| `shipping_address.first_name + last_name` | `shipping_customer_name` |
| `shipping_address.address1` | `shipping_address_line1` |
| `shipping_address.country_code` | `shipping_country_code` |

---

### Step 4: Verify CJ Order Placed

The `SupplierOrderPlacementListener` listens for `OrderConfirmed` events and delegates to `SupplierOrderPlacementService`, which resolves the supplier mapping and calls `CjSupplierOrderAdapter.placeOrder()`.

**What to look for in logs:**

```
OrderConfirmed received for order <order-uuid>, placing supplier order
CJ order <order-uuid>: fromCountryCode=US, logisticName=USPS
CJ order placed successfully: orderId=<cj-order-id>
Supplier order <cj-order-id> placed for order <order-uuid>
```

**What a failure looks like:**

- `No supplier product mapping found for SKU <uuid>` -- Missing row in `supplier_product_mappings`. Order is marked `FAILED`.
- `CJ API credentials not configured` -- `CJ_API_BASE_URL` or `CJ_ACCESS_TOKEN` is blank.
- `CJ order placement failed: <message>` -- CJ returned an error. Common causes:
  - `"Product vid is not exist"` -- Wrong `supplier_variant_id` in the mapping.
  - `"Product not found"` -- Wrong `supplier_product_id`.
  - `"Insufficient inventory"` -- CJ warehouse does not have stock.
  - HTTP 401 -- Invalid `CJ_ACCESS_TOKEN`.

**Database verification:**

```sql
SELECT id, status, supplier_order_id, failure_reason
FROM orders
WHERE id = '<order-uuid>';
```

Verify:
- `status` = `CONFIRMED` (unchanged -- CJ order placement does not change order status)
- `supplier_order_id` is populated with the CJ order ID
- `failure_reason` is NULL

**CJ Dashboard verification:** Log into the CJ dashboard and verify the order appears under your order list. Note the CJ order ID should match `supplier_order_id` in the database.

**Contract verification point:** The CJ `createOrderV2` request body sent by the adapter:

```json
{
  "orderNumber": "<order-uuid>",
  "shippingCountryCode": "US",
  "shippingCountry": "United States",
  "shippingCustomerName": "John Doe",
  "shippingAddress": "123 Main St",
  "shippingCity": "Anytown",
  "shippingProvince": "CA",
  "shippingZip": "90210",
  "shippingPhone": "555-0100",
  "fromCountryCode": "US",
  "logisticName": "USPS",
  "products": [
    {
      "vid": "<cj-variant-id>",
      "quantity": 1
    }
  ]
}
```

The expected CJ success response:

```json
{
  "result": true,
  "code": 200,
  "message": "success",
  "data": {
    "orderId": "<cj-order-id>"
  }
}
```

---

### Step 5: Receive CJ Tracking Webhook (or Simulate)

CJ sends a tracking webhook when a shipment is dispatched. This can take **hours to days** depending on the product and warehouse.

**Option A: Wait for CJ to send the webhook**

If you registered the webhook URL in the CJ dashboard (Section 6), CJ will POST to your endpoint when tracking is available. Monitor the ngrok inspector at `http://localhost:4040`.

**Option B: Simulate with curl** (recommended for initial verification)

```bash
CJ_SECRET="<your-cj-webhook-secret>"
ORDER_UUID="<the-order-uuid-from-step-3>"

curl -X POST http://localhost:8080/webhooks/cj/tracking \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $CJ_SECRET" \
  -d "{
    \"params\": {
      \"orderId\": \"$ORDER_UUID\",
      \"trackingNumber\": \"9400111899223456789012\",
      \"logisticName\": \"USPS\"
    }
  }"
```

**Important:** The `orderId` in the CJ tracking payload must be the **internal Order UUID** (not the CJ order ID). The `CjTrackingProcessingService` uses this to look up the order via `orderRepository.findById(uuid)`. If CJ sends its own order ID instead, the lookup will fail. This is a known contract question -- verify what CJ actually sends in the `orderId` field.

**What to look for in logs:**

```
Accepted CJ tracking webhook event: cj:<order-uuid>:<tracking-number>
```

**What a failure looks like:**

- 401 response: Bearer token mismatch. Check `CJ_WEBHOOK_SECRET`.
- `CJ tracking webhook ignored -- missing orderId or trackingNumber`: Payload structure differs from expected.
- `Duplicate CJ tracking webhook event`: Same orderId + trackingNumber already processed (deduplication working correctly).

**Database verification:**

```sql
SELECT event_id, topic, channel, processed_at
FROM webhook_events
WHERE channel = 'cj'
ORDER BY processed_at DESC
LIMIT 5;
```

---

### Step 6: Verify Order Marked SHIPPED

The `CjTrackingProcessingService` listens for `CjTrackingReceivedEvent` (via `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`) and calls `orderService.markShipped()`.

**What to look for in logs:**

```
CJ tracking processed: order <order-uuid> marked SHIPPED with tracking 9400111899223456789012 via USPS
Order <order-uuid> marked SHIPPED with tracking 9400111899223456789012 via USPS, OrderShipped event published
```

**What a failure looks like:**

- `CJ tracking event <key> has invalid UUID orderId '<value>'` -- The `orderId` from CJ is not a valid UUID.
- `CJ tracking event <key> references unknown order <uuid>` -- No order found with that ID.
- `CJ tracking event <key> for order <uuid> -- expected CONFIRMED but was <status>` -- Order is not in `CONFIRMED` status (e.g., already `SHIPPED` or `FAILED`).
- `CJ tracking processing FAILED for order <uuid> (dedupKey=<key>)` -- Transient failure after dedup commit. **Manual intervention required** -- the dedup record blocks retries.

**Database verification:**

```sql
SELECT id, status, tracking_number, carrier, supplier_order_id
FROM orders
WHERE id = '<order-uuid>';
```

Verify:
- `status` = `SHIPPED`
- `tracking_number` = the tracking number from the webhook
- `carrier` = normalized carrier name (e.g., `USPS`, `UPS`, `FedEx` -- see `CjCarrierMapper`)

**Carrier normalization:** The `CjCarrierMapper` normalizes `logisticName` from CJ:

| CJ logisticName | Normalized Carrier |
|------------------|--------------------|
| `usps` | `USPS` |
| `ups` | `UPS` |
| `fedex` | `FedEx` |
| `dhl` | `DHL` |
| `4px` | `4PX` |
| `yanwen` | `Yanwen` |
| `yunexpress` | `YunExpress` |
| `cainiao` | `Cainiao` |
| `ems` | `EMS` |
| *(anything else)* | *(passed through as-is)* |

---

### Step 7: Verify Shopify Fulfillment Synced

The `ShopifyFulfillmentSyncListener` listens for `OrderShipped` events and calls `ShopifyFulfillmentAdapter.createFulfillment()` via Shopify's GraphQL API.

The adapter performs two GraphQL calls:
1. **Query fulfillment orders**: `order(id: $orderId) { fulfillmentOrders { edges { node { id, status } } } }` -- retrieves `FulfillmentOrder` GIDs with status `OPEN`, `IN_PROGRESS`, or `SCHEDULED`
2. **Create fulfillment**: `fulfillmentCreateV2` mutation with tracking info and fulfillment order GIDs

**What to look for in logs:**

```
OrderShipped received for order <order-uuid>, syncing fulfillment to Shopify
Shopify fulfillment created for order <order-uuid>: fulfillmentId=gid://shopify/Fulfillment/<id>
```

**What a failure looks like:**

- `Shopify access token is blank` -- `SHOPIFY_ACCESS_TOKEN` not configured.
- `No fulfillment orders found for Shopify order <id>` -- The Shopify order has no open fulfillment orders (may already be fulfilled, or the order ID format does not match).
- `Shopify GraphQL error querying fulfillment orders for <gid>` -- Authentication failure (wrong access token) or invalid GID format.
- `Shopify fulfillment userError for order <id>: field=<f>, message=<m>` -- Shopify rejected the fulfillment (e.g., order already fulfilled, invalid tracking number format).
- `Shopify fulfillment sync failed for order <uuid>` -- Exception during API call. **Non-fatal**: the order stays `SHIPPED` in our system.

**Shopify Admin verification:**

1. Go to your Shopify admin > Orders
2. Find the test order
3. Verify it shows as **Fulfilled** with the tracking number and carrier
4. Check that the customer received a shipping notification email (if enabled)

**Contract verification point:** The GraphQL mutation sent to Shopify:

```graphql
mutation fulfillmentCreateV2($fulfillment: FulfillmentV2Input!) {
  fulfillmentCreateV2(fulfillment: $fulfillment) {
    fulfillment { id, status }
    userErrors { field, message }
  }
}
```

Variables:
```json
{
  "fulfillment": {
    "lineItemsByFulfillmentOrder": [
      { "fulfillmentOrderId": "gid://shopify/FulfillmentOrder/<id>" }
    ],
    "trackingInfo": {
      "company": "USPS",
      "number": "9400111899223456789012"
    },
    "notifyCustomer": true
  }
}
```

---

### Step 8: DELIVERED Gap (Documented)

The `SHIPPED -> DELIVERED` transition is triggered by `OrderService.markDelivered()`, which publishes an `OrderFulfilled` event consumed by the capital module's `OrderEventListener` to record revenue and credit reserves.

**This transition is currently unreachable in the live pipeline.** There are no real carrier tracking providers wired to poll for delivery status. The `StubCarrierTrackingConfiguration` exists for the `local` profile only. In production, nothing calls `markDelivered()`.

To manually test the DELIVERED path (for contract verification of the capital module):

```bash
# Direct API call or SQL update -- use with caution
psql -h localhost -U autoshipper -d autoshipper -c "
  UPDATE orders SET status = 'DELIVERED', updated_at = NOW()
  WHERE id = '<order-uuid>' AND status = 'SHIPPED';
"
```

**Note:** This SQL bypass does not fire the `OrderFulfilled` domain event, so the capital module will not record the order. To properly test capital recording, you would need to call `OrderService.markDelivered()` programmatically (e.g., via a temporary REST endpoint or test harness).

---

## 9. Observability (BR-10)

### Key Log Patterns

Search for these patterns at each pipeline step:

| Step | Log Pattern |
|------|------------|
| Webhook received | `Accepted Shopify webhook event:` |
| Webhook deduped | `Duplicate Shopify webhook event:` |
| HMAC failure | `Shopify webhook HMAC verification failed` |
| Order created | `Created and confirmed order` |
| Line item unresolvable | `Unresolvable line item:` |
| Supplier order placed | `CJ order placed successfully:` |
| Supplier order failed | `CJ order placement failed:` |
| No supplier mapping | `No supplier product mapping found` |
| CJ tracking accepted | `Accepted CJ tracking webhook event:` |
| Order shipped | `CJ tracking processed: order` |
| Tracking processing failed | `CJ tracking processing FAILED` |
| Fulfillment synced | `Shopify fulfillment created for order` |
| Fulfillment failed | `Shopify fulfillment sync failed` |

### Useful SQL Queries

**Pipeline status overview:**

```sql
SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY status;
```

**Recent orders with full pipeline state:**

```sql
SELECT o.id, o.status, o.channel_order_id, o.supplier_order_id,
       o.tracking_number, o.carrier, o.failure_reason,
       o.created_at, o.updated_at
FROM orders o
ORDER BY o.created_at DESC
LIMIT 20;
```

**Webhook events (recent):**

```sql
SELECT event_id, topic, channel, processed_at
FROM webhook_events
ORDER BY processed_at DESC
LIMIT 20;
```

**Failed orders requiring investigation:**

```sql
SELECT id, sku_id, status, failure_reason, created_at
FROM orders
WHERE status = 'FAILED'
ORDER BY created_at DESC;
```

**Supplier mapping coverage:**

```sql
SELECT s.id as sku_id, s.name, s.status,
       spm.supplier_product_id, spm.supplier_variant_id, spm.warehouse_country_code,
       pl.external_listing_id, pl.platform, pl.status as listing_status
FROM skus s
LEFT JOIN supplier_product_mappings spm ON spm.sku_id = s.id
LEFT JOIN platform_listings pl ON pl.sku_id = s.id
WHERE s.status = 'Listed';
```

### Actuator Endpoints

```bash
# Health check with component details
curl -s http://localhost:8080/actuator/health | python3 -m json.tool

# Prometheus metrics (for circuit breaker states)
curl -s http://localhost:8080/actuator/prometheus | grep resilience4j

# Application info
curl -s http://localhost:8080/actuator/info
```

---

## 10. Known Gaps

| Gap | Impact | Workaround |
|-----|--------|-----------|
| **DELIVERED unreachable** | `OrderService.markDelivered()` is never called in the live pipeline. Capital recording (`OrderEventListener.onOrderFulfilled`) never fires. | Manual SQL update or temporary REST endpoint. Does not trigger domain events. |
| **Carrier tracking stubbed** | No real UPS/FedEx/USPS tracking provider polls for delivery status. `StubCarrierTrackingConfiguration` only active under `local` profile. | Out of scope for Phase 1. |
| **CJ webhook auth unverified** | `CjWebhookTokenVerificationFilter` uses Bearer token auth, but CJ docs do not document any outgoing webhook authentication. Real CJ webhooks may lack an `Authorization` header and be rejected with 401. | If CJ webhooks are rejected, disable or modify the filter. Monitor ngrok inspector for rejected requests. |
| **Replay protection disabled** | `shopify.webhook.replay-protection.enabled: false` by default. Replayed webhooks within the `max-age-seconds` window are accepted (deduplication still prevents duplicate processing). | Enable in production with `enabled: true` and set `max-age-seconds: 300`. |
| **CJ tracking orderId format** | `CjTrackingProcessingService` expects `params.orderId` to be an internal Order UUID. If CJ sends its own order ID instead, the `UUID.fromString()` parse will fail. | Verify CJ's actual webhook payload structure. May need to add a lookup by `supplier_order_id` instead of direct UUID parse. |
| **Inventory check against Shopify** | `ShopifyInventoryCheckAdapter` queries Shopify's inventory API using the SKU UUID as `inventory_item_id`, which is unlikely to match Shopify's actual inventory item IDs. | For E2E testing, this may block order creation with `"SKU <uuid> is not available in inventory"`. May need to add a mapping or use a different inventory strategy. |

---

## 11. Troubleshooting

### Webhook Returns 401 (Shopify)

**Symptom:** ngrok inspector shows 401 response to Shopify webhook.

**Cause:** HMAC mismatch between the webhook body + signing secret and the `X-Shopify-Hmac-SHA256` header.

**Fix:**
1. Verify `SHOPIFY_WEBHOOK_SECRETS` in your `.env` matches the signing secret shown in Shopify admin (Settings > Notifications > Webhooks)
2. The secret is at the **bottom** of the webhooks section, not per-webhook
3. Restart the application after changing the secret
4. Check logs for: `Shopify webhook HMAC verification failed from remote address:`
5. If using multiple secrets (key rotation), ensure they are comma-separated: `secret1,secret2`

### Webhook Returns 401 (CJ)

**Symptom:** CJ tracking webhook rejected with 401.

**Cause:** Missing or mismatched Bearer token in `Authorization` header.

**Fix:**
1. Ensure `CJ_WEBHOOK_SECRET` in `.env` matches the token configured in the CJ dashboard
2. If CJ does not send an `Authorization` header at all (see Known Gaps), you may need to temporarily bypass the filter
3. Check ngrok inspector (`http://localhost:4040`) to see the exact headers CJ sends

### Order Created But Supplier Order Not Placed (Silent Failure)

**Symptom:** Order is `CONFIRMED` but `supplier_order_id` is NULL.

**Possible causes:**
1. **No supplier mapping**: Check `supplier_product_mappings` for the order's `sku_id`. Log: `No supplier product mapping found for SKU <uuid>`
2. **CJ credentials blank**: Check `CJ_API_BASE_URL` and `CJ_ACCESS_TOKEN`. Log: `CJ Dropshipping API credentials blank`
3. **Event listener did not fire**: The `SupplierOrderPlacementListener` uses `@TransactionalEventListener(AFTER_COMMIT)`. If the order creation transaction did not commit, the listener never fires. Check for exceptions in the order creation logs.
4. **CJ API error**: Check logs for `CJ order placement failed:` with the specific error message.

**Diagnostic query:**

```sql
SELECT o.id, o.status, o.supplier_order_id, o.failure_reason, o.sku_id,
       spm.supplier_product_id, spm.supplier_variant_id
FROM orders o
LEFT JOIN supplier_product_mappings spm ON spm.sku_id = o.sku_id AND spm.supplier_type = 'CJ_DROPSHIPPING'
WHERE o.supplier_order_id IS NULL AND o.status = 'CONFIRMED'
ORDER BY o.created_at DESC;
```

### Line Items Not Resolved (No Order Created)

**Symptom:** Webhook accepted but no order appears in the `orders` table.

**Cause:** `PlatformListingResolver` cannot match the Shopify `product_id`/`variant_id` to a row in `platform_listings`.

**Fix:**
1. Check the Shopify webhook payload (ngrok inspector) for the actual `product_id` and `variant_id` values
2. Verify `platform_listings` has a matching row:

```sql
SELECT * FROM platform_listings
WHERE platform = 'SHOPIFY'
  AND external_listing_id = '<shopify-product-id>'
  AND status = 'ACTIVE';
```

3. Also verify `vendor_sku_assignments` exists for the resolved SKU:

```sql
SELECT * FROM vendor_sku_assignments
WHERE sku_id = '<sku-uuid>' AND active = true;
```

### CJ API Returns 400 (Bad Request)

**Symptom:** `CJ order placement failed: <error message>`

**Common causes:**
- Wrong `supplier_variant_id` (vid) -- `"Product vid is not exist"`
- Product out of stock at the specified warehouse -- `"Insufficient inventory"`
- Invalid address format -- CJ has specific requirements for country codes and address fields
- Missing required fields -- Check the CJ API docs for `createOrderV2` requirements

### ngrok Tunnel Expired

**Symptom:** Webhooks stop arriving; ngrok shows disconnected.

**Fix:**
1. Restart ngrok: `ngrok http 8080`
2. Note the new public URL
3. Update the webhook URL in Shopify admin (Settings > Notifications > Webhooks)
4. Update the webhook URL in the CJ developer dashboard
5. Free ngrok accounts get random URLs each time -- consider a paid plan for stable URLs

### Database Connection Refused

**Symptom:** Application fails to start with `Connection refused` to PostgreSQL.

**Fix:**
1. Verify Docker is running: `docker compose ps`
2. Restart if needed: `docker compose up -d`
3. Verify port: `psql -h localhost -U autoshipper -d autoshipper -c "SELECT 1;"`
4. Check `DB_URL` in `.env` matches the Docker Compose configuration (`localhost:5432`)

### Flyway Migration Failure

**Symptom:** Application fails to start with Flyway validation or migration errors.

**Fix:**
1. Check migration status: `./gradlew flywayInfo`
2. If checksum mismatch: `./gradlew flywayRepair` then `./gradlew flywayMigrate`
3. If on a fresh database, ensure the `autoshipper` database exists:

```bash
psql -h localhost -U autoshipper -d postgres -c "CREATE DATABASE autoshipper;"
```

### Inventory Check Blocking Order Creation

**Symptom:** Order creation fails with `"SKU <uuid> is not available in inventory"`.

**Cause:** `ShopifyInventoryCheckAdapter` (active in non-local profile) queries Shopify's inventory API using the internal SKU UUID, which does not match Shopify's inventory item IDs.

**Workaround:** This is a known limitation. For E2E testing, you may need to:
1. Temporarily add `local` to profiles just for the `InventoryChecker` bean (not recommended -- it also stubs other adapters)
2. Create a temporary override that always returns `true`
3. Ensure your Shopify dev store has inventory tracking disabled for the test product
