ALTER TABLE orders
    ADD COLUMN payment_status varchar(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (payment_status IN ('UNKNOWN','PENDING','UNRESOLVED','SUCCEEDED','FAILED')),
    ADD COLUMN payment_id uuid UNIQUE,
    ADD COLUMN provider_transaction_id varchar(255),
    ADD COLUMN inventory_released_at timestamptz,
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancellation_source varchar(32);

UPDATE orders SET inventory_released_at=updated_at, cancelled_at=updated_at,
    cancellation_source=CASE WHEN cancellation_reason LIKE 'PAYMENT_FAILED:%'
        THEN 'PAYMENT_FAILED' ELSE 'STAFF_REQUESTED' END
WHERE status='CANCELLED';

UPDATE orders o SET payment_status=CASE r.result_status WHEN 'PAID' THEN 'SUCCEEDED' ELSE 'FAILED' END,
    payment_id=r.payment_id
FROM payment_result_receipts r WHERE r.order_id=o.id
    AND (SELECT count(*) FROM payment_result_receipts WHERE order_id=o.id)=1;

UPDATE orders o SET payment_status='PENDING'
WHERE o.status='PENDING' AND o.payment_status='UNKNOWN'
    AND (SELECT count(*) FROM payment_outbox p WHERE p.order_id=o.id AND p.event_type='payment.requested')=1;

CREATE TABLE order_cancellation_requests (
    request_id uuid PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    actor varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE order_refunds (
    id uuid PRIMARY KEY,
    request_event_id uuid NOT NULL UNIQUE,
    payment_request_event_id uuid NOT NULL UNIQUE REFERENCES payment_result_receipts(request_event_id),
    order_id bigint NOT NULL UNIQUE REFERENCES orders(id),
    payment_id uuid NOT NULL UNIQUE,
    amount decimal(10,2) NOT NULL CHECK (amount>=0),
    currency varchar(3) NOT NULL,
    provider_transaction_id varchar(255),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','UNRESOLVED','SUCCEEDED','FAILED')),
    result_event_id uuid UNIQUE,
    refund_transaction_id varchar(255),
    failure_reason varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO order_refunds
    (id,request_event_id,payment_request_event_id,order_id,payment_id,amount,currency,status)
SELECT gen_random_uuid(),gen_random_uuid(),r.request_event_id,o.id,r.payment_id,
    (p.payload->>'amount')::decimal(10,2),p.payload->>'currency','PENDING'
FROM orders o JOIN payment_result_receipts r ON r.order_id=o.id
JOIN payment_outbox p ON p.id=r.outbox_event_id
WHERE o.status='CANCELLED' AND o.payment_status='SUCCEEDED'
    AND (SELECT count(*) FROM payment_result_receipts WHERE order_id=o.id)=1;

INSERT INTO payment_outbox(order_id,event_type,payload)
SELECT order_id,'refund.requested',jsonb_build_object(
    'eventId',request_event_id,'refundId',id,'requestEventId',payment_request_event_id,
    'orderId',order_id,'paymentId',payment_id,'amount',amount,'currency',currency,
    'providerTransactionId',provider_transaction_id,'occurredAt',created_at)
FROM order_refunds;

CREATE FUNCTION protect_refund_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.request_event_id,NEW.payment_request_event_id,NEW.order_id,
        NEW.payment_id,NEW.amount,NEW.currency,NEW.provider_transaction_id)
        IS DISTINCT FROM ROW(OLD.id,OLD.request_event_id,OLD.payment_request_event_id,OLD.order_id,
        OLD.payment_id,OLD.amount,OLD.currency,OLD.provider_transaction_id) THEN
        RAISE EXCEPTION 'Refund financial identity is immutable';
    END IF;
    IF OLD.status IN ('SUCCEEDED','FAILED') AND ROW(NEW.status,NEW.result_event_id,
        NEW.refund_transaction_id,NEW.failure_reason)
        IS DISTINCT FROM ROW(OLD.status,OLD.result_event_id,OLD.refund_transaction_id,OLD.failure_reason) THEN
        RAISE EXCEPTION 'Refund terminal outcome is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_refund_identity BEFORE UPDATE ON order_refunds
    FOR EACH ROW EXECUTE FUNCTION protect_refund_identity();
CREATE TRIGGER trg_refund_updated_at BEFORE UPDATE ON order_refunds
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE FUNCTION protect_order_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.inventory_released_at IS NOT NULL AND NEW.inventory_released_at IS DISTINCT FROM OLD.inventory_released_at THEN
        RAISE EXCEPTION 'Inventory release marker is immutable';
    END IF;
    IF OLD.payment_id IS NOT NULL AND NEW.payment_id IS DISTINCT FROM OLD.payment_id THEN
        RAISE EXCEPTION 'Original payment identity is immutable';
    END IF;
    IF OLD.payment_status IN ('SUCCEEDED','FAILED') AND
        ROW(NEW.payment_status,NEW.provider_transaction_id) IS DISTINCT FROM ROW(OLD.payment_status,OLD.provider_transaction_id) THEN
        RAISE EXCEPTION 'Payment terminal outcome is immutable';
    END IF;
    IF OLD.status='CANCELLED' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'Cancelled orders cannot be resurrected';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_order_recovery BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION protect_order_recovery();
