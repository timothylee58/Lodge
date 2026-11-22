-- Read model, rebuilt from claims.events. Safe to drop and replay from offset 0.
CREATE TABLE claim_projection (
    claim_id       UUID PRIMARY KEY,
    claim_number   VARCHAR(30)   NOT NULL,
    market         VARCHAR(4)    NOT NULL,
    type           VARCHAR(20)   NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    reserve_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    assignee_id    UUID,
    assignee_name  VARCHAR(120),
    version        BIGINT        NOT NULL,   -- claim version of the last applied event; older events are ignored
    occurred_at    TIMESTAMPTZ   NOT NULL    -- when the last applied event happened (drives "as of")
);
CREATE INDEX idx_projection_open ON claim_projection (market, type) WHERE status NOT IN ('SETTLED', 'REJECTED');
CREATE INDEX idx_projection_assignee ON claim_projection (assignee_id) WHERE assignee_id IS NOT NULL;

-- Exact-duplicate guard (Kafka is at-least-once). Prune rows older than the topic's retention.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
