-- FR-027: CJ US Warehouse Filtering
-- Add warehouse country code to supplier product mappings.
-- Nullable for backward compatibility: NULL = legacy mapping (assumed CN origin).
ALTER TABLE supplier_product_mappings
    ADD COLUMN warehouse_country_code VARCHAR(10);
