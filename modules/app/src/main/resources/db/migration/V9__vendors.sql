CREATE TABLE vendors (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    contact_email VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    sla_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    defect_rate_documented BOOLEAN NOT NULL DEFAULT FALSE,
    scalability_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    fulfillment_times_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    refund_policy_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    deactivated_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE vendor_sku_assignments (
    id UUID PRIMARY KEY,
    vendor_id UUID NOT NULL REFERENCES vendors(id),
    sku_id UUID NOT NULL REFERENCES skus(id),
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE vendor_breach_log (
    id UUID PRIMARY KEY,
    vendor_id UUID NOT NULL REFERENCES vendors(id),
    breach_rate NUMERIC(5,2) NOT NULL,
    threshold NUMERIC(5,2) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE vendor_fulfillment_records (
    id UUID PRIMARY KEY,
    vendor_id UUID NOT NULL REFERENCES vendors(id),
    order_id UUID NOT NULL,
    is_violation BOOLEAN NOT NULL DEFAULT FALSE,
    violation_type VARCHAR(100),
    recorded_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vendor_status ON vendors(status);
CREATE INDEX idx_vendor_sku_vendor_id ON vendor_sku_assignments(vendor_id);
CREATE INDEX idx_vendor_sku_sku_id ON vendor_sku_assignments(sku_id);
CREATE INDEX idx_vendor_breach_vendor_id ON vendor_breach_log(vendor_id);
CREATE INDEX idx_vendor_fulfillment_vendor_id ON vendor_fulfillment_records(vendor_id);
CREATE INDEX idx_vendor_fulfillment_recorded_at ON vendor_fulfillment_records(vendor_id, recorded_at);
