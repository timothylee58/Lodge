-- Messages the consumer could not process. Stored (not republished to a DLT topic) so operators can list,
-- re-drive or discard them through the ops API. (topic, partition, offset) makes recording idempotent.
CREATE TABLE dead_letter (
    id               UUID PRIMARY KEY,
    topic            VARCHAR(200)  NOT NULL,
    kafka_partition  INT           NOT NULL,
    kafka_offset     BIGINT        NOT NULL,
    msg_key          VARCHAR(200),
    payload          TEXT,
    error            VARCHAR(1000) NOT NULL,
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    redrive_attempts INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(1000),
    resolved_at      TIMESTAMPTZ,
    resolution       VARCHAR(10) CHECK (resolution IN ('REDRIVEN', 'DISCARDED')),
    UNIQUE (topic, kafka_partition, kafka_offset)
);
CREATE INDEX idx_dead_letter_open ON dead_letter (received_at) WHERE resolved_at IS NULL;
