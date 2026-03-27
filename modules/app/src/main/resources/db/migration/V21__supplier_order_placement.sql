-- Shipping address columns on orders table
ALTER TABLE orders ADD COLUMN shipping_customer_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_address       VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_city           VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province       VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_country        VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_country_code   VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_zip            VARCHAR(50);
ALTER TABLE orders ADD COLUMN shipping_phone          VARCHAR(50);

-- Supplier order cross-reference
ALTER TABLE orders ADD COLUMN supplier_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN failure_reason    VARCHAR(255);

-- Supplier product mappings table
CREATE TABLE supplier_product_mappings (
    id                  UUID PRIMARY KEY,
    sku_id              UUID         NOT NULL REFERENCES skus(id),
    supplier            VARCHAR(50)  NOT NULL,
    supplier_product_id VARCHAR(255) NOT NULL,
    supplier_variant_id VARCHAR(255) NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_supplier_product_mappings_sku_supplier
    ON supplier_product_mappings(sku_id, supplier);

CREATE INDEX idx_supplier_product_mappings_sku_id
    ON supplier_product_mappings(sku_id);
