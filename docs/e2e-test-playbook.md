# E2E Test Playbook

End-to-end manual test script for the full SKU lifecycle through capital protection.
Covers: SKU creation, state machine, cost gate, stress test, pricing, orders, reserve management, margin monitoring, and automated shutdown rules.

**Last validated:** 2026-03-12 on branch `feat/FR-009-capital-protection`

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
                 sku_cost_envelopes, stress_test_results, sku_prices, sku_pricing_history,
                 vendors, vendor_sku_assignments, vendor_breach_log
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

### 1.2 Advance: IDEATION to VALIDATION_PENDING

```bash
curl -s -X POST "http://localhost:8080/api/skus/$SKU_ID/state" \
  -H "Content-Type: application/json" \
  -d '{"state":"VALIDATION_PENDING"}' | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `currentState` | `VALIDATION_PENDING` |

### 1.3 Advance: VALIDATION_PENDING to COST_GATING

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

### 2.3 Advance Order to DELIVERED (via DB)

There are no REST endpoints for `routeToVendor`, `markShipped`, or `markDelivered` — these are internal service methods invoked by the `ShipmentTracker` scheduler. For E2E testing, advance the order through the DB:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  UPDATE orders SET status = 'CONFIRMED', updated_at = NOW() WHERE id = '$ORDER_ID';
  UPDATE orders SET status = 'SHIPPED', tracking_number = 'TRK123456', carrier = 'UPS', updated_at = NOW() WHERE id = '$ORDER_ID';
  UPDATE orders SET status = 'DELIVERED', updated_at = NOW() WHERE id = '$ORDER_ID';
"
```

**Note:** This DB-only approach bypasses `OrderService.markDelivered()`, so the `OrderFulfilled` event is NOT published and the `OrderEventListener` does NOT fire. To test the full AFTER_COMMIT event chain for `OrderEventListener`, you would need a REST endpoint for order state transitions (not yet implemented — see [Gap](#known-gaps) below).

---

## Phase 3: Capital Module — Reserve and P&L

### 3.1 Seed Capital Data

Since the order was advanced via DB (bypassing `OrderEventListener`), manually insert the capital order record and reserve account:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  -- Capital order record (what OrderEventListener would create)
  INSERT INTO capital_order_records (id, order_id, sku_id, total_amount, currency, status, refunded, chargebacked, recorded_at)
  VALUES (gen_random_uuid(), '$ORDER_ID', '$SKU_ID'::uuid, 199.9900, 'USD', 'DELIVERED', false, false, NOW());

  -- Add more orders for meaningful P&L data (10 orders over 10 days)
  INSERT INTO capital_order_records (id, order_id, sku_id, total_amount, currency, status, refunded, chargebacked, recorded_at)
  SELECT gen_random_uuid(), gen_random_uuid(), '$SKU_ID'::uuid, 199.9900, 'USD', 'DELIVERED', false, false, NOW() - (i || ' days')::interval
  FROM generate_series(1, 9) AS s(i);

  -- Reserve account (10% of 10 orders x \$199.99 = \$199.99)
  INSERT INTO reserve_accounts (id, balance_amount, balance_currency, target_rate_min, target_rate_max, last_updated_at, version)
  VALUES (gen_random_uuid(), 199.9900, 'USD', 10, 15, NOW(), 0);
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

## Quick Reference: All Endpoints Used

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/actuator/health` | App health check |
| `POST` | `/api/skus` | Create SKU |
| `GET` | `/api/skus/{id}` | Get SKU state |
| `POST` | `/api/skus/{id}/state` | Transition SKU (`{"state":"..."}`) |
| `POST` | `/api/skus/{id}/verify-costs` | Cost gate verification |
| `POST` | `/api/skus/{id}/stress-test` | Stress test (`{"estimatedPriceAmount":..., "currency":"USD"}`) |
| `GET` | `/api/skus/{id}/pricing` | Pricing data |
| `POST` | `/api/vendors` | Create vendor |
| `PATCH` | `/api/vendors/{id}/checklist` | Update onboarding checklist |
| `POST` | `/api/vendors/{id}/activate` | Activate vendor |
| `POST` | `/api/orders` | Create order |
| `GET` | `/api/capital/reserve` | Reserve balance + health |
| `GET` | `/api/capital/skus/{id}/pnl?from=&to=` | SKU P&L report |

---

## Known Gaps

| Gap | Impact | Workaround |
|---|---|---|
| No REST endpoints for order state transitions (`routeToVendor`, `markShipped`, `markDelivered`) | Cannot test `OrderEventListener` AFTER_COMMIT chain via HTTP | Update order status via DB; manually insert `capital_order_records` |
| No REST endpoint to trigger `MarginSweepJob` on demand | Must restart app to trigger sweep | Restart the application; sweep fires immediately on startup |
| `MarginSweepSkuProcessor` estimates cost as `revenue * 0.50` | P&L cost figures are approximations, not from cost envelope | Acceptable for Phase 1; will use real cost envelope data later |

---

## Transaction Safety Invariants (What This Playbook Validates)

These are the cross-module event listener patterns that must hold for the system to work correctly. All were validated by PM-001 and PM-005 postmortems.

| Listener | Event | Pattern | If broken |
|---|---|---|---|
| `PricingInitializer` | `SkuStateChanged` | `AFTER_COMMIT` + `REQUIRES_NEW` | Pricing never persisted (PM-001) |
| `ShutdownRuleListener` | `ShutdownRuleTriggered` | `AFTER_COMMIT` + `REQUIRES_NEW` | SKU never auto-paused (PM-005) |
| `VendorBreachListener` | `VendorSlaBreached` | `AFTER_COMMIT` + `REQUIRES_NEW` | SKU never paused on SLA breach (PM-005) |
| `OrderEventListener` | `OrderFulfilled` | `AFTER_COMMIT` + `REQUIRES_NEW` | Reserve never credited; fulfillment tx at risk (this PR) |
| `PricingDecisionListener` | `PricingDecision` | `AFTER_COMMIT` + `REQUIRES_NEW` | Price sync / state transition lost |

**Rule:** Any `@TransactionalEventListener(AFTER_COMMIT)` handler that writes to the database **must** use `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Without it, JPA operations silently succeed but are never flushed.
