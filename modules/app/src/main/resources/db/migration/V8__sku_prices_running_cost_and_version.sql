ALTER TABLE sku_prices
    ADD COLUMN current_fully_burdened_amount NUMERIC(19,4),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
