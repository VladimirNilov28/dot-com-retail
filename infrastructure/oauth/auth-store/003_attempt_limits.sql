-- Requires psql variable runtime_user (the BFF runtime role).
BEGIN;
CREATE TABLE IF NOT EXISTS attempt_limits (
    key_hash char(64) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts > 0)
);
CREATE INDEX IF NOT EXISTS attempt_limits_window_start ON attempt_limits (window_start);
GRANT SELECT, INSERT, UPDATE, DELETE ON attempt_limits TO :"runtime_user";
COMMIT;
