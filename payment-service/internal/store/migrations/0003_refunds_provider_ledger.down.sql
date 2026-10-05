DROP TRIGGER provider_ledger_immutable ON provider_ledger;
DROP FUNCTION guard_provider_ledger();
DROP TABLE provider_ledger;
DROP TRIGGER refund_financial_identity ON refunds;
DROP FUNCTION guard_refund_financial_identity();
DROP TRIGGER payment_financial_identity ON payments;
DROP FUNCTION guard_payment_financial_identity();
ALTER TABLE payment_outbox DROP COLUMN refund_id;
DROP TABLE refunds;
ALTER TABLE payments
    DROP COLUMN provider_transaction_id,
    DROP COLUMN unresolved_reason,
    DROP COLUMN unresolved_notified;
