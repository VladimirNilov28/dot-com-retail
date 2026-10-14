-- Distinguishes an order cancelled automatically because payment failed
-- from one cancelled manually (e.g. by an ORDER_MANAGER/ADMIN via
-- updateOrderStatus). NULL for every other status/transition.
ALTER TABLE orders
    ADD COLUMN cancellation_reason varchar(255);

-- Transactional outbox for events published to the Payment Service. A row is
-- inserted in the same DB transaction as the domain change (e.g. order
-- creation) that requires it, so the write to `orders`/`order_items` and the
-- write to `payment_outbox` either both commit or both roll back - no
-- separate Kafka write inside that transaction. A background poller later
-- reads unpublished rows, publishes them to Kafka, and marks them published.
CREATE TABLE payment_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid (),
    order_id bigint NOT NULL,
    event_type varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    published boolean NOT NULL DEFAULT FALSE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    published_at timestamptz,
    CONSTRAINT fk_payment_outbox_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

-- Poller scans for unpublished rows; keep this fast regardless of table size.
CREATE INDEX idx_payment_outbox_unpublished ON payment_outbox (created_at)
    WHERE published = FALSE;
