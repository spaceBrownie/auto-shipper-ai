-- FR-030 (RAT-53): Shopify Dev Store gate-zero — close inventory-check SKU-UUID gap.
-- Persist Shopify's real inventory_item_id captured at product-create time so
-- ShopifyInventoryCheckAdapter can call the Shopify inventory API with the real id
-- instead of the internal SKU UUID. Nullable + updatable so legacy rows remain valid
-- and the value can be backfilled later for existing listings.
ALTER TABLE platform_listings
    ADD COLUMN shopify_inventory_item_id VARCHAR(64);

CREATE INDEX idx_platform_listings_inventory_item
    ON platform_listings(shopify_inventory_item_id);
