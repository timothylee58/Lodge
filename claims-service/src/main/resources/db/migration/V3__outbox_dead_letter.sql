-- Poison-row handling: a row that can never be published must not block every event behind it.
ALTER TABLE outbox_event
    ADD COLUMN attempts   INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error VARCHAR(500),
    ADD COLUMN dead_at    TIMESTAMPTZ;

DROP INDEX idx_outbox_unpublished;
CREATE INDEX idx_outbox_pending ON outbox_event (created_at) WHERE published_at IS NULL AND dead_at IS NULL;
-- Ops query: SELECT id, event_type, attempts, last_error FROM outbox_event WHERE dead_at IS NOT NULL;
