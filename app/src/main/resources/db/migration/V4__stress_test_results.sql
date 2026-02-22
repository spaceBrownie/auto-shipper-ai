CREATE TABLE sku_stress_test_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id UUID NOT NULL REFERENCES skus(id),
    currency VARCHAR(3) NOT NULL,
    stressed_shipping_amount NUMERIC(19,4) NOT NULL,
    stressed_cac_amount NUMERIC(19,4) NOT NULL,
    stressed_supplier_amount NUMERIC(19,4) NOT NULL,
    stressed_refund_amount NUMERIC(19,4) NOT NULL,
    stressed_chargeback_amount NUMERIC(19,4) NOT NULL,
    stressed_total_cost_amount NUMERIC(19,4) NOT NULL,
    estimated_price_amount NUMERIC(19,4) NOT NULL,
    gross_margin_percent NUMERIC(8,4) NOT NULL,
    net_margin_percent NUMERIC(8,4) NOT NULL,
    passed BOOLEAN NOT NULL,
    shipping_multiplier_used NUMERIC(8,4) NOT NULL,
    cac_increase_percent_used NUMERIC(8,4) NOT NULL,
    supplier_increase_percent_used NUMERIC(8,4) NOT NULL,
    refund_rate_percent_used NUMERIC(8,4) NOT NULL,
    chargeback_rate_percent_used NUMERIC(8,4) NOT NULL,
    tested_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_stress_test_results_sku_id ON sku_stress_test_results(sku_id);
