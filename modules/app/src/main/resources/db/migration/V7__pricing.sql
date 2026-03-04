CREATE TABLE sku_prices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id UUID NOT NULL REFERENCES skus(id),
    currency VARCHAR(3) NOT NULL,
    current_price_amount NUMERIC(19,4) NOT NULL,
    current_margin_percent NUMERIC(8,4) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sku_prices_sku_id UNIQUE (sku_id)
);
CREATE INDEX idx_sku_prices_sku_id ON sku_prices(sku_id);

CREATE TABLE sku_pricing_history (
    id BIGSERIAL PRIMARY KEY,
    sku_id UUID NOT NULL REFERENCES skus(id),
    currency VARCHAR(3) NOT NULL,
    price_amount NUMERIC(19,4) NOT NULL,
    margin_percent NUMERIC(8,4) NOT NULL,
    signal_type VARCHAR(50) NOT NULL,
    decision_type VARCHAR(50) NOT NULL,
    decision_reason VARCHAR(500),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sku_pricing_history_sku_id ON sku_pricing_history(sku_id);
