-- V6: Seed data for local development
-- Four SKUs covering distinct lifecycle stages:
--   SKU 1 (10000000-...-001)  Electronics / Bluetooth Speaker Pro  → LISTED      (stress test passed)
--   SKU 2 (20000000-...-002)  Fitness      / Bamboo Yoga Mat        → STRESS_TESTING (awaiting test)
--   SKU 3 (30000000-...-003)  Accessories  / Minimalist Leather Wallet → IDEATION  (just created)
--   SKU 4 (40000000-...-004)  Electronics  / USB-C Charging Hub     → TERMINATED  (stress test failed)

-- ============================================================
-- SKU 1: Bluetooth Speaker Pro — LISTED
-- all-in cost $36.55 | price $99.99 | stressed total $43.5493 | gross margin 56.45% ✓
-- ============================================================
INSERT INTO skus (id, name, category, current_state, termination_reason, version, created_at, updated_at)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    'Bluetooth Speaker Pro',
    'Electronics',
    'LISTED',
    NULL,
    4,
    NOW() - INTERVAL '14 days',
    NOW() - INTERVAL '10 days'
);

INSERT INTO sku_state_history (sku_id, from_state, to_state, transitioned_at) VALUES
    ('10000000-0000-0000-0000-000000000001', 'IDEATION',           'VALIDATION_PENDING', NOW() - INTERVAL '14 days'),
    ('10000000-0000-0000-0000-000000000001', 'VALIDATION_PENDING', 'COST_GATING',        NOW() - INTERVAL '13 days'),
    ('10000000-0000-0000-0000-000000000001', 'COST_GATING',        'STRESS_TESTING',     NOW() - INTERVAL '12 days'),
    ('10000000-0000-0000-0000-000000000001', 'STRESS_TESTING',     'LISTED',             NOW() - INTERVAL '10 days');

INSERT INTO sku_cost_envelopes (
    id, sku_id, currency,
    supplier_unit_cost_amount, inbound_shipping_amount, outbound_shipping_amount,
    platform_fee_amount, processing_fee_amount, packaging_cost_amount,
    return_handling_cost_amount, customer_acquisition_cost_amount, warehousing_cost_amount,
    customer_service_cost_amount, refund_allowance_amount, chargeback_allowance_amount,
    taxes_and_duties_amount, verified_at, created_at
) VALUES (
    'a0000001-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    'USD',
    8.0000,   -- vendor quote (supplier)
    0.0000,   -- inbound shipping (not tracked in Phase 1)
    5.0000,   -- outbound shipping — cheapest carrier (USPS Ground)
    2.5000,   -- Shopify platform fee
    3.5000,   -- Stripe processing fee
    0.5000,   -- packaging
    0.8000,   -- return handling
    8.0000,   -- customer acquisition cost (CAC)
    0.7500,   -- warehousing
    0.5000,   -- customer service
    5.0000,   -- refund allowance  (5% of $99.99)
    2.0000,   -- chargeback allowance (2% of $99.99)
    0.0000,   -- taxes & duties (domestic US)
    NOW() - INTERVAL '12 days',
    NOW() - INTERVAL '12 days'
);

-- stressed total = $43.5493 → gross margin = (99.99 − 43.5493) / 99.99 × 100 = 56.4463 % ≥ 50 % ✓
INSERT INTO sku_stress_test_results (
    id, sku_id, currency,
    stressed_shipping_amount, stressed_cac_amount, stressed_supplier_amount,
    stressed_refund_amount, stressed_chargeback_amount, stressed_total_cost_amount,
    estimated_price_amount, gross_margin_percent, net_margin_percent, passed,
    shipping_multiplier_used, cac_increase_percent_used, supplier_increase_percent_used,
    refund_rate_percent_used, chargeback_rate_percent_used, tested_at, created_at
) VALUES (
    'b0000001-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    'USD',
    10.0000,  -- stressed shipping  (5.00 × 2.0)
    9.2000,   -- stressed CAC       (8.00 × 1.15)
    8.8000,   -- stressed supplier  (8.00 × 1.10)
    4.9995,   -- stressed refund    (99.99 × 0.05)
    1.9998,   -- stressed chargeback(99.99 × 0.02)
    43.5493,  -- stressed total cost
    99.9900,  -- estimated price
    56.4463,  -- gross margin %
    56.4463,  -- net margin % (equals gross in Phase 1)
    TRUE,
    2.0000, 15.0000, 10.0000, 5.0000, 2.0000,
    NOW() - INTERVAL '10 days',
    NOW() - INTERVAL '10 days'
);

