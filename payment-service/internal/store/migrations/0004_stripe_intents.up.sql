ALTER TABLE payments ADD COLUMN provider TEXT NOT NULL DEFAULT 'simulated'
    CHECK (provider IN ('simulated', 'stripe'));

CREATE TABLE stripe_operations (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('intent', 'refund')),
    provider_id TEXT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
