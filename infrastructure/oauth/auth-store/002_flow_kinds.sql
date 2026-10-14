BEGIN;
ALTER TABLE browser_flows DROP CONSTRAINT IF EXISTS browser_flows_kind_check;
ALTER TABLE browser_flows ADD CONSTRAINT browser_flows_kind_check
    CHECK (kind IN ('login', 'registration', 'verification', 'recovery', 'reset', 'settings'));
COMMIT;
