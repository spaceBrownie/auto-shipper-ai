-- Enforce one verified cost envelope per SKU at the database level.
-- Multiple rows for the same sku_id would cause IncorrectResultSizeDataAccessException
-- when StressTestService calls CostEnvelopeRepository.findBySkuId().
ALTER TABLE sku_cost_envelopes
    ADD CONSTRAINT uq_cost_envelopes_sku_id UNIQUE (sku_id);
