CREATE TABLE sku_cost_envelopes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id UUID NOT NULL REFERENCES skus(id),
    currency VARCHAR(3) NOT NULL,
    supplier_unit_cost_amount NUMERIC(19,4) NOT NULL,
    inbound_shipping_amount NUMERIC(19,4) NOT NULL,
    outbound_shipping_amount NUMERIC(19,4) NOT NULL,
    platform_fee_amount NUMERIC(19,4) NOT NULL,
    processing_fee_amount NUMERIC(19,4) NOT NULL,
    packaging_cost_amount NUMERIC(19,4) NOT NULL,
    return_handling_cost_amount NUMERIC(19,4) NOT NULL,
    customer_acquisition_cost_amount NUMERIC(19,4) NOT NULL,
    warehousing_cost_amount NUMERIC(19,4) NOT NULL,
    customer_service_cost_amount NUMERIC(19,4) NOT NULL,
    refund_allowance_amount NUMERIC(19,4) NOT NULL,
    chargeback_allowance_amount NUMERIC(19,4) NOT NULL,
    taxes_and_duties_amount NUMERIC(19,4) NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cost_envelopes_sku_id ON sku_cost_envelopes(sku_id);
