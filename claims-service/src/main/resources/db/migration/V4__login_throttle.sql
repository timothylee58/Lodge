-- Keyed by normalised email string, NOT a FK to app_user: unknown emails are throttled identically,
-- so lockout behaviour cannot be used to discover which accounts exist.
CREATE TABLE login_throttle (
    email           VARCHAR(200) PRIMARY KEY,
    failed_attempts INT         NOT NULL,
    locked_until    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL
);
