-- Reserve accounts
CREATE TABLE reserve_accounts (
    id              UUID            PRIMARY KEY,
    balance_amount  NUMERIC(19,4)   NOT NULL,
    balance_currency VARCHAR(3)     NOT NULL,
    target_rate_min NUMERIC(5,2)    NOT NULL DEFAULT 10.00,
    target_rate_max NUMERIC(5,2)    NOT NULL DEFAULT 15.00,
    last_updated_at TIMESTAMP       NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0
);

-- Margin snapshots
CREATE TABLE margin_snapshots (
    id                  UUID            PRIMARY KEY,
    sku_id              UUID            NOT NULL,
    snapshot_date       DATE            NOT NULL,
    gross_margin        NUMERIC(5,2)    NOT NULL,
    net_margin          NUMERIC(5,2)    NOT NULL,
    revenue_amount      NUMERIC(19,4)   NOT NULL,
    revenue_currency    VARCHAR(3)      NOT NULL,
    total_cost_amount   NUMERIC(19,4)   NOT NULL,
    total_cost_currency VARCHAR(3)      NOT NULL,
    refund_rate         NUMERIC(5,2)    NOT NULL,
    chargeback_rate     NUMERIC(5,2)    NOT NULL,
    cac_variance        NUMERIC(5,2)    NOT NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_margin_snapshots_sku_date UNIQUE (sku_id, snapshot_date)
);

CREATE INDEX idx_margin_snapshots_sku_date ON margin_snapshots (sku_id, snapshot_date);

-- Capital rule audit log
CREATE TABLE capital_rule_audit (
    id              UUID            PRIMARY KEY,
    sku_id          UUID            NOT NULL,
    rule            VARCHAR(50)     NOT NULL,
    condition_value VARCHAR(100)    NOT NULL,
    action          VARCHAR(50)     NOT NULL,
    fired_at        TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_capital_rule_audit_sku_fired ON capital_rule_audit (sku_id, fired_at);

-- Capital order records
CREATE TABLE capital_order_records (
    id              UUID            PRIMARY KEY,
    order_id        UUID            NOT NULL UNIQUE,
    sku_id          UUID            NOT NULL,
    total_amount    NUMERIC(19,4)   NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    status          VARCHAR(30)     NOT NULL,
    refunded        BOOLEAN         NOT NULL DEFAULT FALSE,
    chargebacked    BOOLEAN         NOT NULL DEFAULT FALSE,
    recorded_at     TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_capital_order_records_sku_recorded ON capital_order_records (sku_id, recorded_at);
