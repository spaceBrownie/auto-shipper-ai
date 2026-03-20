CREATE TABLE platform_listings (
    id                   UUID PRIMARY KEY,
    sku_id               UUID NOT NULL REFERENCES skus(id),
    platform             VARCHAR(50)  NOT NULL,
    external_listing_id  VARCHAR(255) NOT NULL,
    external_variant_id  VARCHAR(255),
    current_price_amount NUMERIC(19,4) NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    status               VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_platform_listings_sku_platform
    ON platform_listings(sku_id, platform);

CREATE INDEX idx_platform_listings_sku_id
    ON platform_listings(sku_id);

CREATE INDEX idx_platform_listings_status
    ON platform_listings(status);
