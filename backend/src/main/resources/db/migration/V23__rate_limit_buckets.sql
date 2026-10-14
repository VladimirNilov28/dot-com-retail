-- Shared token-bucket state for the HTTP rate limiter (see docs/security/rate-limiting.md).
-- One row per (policy, client key). Every check is a single atomic
-- INSERT ... ON CONFLICT DO UPDATE ... RETURNING, so all backend instances see
-- the same buckets. Idle rows are deleted by a scheduled cleanup; an idle row
-- is always already full, so deleting it never changes a decision.
CREATE TABLE rate_limit_buckets (
    bucket_key VARCHAR(200) PRIMARY KEY,
    tokens DOUBLE PRECISION NOT NULL CHECK (tokens >= 0),
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX rate_limit_buckets_updated ON rate_limit_buckets(updated_at);
