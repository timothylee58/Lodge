-- Emails are matched case-insensitively. Restricting them to printable ASCII guarantees that Java's lower-casing
-- (used for the login-throttle key) and Postgres's lower() (used for lookup) agree, so no alternative Unicode
-- spelling of an address (e.g. dotless i) can reach the same account under a different throttle key.
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_email_ascii CHECK (email ~ '^[!-~]+$');
