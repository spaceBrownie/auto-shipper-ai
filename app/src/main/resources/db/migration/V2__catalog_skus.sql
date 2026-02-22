-- V2: Catalog SKU Lifecycle tables

CREATE TABLE skus (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    current_state VARCHAR(50) NOT NULL DEFAULT 'IDEATION',
    termination_reason VARCHAR(50),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_skus_current_state ON skus(current_state);

CREATE TABLE sku_state_history (
    id BIGSERIAL PRIMARY KEY,
    sku_id UUID NOT NULL REFERENCES skus(id),
    from_state VARCHAR(50) NOT NULL,
    to_state VARCHAR(50) NOT NULL,
    transitioned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sku_state_history_sku_id ON sku_state_history(sku_id);
