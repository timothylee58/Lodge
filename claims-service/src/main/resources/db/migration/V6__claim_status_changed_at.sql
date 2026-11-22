-- When the claim entered its current status: drives "waiting for" ages and SLA targets per status.
ALTER TABLE claim ADD COLUMN status_changed_at TIMESTAMPTZ;
UPDATE claim SET status_changed_at = updated_at;
ALTER TABLE claim ALTER COLUMN status_changed_at SET NOT NULL;
CREATE INDEX idx_claim_status_waiting ON claim (status, status_changed_at);
