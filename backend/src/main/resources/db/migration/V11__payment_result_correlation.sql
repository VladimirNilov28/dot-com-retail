CREATE TABLE payment_result_receipts (
    request_event_id UUID PRIMARY KEY,
    outbox_event_id UUID NOT NULL UNIQUE REFERENCES payment_outbox(id) ON DELETE CASCADE,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    payment_id UUID NOT NULL UNIQUE,
    result_event_id UUID NOT NULL UNIQUE,
    result_status VARCHAR(16) NOT NULL CHECK (result_status IN ('PAID', 'CANCELLED'))
);
