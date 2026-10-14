BEGIN;

CREATE TABLE IF NOT EXISTS browser_sessions (
    id_hash char(64) PRIMARY KEY,
    sealed text NOT NULL,
    authenticated boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL,
    idle_until timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_pending boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS oauth_transactions (
    state_hash char(64) PRIMARY KEY,
    browser_hash char(64) NOT NULL REFERENCES browser_sessions(id_hash) ON DELETE CASCADE,
    sealed text NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX IF NOT EXISTS oauth_transaction_expiry ON oauth_transactions(expires_at);

CREATE TABLE IF NOT EXISTS browser_flows (
    flow_id uuid PRIMARY KEY,
    browser_hash char(64) NOT NULL REFERENCES browser_sessions(id_hash) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('login', 'registration', 'verification')),
    expires_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS captcha_challenges (
    nonce text PRIMARY KEY,
    flow_id uuid NOT NULL,
    challenge jsonb NOT NULL,
    expires_at timestamptz NOT NULL,
    used_trigger uuid,
    payload_digest char(64),
    reservation jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS captcha_flow_issuance ON captcha_challenges(flow_id, created_at);
CREATE INDEX IF NOT EXISTS captcha_expiry ON captcha_challenges(expires_at);

COMMIT;
