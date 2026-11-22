-- Review finding: V6 backfilled status_changed_at from updated_at, which also moves on reserve changes, and V7
-- derived the SLA clock from it. The timeline (claim_event) records every status change exactly, so recompute from it.
-- Idempotent: a full recomputation, safe to run again (the regression test does). Claims without events keep values.

-- 1. When the claim entered its current status = its latest status-changing event.
WITH last_change AS (
  SELECT DISTINCT ON (claim_id) claim_id, created_at
  FROM claim_event
  WHERE type IN ('CLAIM_SUBMITTED','CLAIM_ASSIGNED','INFO_REQUESTED','INFO_PROVIDED',
                 'CLAIM_APPROVED','CLAIM_REJECTED','CLAIM_SETTLED')
  ORDER BY claim_id, created_at DESC
)
UPDATE claim c SET status_changed_at = lc.created_at
FROM last_change lc WHERE lc.claim_id = c.id;

-- 2. Review time already banked = sum of completed review stretches, each running from a review start
--    (CLAIM_ASSIGNED or INFO_PROVIDED) to the INFO_REQUESTED that ended it. Same rule as Claim.transitionTo.
WITH review_events AS (
  SELECT claim_id, type, created_at,
         LAG(created_at) OVER (PARTITION BY claim_id ORDER BY created_at) AS prev_at
  FROM claim_event
  WHERE type IN ('CLAIM_ASSIGNED','INFO_REQUESTED','INFO_PROVIDED')
), banked AS (
  SELECT claim_id, COALESCE(SUM(EXTRACT(EPOCH FROM created_at - prev_at)), 0)::bigint AS secs
  FROM review_events WHERE type = 'INFO_REQUESTED' AND prev_at IS NOT NULL
  GROUP BY claim_id
)
UPDATE claim c SET review_seconds_used = COALESCE(b.secs, 0)
FROM claim c2 LEFT JOIN banked b ON b.claim_id = c2.id
WHERE c.id = c2.id;

-- 3. SLA clock: a resumed review starts "banked seconds" before the status change; everything else at the change.
UPDATE claim SET sla_started_at = CASE
  WHEN status = 'UNDER_REVIEW' THEN status_changed_at - make_interval(secs => review_seconds_used)
  ELSE status_changed_at END;
