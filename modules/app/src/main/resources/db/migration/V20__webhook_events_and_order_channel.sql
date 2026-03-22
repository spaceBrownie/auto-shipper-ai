-- Webhook event deduplication table
CREATE TABLE webhook_events (
    event_id     VARCHAR(255) PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    channel      VARCHAR(50)  NOT NULL DEFAULT 'shopify',
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_events_processed_at ON webhook_events(processed_at);

-- Add channel metadata columns to orders table
ALTER TABLE orders ADD COLUMN channel VARCHAR(50);
ALTER TABLE orders ADD COLUMN channel_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN channel_order_number VARCHAR(100);

CREATE INDEX idx_orders_channel_order_id ON orders(channel_order_id);
