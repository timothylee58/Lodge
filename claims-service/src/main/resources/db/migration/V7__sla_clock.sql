-- The SLA clock is separate from "time in status": the 5-day decision target is paused while the claim waits on the
-- claimant (PENDING_INFO) and resumes, rather than restarts, when they answer.
ALTER TABLE claim ADD COLUMN sla_started_at TIMESTAMPTZ;
ALTER TABLE claim ADD COLUMN review_seconds_used BIGINT NOT NULL DEFAULT 0;
UPDATE claim SET sla_started_at = status_changed_at;
ALTER TABLE claim ALTER COLUMN sla_started_at SET NOT NULL;
