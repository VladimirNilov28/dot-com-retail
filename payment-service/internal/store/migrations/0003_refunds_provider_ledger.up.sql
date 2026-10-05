ALTER TABLE payments
    ADD COLUMN provider_transaction_id text,
    ADD COLUMN unresolved_reason text,
    ADD COLUMN unresolved_notified boolean NOT NULL DEFAULT false;

CREATE TABLE refunds (
    id uuid PRIMARY KEY,
    request_event_id uuid NOT NULL UNIQUE,
    original_request_event_id uuid NOT NULL,
    payment_id uuid NOT NULL UNIQUE REFERENCES payments(id),
    order_id bigint NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    currency varchar(3) NOT NULL,
    original_provider_transaction_id text NOT NULL DEFAULT '',
    provider_transaction_id text,
    status varchar(16) NOT NULL CHECK (status IN ('REQUESTED','SUCCEEDED','FAILED')),
    failure_reason text,
    unresolved_reason text,
    unresolved_notified boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (id <> request_event_id)
);

ALTER TABLE payment_outbox ADD COLUMN refund_id uuid REFERENCES refunds(id);

-- The simulator owns this independently committed ledger. These rows are
-- deliberately labelled simulation, never evidence of a real gateway charge.
CREATE TABLE provider_ledger (
    operation varchar(8) NOT NULL CHECK (operation IN ('CHARGE','REFUND')),
    idempotency_key uuid NOT NULL,
    payment_id uuid NOT NULL,
    order_id bigint NOT NULL,
    amount_cents bigint NOT NULL,
    currency varchar(3) NOT NULL,
    original_provider_transaction_id text NOT NULL DEFAULT '',
    approved boolean NOT NULL,
    reason text NOT NULL DEFAULT '',
    provider_transaction_id text NOT NULL UNIQUE,
    simulation boolean NOT NULL DEFAULT true CHECK (simulation),
    historical boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(operation,idempotency_key),
    UNIQUE(operation,payment_id)
);

-- Only authoritative Go terminal payment records are seeded. Pending rows
-- are not assumed charged; .13 is not re-evaluated for historical outcomes.
INSERT INTO provider_ledger
    (operation,idempotency_key,payment_id,order_id,amount_cents,currency,
     approved,reason,provider_transaction_id,historical)
SELECT 'CHARGE',id,id,order_id,amount_cents,currency,
       status='SUCCEEDED',coalesce(failure_reason,''),
       'sim-charge-' || id::text,true
FROM payments WHERE status IN ('SUCCEEDED','FAILED');

-- Financial bindings and terminal outcomes cannot be reassigned, including
-- through accidental direct SQL. Nonterminal metadata remains mutable.
CREATE FUNCTION guard_payment_financial_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.request_event_id,NEW.order_id,NEW.user_id,NEW.amount_cents,NEW.currency)
       IS DISTINCT FROM ROW(OLD.id,OLD.request_event_id,OLD.order_id,OLD.user_id,OLD.amount_cents,OLD.currency)
       OR (OLD.status IN ('SUCCEEDED','FAILED') AND
           ROW(NEW.status,NEW.failure_reason,NEW.provider_transaction_id)
           IS DISTINCT FROM ROW(OLD.status,OLD.failure_reason,OLD.provider_transaction_id)) THEN
        RAISE EXCEPTION 'immutable payment financial identity or terminal outcome';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER payment_financial_identity BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION guard_payment_financial_identity();

CREATE FUNCTION guard_refund_financial_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.request_event_id,NEW.original_request_event_id,NEW.payment_id,NEW.order_id,
           NEW.amount_cents,NEW.currency,NEW.original_provider_transaction_id)
       IS DISTINCT FROM ROW(OLD.id,OLD.request_event_id,OLD.original_request_event_id,OLD.payment_id,OLD.order_id,
           OLD.amount_cents,OLD.currency,OLD.original_provider_transaction_id)
       OR (OLD.status IN ('SUCCEEDED','FAILED') AND
           ROW(NEW.status,NEW.failure_reason,NEW.provider_transaction_id)
           IS DISTINCT FROM ROW(OLD.status,OLD.failure_reason,OLD.provider_transaction_id)) THEN
        RAISE EXCEPTION 'immutable refund financial identity or terminal outcome';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER refund_financial_identity BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION guard_refund_financial_identity();

CREATE FUNCTION guard_provider_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable simulated provider ledger';
END $$;
CREATE TRIGGER provider_ledger_immutable BEFORE UPDATE OR DELETE ON provider_ledger
    FOR EACH ROW EXECUTE FUNCTION guard_provider_ledger();
