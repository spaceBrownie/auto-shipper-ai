-- FR-016: Demand Scan Job tables
-- Enable pg_trgm extension for trigram similarity matching
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Tracks each scan execution for idempotency and status display
CREATE TABLE demand_scan_runs (
    id            UUID PRIMARY KEY,
    started_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMP,
    status        VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    sources_queried   INT NOT NULL DEFAULT 0,
    candidates_found  INT NOT NULL DEFAULT 0,
    experiments_created INT NOT NULL DEFAULT 0,
    rejections        INT NOT NULL DEFAULT 0
);

-- Persists scored candidates for frontend display and dedup
CREATE TABLE demand_candidates (
    id                      UUID PRIMARY KEY,
    scan_run_id             UUID NOT NULL REFERENCES demand_scan_runs(id),
    product_name            VARCHAR(500) NOT NULL,
    category                VARCHAR(255) NOT NULL,
    description             TEXT,
    source_type             VARCHAR(50) NOT NULL,
    supplier_unit_cost      NUMERIC(19,4),
    supplier_cost_currency  VARCHAR(3),
    estimated_selling_price NUMERIC(19,4),
    selling_price_currency  VARCHAR(3),
    demand_score            NUMERIC(5,4) NOT NULL,
    margin_potential_score  NUMERIC(5,4) NOT NULL,
    competition_score       NUMERIC(5,4) NOT NULL,
    composite_score         NUMERIC(5,4) NOT NULL,
    passed                  BOOLEAN NOT NULL,
    demand_signals          JSONB,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Structured rejection log
CREATE TABLE candidate_rejections (
    id                      UUID PRIMARY KEY,
    scan_run_id             UUID NOT NULL REFERENCES demand_scan_runs(id),
    product_name            VARCHAR(500) NOT NULL,
    category                VARCHAR(255) NOT NULL,
    source_type             VARCHAR(50) NOT NULL,
    rejection_reason        VARCHAR(255) NOT NULL,
    demand_score            NUMERIC(5,4),
    margin_potential_score  NUMERIC(5,4),
    competition_score       NUMERIC(5,4),
    composite_score         NUMERIC(5,4),
    metadata                JSONB,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

-- GIN trigram index for similarity queries
CREATE INDEX idx_demand_candidates_name_trgm ON demand_candidates USING gin (product_name gin_trgm_ops);

-- FK and lookup indexes
CREATE INDEX idx_demand_candidates_scan_run_id ON demand_candidates(scan_run_id);
CREATE INDEX idx_candidate_rejections_scan_run_id ON candidate_rejections(scan_run_id);
