-- Experiments
CREATE TABLE experiments (
    id                      UUID            PRIMARY KEY,
    name                    VARCHAR(255)    NOT NULL,
    hypothesis_description  TEXT            NOT NULL,
    budget_amount           NUMERIC(19,4)   NOT NULL,
    budget_currency         VARCHAR(3)      NOT NULL,
    validation_window_days  INT             NOT NULL,
    status                  VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    launched_sku_id         UUID,
    created_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_experiments_status ON experiments (status);

-- Kill recommendations
CREATE TABLE kill_recommendations (
    id              UUID            PRIMARY KEY,
    sku_id          UUID            NOT NULL,
    days_negative   INT             NOT NULL,
    avg_net_margin  NUMERIC(5,2)    NOT NULL,
    detected_at     TIMESTAMP       NOT NULL DEFAULT NOW(),
    confirmed_at    TIMESTAMP
);

CREATE INDEX idx_kill_recommendations_sku ON kill_recommendations (sku_id);

-- Capital reallocation log (advisory — no fund transfers in Phase 1)
CREATE TABLE capital_reallocation_log (
    id                          UUID            PRIMARY KEY,
    freed_capital_amount        NUMERIC(19,4)   NOT NULL,
    freed_capital_currency      VARCHAR(3)      NOT NULL,
    recommended_target_sku_id   UUID            NOT NULL,
    recommended_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Scaling flags
CREATE TABLE scaling_flags (
    id          UUID        PRIMARY KEY,
    sku_id      UUID        NOT NULL,
    flagged_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMP
);

CREATE INDEX idx_scaling_flags_sku ON scaling_flags (sku_id);

-- Refund alerts
CREATE TABLE refund_alerts (
    id              UUID            PRIMARY KEY,
    pattern_type    VARCHAR(50)     NOT NULL,
    affected_skus   TEXT            NOT NULL,
    avg_refund_rate NUMERIC(5,2)    NOT NULL,
    detected_at     TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Discovery blacklist
CREATE TABLE discovery_blacklist (
    id          UUID            PRIMARY KEY,
    keyword     VARCHAR(255)    NOT NULL UNIQUE,
    reason      TEXT            NOT NULL,
    added_at    TIMESTAMP       NOT NULL DEFAULT NOW()
);
