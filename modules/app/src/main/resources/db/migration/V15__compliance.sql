-- Compliance audit records
CREATE TABLE compliance_audit (
    id              UUID            PRIMARY KEY,
    sku_id          UUID            NOT NULL,
    check_type      VARCHAR(30)     NOT NULL,
    result          VARCHAR(10)     NOT NULL,
    failure_reason  VARCHAR(50),
    detail          TEXT,
    checked_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_compliance_audit_sku ON compliance_audit (sku_id, checked_at);
