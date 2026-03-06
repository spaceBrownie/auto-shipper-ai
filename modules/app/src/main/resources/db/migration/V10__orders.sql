CREATE TABLE orders (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    sku_id UUID NOT NULL REFERENCES skus(id),
    vendor_id UUID NOT NULL REFERENCES vendors(id),
    customer_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    tracking_number VARCHAR(255),
    carrier VARCHAR(100),
    estimated_delivery TIMESTAMP,
    last_known_location VARCHAR(500),
    delay_detected BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_orders_idempotency_key ON orders(idempotency_key);
CREATE INDEX idx_orders_sku_id ON orders(sku_id);
CREATE INDEX idx_orders_vendor_id ON orders(vendor_id);
CREATE INDEX idx_orders_status ON orders(status);

CREATE TABLE return_records (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    reason VARCHAR(500) NOT NULL,
    returned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    return_handling_cost_amount NUMERIC(19,4) NOT NULL,
    return_handling_cost_currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_return_records_order_id ON return_records(order_id);

CREATE TABLE customer_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    notification_type VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customer_notifications_order_id ON customer_notifications(order_id);
