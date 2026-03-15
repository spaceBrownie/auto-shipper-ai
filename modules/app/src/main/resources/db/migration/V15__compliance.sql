CREATE TABLE compliance_audit (
    id          UUID PRIMARY KEY,
    sku_id      UUID        NOT NULL,
    check_type  VARCHAR(50) NOT NULL,
    result      VARCHAR(20) NOT NULL,
    reason      VARCHAR(100),
    detail      TEXT,
    checked_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_compliance_audit_sku_id ON compliance_audit(sku_id);
CREATE INDEX idx_compliance_audit_checked_at ON compliance_audit(checked_at);
