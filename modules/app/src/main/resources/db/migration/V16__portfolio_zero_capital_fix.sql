-- Fix experiment table: replace budget fields with source_signal and estimated_margin_per_unit
-- per PM-007 AD-8 (zero-capital model: no budget concept)
ALTER TABLE experiments DROP COLUMN budget_amount;
ALTER TABLE experiments DROP COLUMN budget_currency;
ALTER TABLE experiments ADD COLUMN source_signal TEXT;
ALTER TABLE experiments ADD COLUMN estimated_margin_per_unit NUMERIC(19,4);
ALTER TABLE experiments ADD COLUMN estimated_margin_currency VARCHAR(3);

-- Rename capital_reallocation_log to priority_ranking_log (PM-007 AD-8)
ALTER TABLE capital_reallocation_log RENAME TO priority_ranking_log;
ALTER TABLE priority_ranking_log RENAME COLUMN freed_capital_amount TO score;
ALTER TABLE priority_ranking_log RENAME COLUMN freed_capital_currency TO currency;
ALTER TABLE priority_ranking_log RENAME COLUMN recommended_target_sku_id TO sku_id;
ALTER TABLE priority_ranking_log RENAME COLUMN recommended_at TO ranked_at;
ALTER TABLE priority_ranking_log ADD COLUMN rank INT NOT NULL DEFAULT 0;
