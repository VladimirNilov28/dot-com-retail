-- Definite card declines are a distinct, NON-terminal payment state: the order
-- stays PENDING (stock stays reserved) and the customer retries on the same
-- order/PaymentIntent. A later success settles normally to PAID.
ALTER TABLE orders DROP CONSTRAINT orders_payment_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_payment_status_check
    CHECK (payment_status IN ('UNKNOWN','PENDING','UNRESOLVED','DECLINED','SUCCEEDED','FAILED'));

-- Latest allow-listed decline reason code (e.g. insufficient_funds); never card data.
ALTER TABLE orders ADD COLUMN payment_decline_reason varchar(32);

-- One row per declined provider attempt (payment.declined). The key makes Kafka
-- redelivery and provider replays idempotent and drives the per-attempt
-- PAYMENT_FAILED notification dedupe.
CREATE TABLE order_payment_declines (
    payment_id uuid NOT NULL,
    attempt_id varchar(64) NOT NULL,
    order_id bigint NOT NULL REFERENCES orders(id),
    reason varchar(32) NOT NULL,
    event_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, attempt_id)
);
CREATE INDEX idx_order_payment_declines_order ON order_payment_declines(order_id);
