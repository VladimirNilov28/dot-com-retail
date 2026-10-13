CREATE TABLE order_notifications (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    kind VARCHAR(50) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(order_id, kind)
);
CREATE INDEX order_notifications_pending ON order_notifications(next_attempt_at)
    WHERE status = 'PENDING';
