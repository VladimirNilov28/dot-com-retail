-- Payment Service's own schema. Owned exclusively by this service - the
-- Spring backend never connects to this database. request_event_id is the
-- durable idempotency key for at-least-once Kafka delivery of
-- payment.requested: a unique constraint here (not an in-memory cache)
-- means a redelivered/duplicate request can never cause a second charge.
CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_event_id uuid NOT NULL UNIQUE,
    order_id bigint NOT NULL,
    user_id bigint NOT NULL,
    amount_cents bigint NOT NULL,
    currency varchar(3) NOT NULL,
    status varchar(16) NOT NULL,
    failure_reason varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_order_id ON payments (order_id);

-- Transactional outbox for payment.succeeded/payment.failed results,
-- mirroring the backend's payment_outbox table/pattern: a row is inserted in
-- the same DB transaction as the payments status update, so the result is
-- never lost to a crash between "processed the charge" and "published to
-- Kafka" (the dual-write problem this milestone must not ignore).
CREATE TABLE payment_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid NOT NULL REFERENCES payments (id),
    topic varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    published boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX idx_payment_outbox_unpublished ON payment_outbox (created_at)
    WHERE published = false;