-- ============================================================
-- SKU 2: Bamboo Yoga Mat — STRESS_TESTING
-- all-in cost $30.20 | awaiting stress test (use requests/catalog/stress-test.json at $89.99 to pass)
-- ============================================================
INSERT INTO skus (id, name, category, current_state, termination_reason, version, created_at, updated_at)
VALUES (
    '20000000-0000-0000-0000-000000000002',
    'Bamboo Yoga Mat',
    'Fitness',
    'STRESS_TESTING',
    NULL,
    3,
    NOW() - INTERVAL '5 days',
    NOW() - INTERVAL '3 days'
);

INSERT INTO sku_state_history (sku_id, from_state, to_state, transitioned_at) VALUES
    ('20000000-0000-0000-0000-000000000002', 'IDEATION',           'VALIDATION_PENDING', NOW() - INTERVAL '5 days'),
    ('20000000-0000-0000-0000-000000000002', 'VALIDATION_PENDING', 'COST_GATING',        NOW() - INTERVAL '4 days'),
    ('20000000-0000-0000-0000-000000000002', 'COST_GATING',        'STRESS_TESTING',     NOW() - INTERVAL '3 days');

INSERT INTO sku_cost_envelopes (
    id, sku_id, currency,
    supplier_unit_cost_amount, inbound_shipping_amount, outbound_shipping_amount,
    platform_fee_amount, processing_fee_amount, packaging_cost_amount,
    return_handling_cost_amount, customer_acquisition_cost_amount, warehousing_cost_amount,
    customer_service_cost_amount, refund_allowance_amount, chargeback_allowance_amount,
    taxes_and_duties_amount, verified_at, created_at
) VALUES (
    'a0000002-0000-0000-0000-000000000002',
    '20000000-0000-0000-0000-000000000002',
    'USD',
    6.0000,   -- vendor quote
    0.0000,   -- inbound shipping
    7.0000,   -- outbound shipping (UPS Ground — bulky roll)
    1.2500,   -- Shopify platform fee
    1.7500,   -- Stripe processing fee
    1.0000,   -- packaging (custom tube/box)
    1.2000,   -- return handling
    7.0000,   -- CAC
    1.0000,   -- warehousing (large item)
    0.5000,   -- customer service
    2.5000,   -- refund allowance  (5% of $49.99)
    1.0000,   -- chargeback allowance (2% of $49.99)
    0.0000,   -- taxes & duties
    NOW() - INTERVAL '3 days',
    NOW() - INTERVAL '3 days'
);

-- ============================================================
-- SKU 3: Minimalist Leather Wallet — IDEATION
-- ============================================================
INSERT INTO skus (id, name, category, current_state, termination_reason, version, created_at, updated_at)
VALUES (
    '30000000-0000-0000-0000-000000000003',
    'Minimalist Leather Wallet',
    'Accessories',
    'IDEATION',
    NULL,
    0,
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '1 day'
);
-- No state history, cost envelope, or stress test result yet.

