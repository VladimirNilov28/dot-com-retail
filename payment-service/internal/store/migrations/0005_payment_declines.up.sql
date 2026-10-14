-- One row per definite provider decline of a still-retryable payment. The
-- composite key (payment, provider attempt) makes webhook replays and the
-- reconciliation loop idempotent: the same declined attempt can only ever
-- enqueue one payment.declined event. reason is a bounded, allow-listed code
-- (never card data or provider free text).
CREATE TABLE payment_declines (
    payment_id uuid NOT NULL REFERENCES payments(id),
    attempt_id varchar(64) NOT NULL,
    reason varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, attempt_id)
);
