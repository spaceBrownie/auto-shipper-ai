# E2E Test Playbook

End-to-end manual test script for the full SKU lifecycle through capital protection, compliance guards, and portfolio orchestration.
Covers: SKU creation, compliance checks, state machine, cost gate, stress test, pricing, orders, reserve management, margin monitoring, automated shutdown rules, portfolio experiments, kill window monitoring, priority ranking, and demand scan job.

**Last validated:** 2026-03-18 on branch `feat/FR-016-demand-scan-job`

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
                 demand_candidates, candidate_rejections, demand_scan_runs
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

### 8.2 Trigger Demand Scan

```bash
curl -s -X POST http://localhost:8080/api/portfolio/demand-scan/trigger | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `message` | `"Demand scan triggered successfully"` |
| HTTP status | `200` |

### 8.3 Verify Scan Status

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/status | python3 -m json.tool
```

| Field | Expected |
|---|---|
| `lastRunStatus` | `COMPLETED` |
| `sourcesQueried` | `3` (CJ, Google Trends, Amazon — all stubs in local profile) |
| `candidatesFound` | `≥ 5` (dedup may reduce from 10 raw) |
| `experimentsCreated` | `≥ 1` (candidates above 0.6 threshold) |
| `rejections` | `≥ 1` (candidates below 0.6 threshold) |

### 8.4 Verify Scored Candidates

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/candidates | python3 -m json.tool
```

| Check | Expected |
|---|---|
| Response is non-empty array | Yes |
| Each entry has `demandScore`, `marginPotentialScore`, `competitionScore`, `compositeScore` | All present, 0-1 range |
| Entries with `passed: true` | Have `compositeScore >= 0.6` |
| Entries with `passed: false` | Have `compositeScore < 0.6` |
| `sourceType` values | Mix of `CJ_DROPSHIPPING`, `GOOGLE_TRENDS`, `AMAZON_CREATORS_API` |

### 8.5 Verify Rejections

```bash
curl -s http://localhost:8080/api/portfolio/demand-scan/rejections | python3 -m json.tool
```

| Check | Expected |
|---|---|
| Response is non-empty array | Yes |
| Each entry has `rejectionReason` | `"Below scoring threshold"` |
| Each entry has dimension scores | Present |

### 8.6 Verify Experiments Created

Passing candidates should have created `Experiment` records automatically:

```bash
curl -s http://localhost:8080/api/portfolio/experiments | python3 -m json.tool
```

| Check | Expected |
|---|---|
| Experiments with `sourceSignal` starting with `GOOGLE_TRENDS:` or `AMAZON_CREATORS_API:` | Present |
| `status` | `ACTIVE` |
| `validationWindowDays` | `30` |
| `hypothesis` | Contains `"Demand signal detected via"` |

### 8.7 Verify Cooldown / Idempotency

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

### 8.8 Verify Blacklist Filtering (Optional)

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

### 8.9 Verify pg_trgm Dedup (Optional)

Check that trigram similarity dedup is working at the database level:

```bash
PGPASSWORD=autoshipper psql -h localhost -U autoshipper -d autoshipper -c "
  SELECT product_name, similarity(product_name, 'Silicone Collapsible Water Bottle')
  FROM demand_candidates
  WHERE similarity(product_name, 'Silicone Collapsible Water Bottle') > 0.3
  ORDER BY similarity DESC;
"
```

Expected: Both "Silicone Collapsible Water Bottle" (CJ) and "Collapsible Silicone Water Bottle 600ml" (Amazon) should appear with similarity > 0.3, validating the trigram index.

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
| `POST` | `/api/compliance/skus/{id}/check` | Trigger compliance check (FR-011) |
| `GET` | `/api/compliance/skus/{id}` | Compliance status + audit history |
| `POST` | `/api/vendors` | Create vendor |
| `PATCH` | `/api/vendors/{id}/checklist` | Update onboarding checklist |
| `POST` | `/api/vendors/{id}/activate` | Activate vendor |
| `POST` | `/api/orders` | Create order |
| `GET` | `/api/capital/reserve` | Reserve balance + health |
| `GET` | `/api/capital/skus/{id}/pnl?from=&to=` | SKU P&L report |
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

---

## Known Gaps

| Gap | Impact | Workaround |
|---|---|---|
| No REST endpoints for order state transitions (`routeToVendor`, `markShipped`, `markDelivered`) | Cannot test `OrderEventListener` AFTER_COMMIT chain via HTTP | Update order status via DB; manually insert `capital_order_records` |
| No REST endpoint to trigger `MarginSweepJob` on demand | Must restart app to trigger sweep | Restart the application; sweep fires immediately on startup |
| `MarginSweepSkuProcessor` estimates cost as `revenue * 0.50` | P&L cost figures are approximations, not from cost envelope | Acceptable for Phase 1; will use real cost envelope data later |
| No REST endpoint to trigger `KillWindowMonitor` on demand | Must restart app or wait for 1 AM cron | Restart the application; or insert qualifying data and check after next scheduled run |
| Kill recommendation confirm does not auto-terminate SKU | Manual step needed after confirming recommendation | Call `POST /api/skus/{id}/state` with `TERMINATED` after confirming |
| Compliance `SourcingCheckService` needs vendor data not in Sku entity | `vendorId` must be passed manually in the request body | Provide `vendorId` in `ManualCheckRequest` or use auto-check via `SkuReadyForComplianceCheck` event |

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

**Rule:** Any `@TransactionalEventListener(AFTER_COMMIT)` handler that writes to the database **must** use `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Without it, JPA operations silently succeed but are never flushed.