-- ============================================================
-- SKU 4: USB-C Charging Hub — TERMINATED (STRESS_TEST_FAILED)
-- all-in cost $26.50 | price $39.99 | stressed total $32.8493 | gross margin 17.86% ✗
-- ============================================================
INSERT INTO skus (id, name, category, current_state, termination_reason, version, created_at, updated_at)
VALUES (
    '40000000-0000-0000-0000-000000000004',
    'USB-C Charging Hub',
    'Electronics',
    'TERMINATED',
    'STRESS_TEST_FAILED',
    4,
    NOW() - INTERVAL '21 days',
    NOW() - INTERVAL '18 days'
);

INSERT INTO sku_state_history (sku_id, from_state, to_state, transitioned_at) VALUES
    ('40000000-0000-0000-0000-000000000004', 'IDEATION',           'VALIDATION_PENDING', NOW() - INTERVAL '21 days'),
    ('40000000-0000-0000-0000-000000000004', 'VALIDATION_PENDING', 'COST_GATING',        NOW() - INTERVAL '20 days'),
    ('40000000-0000-0000-0000-000000000004', 'COST_GATING',        'STRESS_TESTING',     NOW() - INTERVAL '19 days'),
    ('40000000-0000-0000-0000-000000000004', 'STRESS_TESTING',     'TERMINATED',         NOW() - INTERVAL '18 days');

INSERT INTO sku_cost_envelopes (
    id, sku_id, currency,
    supplier_unit_cost_amount, inbound_shipping_amount, outbound_shipping_amount,
    platform_fee_amount, processing_fee_amount, packaging_cost_amount,
    return_handling_cost_amount, customer_acquisition_cost_amount, warehousing_cost_amount,
    customer_service_cost_amount, refund_allowance_amount, chargeback_allowance_amount,
    taxes_and_duties_amount, verified_at, created_at
) VALUES (
    'a0000004-0000-0000-0000-000000000004',
    '40000000-0000-0000-0000-000000000004',
    'USD',
    5.0000,   -- vendor quote
    0.0000,   -- inbound shipping
    4.5000,   -- outbound shipping
    1.0000,   -- Shopify platform fee
    1.4000,   -- Stripe processing fee
    0.5000,   -- packaging
    0.8000,   -- return handling
    9.0000,   -- CAC (too high relative to $39.99 price)
    0.6000,   -- warehousing
    0.4000,   -- customer service
    2.0000,   -- refund allowance  (5% of $39.99)
    0.8000,   -- chargeback allowance (2% of $39.99)
    0.5000,   -- taxes & duties
    NOW() - INTERVAL '19 days',
    NOW() - INTERVAL '19 days'
);

-- stressed total = $32.8493 → gross margin = (39.99 − 32.8493) / 39.99 × 100 = 17.8562 % < 50 % ✗
INSERT INTO sku_stress_test_results (
    id, sku_id, currency,
    stressed_shipping_amount, stressed_cac_amount, stressed_supplier_amount,
    stressed_refund_amount, stressed_chargeback_amount, stressed_total_cost_amount,
    estimated_price_amount, gross_margin_percent, net_margin_percent, passed,
    shipping_multiplier_used, cac_increase_percent_used, supplier_increase_percent_used,
    refund_rate_percent_used, chargeback_rate_percent_used, tested_at, created_at
) VALUES (
    'b0000004-0000-0000-0000-000000000004',
    '40000000-0000-0000-0000-000000000004',
    'USD',
    9.0000,   -- stressed shipping  (4.50 × 2.0)
    10.3500,  -- stressed CAC       (9.00 × 1.15)
    5.5000,   -- stressed supplier  (5.00 × 1.10)
    1.9995,   -- stressed refund    (39.99 × 0.05)
    0.7998,   -- stressed chargeback(39.99 × 0.02)
    32.8493,  -- stressed total cost
    39.9900,  -- estimated price
    17.8562,  -- gross margin %
    17.8562,  -- net margin %
    FALSE,
    2.0000, 15.0000, 10.0000, 5.0000, 2.0000,
    NOW() - INTERVAL '18 days',
    NOW() - INTERVAL '18 days'
);
