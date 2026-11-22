-- Roster learned from assignment events (reporting has no user data of its own).
CREATE TABLE known_officer (
    officer_id UUID PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    last_seen  TIMESTAMPTZ  NOT NULL
);
