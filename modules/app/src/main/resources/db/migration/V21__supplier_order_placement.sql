-- FR-025: CJ Supplier Order Placement
-- Adds quantity, shipping address, supplier cross-reference columns to orders,
-- and creates the supplier_product_mappings table for SKU-to-CJ-variant resolution.

-- Add quantity to orders (default 1 for existing rows, then drop default)
ALTER TABLE orders ADD COLUMN quantity INT NOT NULL DEFAULT 1;
ALTER TABLE orders ALTER COLUMN quantity DROP DEFAULT;

-- Add shipping address columns (nullable for existing orders)
ALTER TABLE orders ADD COLUMN shipping_customer_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_address VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_address2 VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_city VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_zip VARCHAR(50);
ALTER TABLE orders ADD COLUMN shipping_country VARCHAR(100);
ALTER TABLE orders ADD COLUMN shipping_country_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_phone VARCHAR(50);

-- Supplier cross-reference columns
ALTER TABLE orders ADD COLUMN supplier_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN failure_reason VARCHAR(1000);

-- Supplier product mapping table
CREATE TABLE supplier_product_mappings (
    id UUID PRIMARY KEY,
    sku_id UUID NOT NULL REFERENCES skus(id),
    supplier VARCHAR(50) NOT NULL,
    supplier_variant_id VARCHAR(255) NOT NULL,
    supplier_product_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_supplier_product_mapping_sku_supplier
    ON supplier_product_mappings(sku_id, supplier);

CREATE INDEX idx_supplier_product_mappings_sku_id
    ON supplier_product_mappings(sku_id);
