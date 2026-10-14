-- Durable, append-only record of privileged (staff/admin) operations.
-- details holds safe change metadata only: never credentials, tokens or payment data.
CREATE TABLE admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id BIGINT,
    actor_subject VARCHAR(255) NOT NULL,
    actor_roles VARCHAR(255),
    operation VARCHAR(100) NOT NULL,
    target_type VARCHAR(60) NOT NULL,
    target_id VARCHAR(120),
    outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    details JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX admin_audit_log_occurred ON admin_audit_log(occurred_at DESC, id DESC);
CREATE INDEX admin_audit_log_target ON admin_audit_log(target_type, target_id);

CREATE FUNCTION admin_audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_log is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER admin_audit_log_no_update BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION admin_audit_log_append_only();
