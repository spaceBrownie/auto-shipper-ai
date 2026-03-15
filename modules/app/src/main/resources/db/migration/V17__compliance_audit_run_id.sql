-- Add run_id to compliance_audit to group records from the same check execution.
-- Without this, getComplianceStatus shows FAILED for re-checked SKUs because
-- old FAILED records from prior runs are still in the audit table.
ALTER TABLE compliance_audit ADD COLUMN run_id UUID NOT NULL DEFAULT gen_random_uuid();
CREATE INDEX idx_compliance_audit_run_id ON compliance_audit(run_id);
