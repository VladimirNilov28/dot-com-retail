-- Order lifecycle: PROCESSING state, tracking, status history and customer refund requests.
-- The new enum value is only added here, never used in this migration (PostgreSQL forbids
-- using an enum value in the transaction that created it).
ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'PROCESSING' AFTER 'PAID';

ALTER TABLE orders
    ADD COLUMN carrier varchar(80),
    ADD COLUMN tracking_number varchar(64),
    ADD COLUMN tracking_url varchar(500),
    ADD COLUMN shipped_at timestamptz,
    ADD CONSTRAINT ck_order_tracking_pair CHECK ((carrier IS NULL) = (tracking_number IS NULL)),
    ADD CONSTRAINT ck_order_tracking_url CHECK (tracking_url IS NULL OR tracking_url ~ '^https?://');

CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_status_created ON orders(status, created_at DESC, id DESC);
CREATE INDEX idx_orders_created ON orders(created_at DESC, id DESC);

CREATE TABLE order_status_events (
    id bigserial PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    from_status varchar(16),
    to_status varchar(16) NOT NULL
        CHECK (to_status IN ('PENDING','PAID','PROCESSING','SHIPPING','COMPLETED','CANCELLED')),
    actor_kind varchar(16) NOT NULL
        CHECK (actor_kind IN ('SYSTEM','CUSTOMER','GUEST','STAFF','PAYMENT')),
    note varchar(500),
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_status_events_order ON order_status_events(order_id, occurred_at, id);

-- Best-effort history for existing orders: creation plus the current state.
INSERT INTO order_status_events(order_id, from_status, to_status, actor_kind, note, occurred_at)
SELECT id, NULL, 'PENDING', 'SYSTEM', 'Order placed', created_at FROM orders;
INSERT INTO order_status_events(order_id, from_status, to_status, actor_kind, note, occurred_at)
SELECT id, 'PENDING', status::text, 'SYSTEM', 'Recorded before status history existed', updated_at
FROM orders WHERE status::text <> 'PENDING';

CREATE FUNCTION order_status_events_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_status_events is append-only';
END;
$$;
CREATE TRIGGER trg_order_status_events_append_only BEFORE UPDATE OR DELETE ON order_status_events
    FOR EACH ROW EXECUTE FUNCTION order_status_events_append_only();

CREATE TABLE refund_requests (
    id uuid PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    requested_by varchar(128) NOT NULL,
    reason varchar(1000) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('REQUESTED','APPROVED','REJECTED')),
    decided_by varchar(128),
    decision_note varchar(1000),
    decided_at timestamptz,
    refund_id uuid UNIQUE REFERENCES order_refunds(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_refund_request_decision CHECK (
        (status = 'REQUESTED' AND decided_at IS NULL AND decided_by IS NULL AND refund_id IS NULL)
        OR (status = 'APPROVED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND refund_id IS NOT NULL)
        OR (status = 'REJECTED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND refund_id IS NULL))
);
-- One open request per order; at most one approved (order_refunds is one per order).
CREATE UNIQUE INDEX uq_refund_request_open ON refund_requests(order_id) WHERE status = 'REQUESTED';
CREATE UNIQUE INDEX uq_refund_request_approved ON refund_requests(order_id) WHERE status = 'APPROVED';
CREATE INDEX idx_refund_requests_order ON refund_requests(order_id, created_at);

CREATE FUNCTION protect_refund_request_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.order_id, NEW.requested_by, NEW.reason, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.order_id, OLD.requested_by, OLD.reason, OLD.created_at) THEN
        RAISE EXCEPTION 'Refund request identity is immutable';
    END IF;
    IF OLD.status <> 'REQUESTED' AND ROW(NEW.status, NEW.decided_by, NEW.decision_note, NEW.decided_at, NEW.refund_id)
        IS DISTINCT FROM ROW(OLD.status, OLD.decided_by, OLD.decision_note, OLD.decided_at, OLD.refund_id) THEN
        RAISE EXCEPTION 'Refund request decision is final';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_refund_request_decision BEFORE UPDATE ON refund_requests
    FOR EACH ROW EXECUTE FUNCTION protect_refund_request_decision();
CREATE TRIGGER trg_refund_request_updated_at BEFORE UPDATE ON refund_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Notifications that can legitimately repeat per order (e.g. a rejected then re-submitted
-- refund request) carry a reference; once-per-order kinds keep ref = ''.
ALTER TABLE order_notifications ADD COLUMN ref varchar(64) NOT NULL DEFAULT '';
ALTER TABLE order_notifications DROP CONSTRAINT order_notifications_order_id_kind_key;
ALTER TABLE order_notifications ADD CONSTRAINT uq_order_notifications_kind_ref UNIQUE (order_id, kind, ref);
