-- FR-025: CJ Supplier Order Placement

-- Add quantity field to orders (required, no default)
ALTER TABLE orders ADD COLUMN quantity INT;
UPDATE orders SET quantity = 1 WHERE quantity IS NULL;
ALTER TABLE orders ALTER COLUMN quantity SET NOT NULL;

-- Add supplier order tracking fields
ALTER TABLE orders ADD COLUMN supplier_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN failure_reason TEXT;

-- Add shipping address fields (embedded on orders)
ALTER TABLE orders ADD COLUMN shipping_customer_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_address_line1 VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_address_line2 VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_city VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_country VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_country_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_zip VARCHAR(20);
ALTER TABLE orders ADD COLUMN shipping_phone VARCHAR(50);

-- Supplier product mapping table
CREATE TABLE supplier_product_mappings (
    id                   UUID PRIMARY KEY,
    sku_id               UUID NOT NULL REFERENCES skus(id),
    supplier_type        VARCHAR(50) NOT NULL,
    supplier_product_id  VARCHAR(255) NOT NULL,
    supplier_variant_id  VARCHAR(255) NOT NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_supplier_product_mappings_sku_supplier
    ON supplier_product_mappings(sku_id, supplier_type);

CREATE INDEX idx_supplier_product_mappings_sku_id
    ON supplier_product_mappings(sku_id);

-- Index for supplier order lookups
CREATE INDEX idx_orders_supplier_order_id ON orders(supplier_order_id);
