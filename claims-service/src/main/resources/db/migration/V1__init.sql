CREATE TABLE app_user (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    email       VARCHAR(200) NOT NULL UNIQUE,
    password    VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL CHECK (role IN ('CLAIMANT','OFFICER','MANAGER')),
    market      VARCHAR(2)   NOT NULL
);

CREATE TABLE policy (
    id              UUID PRIMARY KEY,
    policy_number   VARCHAR(30) NOT NULL UNIQUE,
    holder_id       UUID NOT NULL REFERENCES app_user(id),
    type            VARCHAR(10) NOT NULL CHECK (type IN ('MOTOR','PROPERTY')),
    market          VARCHAR(2)  NOT NULL,
    coverage_limit  NUMERIC(14,2) NOT NULL CHECK (coverage_limit > 0),
    currency        VARCHAR(3)  NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
);

CREATE SEQUENCE claim_number_seq START 1000;

CREATE TABLE claim (
    id              UUID PRIMARY KEY,
    claim_number    VARCHAR(20) NOT NULL UNIQUE,
    policy_id       UUID NOT NULL REFERENCES policy(id),
    claimant_id     UUID NOT NULL REFERENCES app_user(id),
    assignee_id     UUID REFERENCES app_user(id),
    type            VARCHAR(10) NOT NULL,
    market          VARCHAR(2)  NOT NULL,
    incident_date   DATE NOT NULL,
    description     TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL,
    reserve_amount  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (reserve_amount >= 0),
    settled_amount  NUMERIC(14,2),
    currency        VARCHAR(3) NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_claim_queue    ON claim (status, assignee_id);
CREATE INDEX idx_claim_claimant ON claim (claimant_id);

CREATE TABLE claim_event (
    id          UUID PRIMARY KEY,
    claim_id    UUID NOT NULL REFERENCES claim(id),
    type        VARCHAR(40) NOT NULL,
    actor_id    UUID NOT NULL REFERENCES app_user(id),
    payload     JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_claim_event_claim ON claim_event (claim_id, created_at);

CREATE TABLE info_request (
    id            UUID PRIMARY KEY,
    claim_id      UUID NOT NULL REFERENCES claim(id),
    question      TEXT NOT NULL,
    response      TEXT,
    requested_by  UUID NOT NULL REFERENCES app_user(id),
    status        VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ANSWERED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at   TIMESTAMPTZ
);

CREATE TABLE outbox_event (
    id            UUID PRIMARY KEY,
    aggregate_id  UUID NOT NULL,
    event_type    VARCHAR(40) NOT NULL,
    topic         VARCHAR(100) NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;
