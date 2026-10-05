package store

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
)

func (s *Store) SaveRefundRequested(ctx context.Context, r domain.Refund) (domain.Refund, error) {
	if !validFinancialParameters(r.ID, r.OrderID, r.AmountCents, r.Currency) ||
		r.RequestEventID == uuid.Nil || r.OriginalRequestEventID == uuid.Nil ||
		r.PaymentID == uuid.Nil || r.ID == r.RequestEventID || r.ID == r.PaymentID ||
		r.RequestEventID == r.OriginalRequestEventID || r.Status != domain.StatusRequested {
		return domain.Refund{}, ErrConflict
	}
	p, err := s.GetByID(ctx, r.PaymentID)
	if err != nil {
		return domain.Refund{}, err
	}
	if p.Status != domain.StatusSucceeded || p.RequestEventID != r.OriginalRequestEventID ||
		p.OrderID != r.OrderID || p.AmountCents != r.AmountCents || p.Currency != r.Currency {
		return domain.Refund{}, ErrConflict
	}
	if r.OriginalProviderTransactionID != "" {
		reference := p.ProviderTransactionID
		if reference == "" {
			if err := s.pool.QueryRow(ctx, `SELECT provider_transaction_id FROM provider_ledger
				WHERE operation='CHARGE' AND idempotency_key=$1 AND approved`, p.ID).Scan(&reference); err != nil {
				return domain.Refund{}, err
			}
		}
		if reference != r.OriginalProviderTransactionID {
			return domain.Refund{}, ErrConflict
		}
	}
	_, err = s.pool.Exec(ctx, `INSERT INTO refunds
		(id,request_event_id,original_request_event_id,payment_id,order_id,amount_cents,currency,
		 original_provider_transaction_id,status)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT DO NOTHING`,
		r.ID, r.RequestEventID, r.OriginalRequestEventID, r.PaymentID, r.OrderID,
		r.AmountCents, r.Currency, r.OriginalProviderTransactionID, r.Status)
	if err != nil {
		return domain.Refund{}, fmt.Errorf("store: insert refund: %w", err)
	}
	existing, err := s.GetRefundByID(ctx, r.ID)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Refund{}, ErrConflict
	}
	if err != nil {
		return domain.Refund{}, err
	}
	if existing.RequestEventID != r.RequestEventID || existing.OriginalRequestEventID != r.OriginalRequestEventID ||
		existing.PaymentID != r.PaymentID || existing.OrderID != r.OrderID || existing.AmountCents != r.AmountCents ||
		existing.Currency != r.Currency || existing.OriginalProviderTransactionID != r.OriginalProviderTransactionID {
		return domain.Refund{}, ErrConflict
	}
	return existing, nil
}

func (s *Store) GetRefundByID(ctx context.Context, id uuid.UUID) (domain.Refund, error) {
	var r domain.Refund
	err := s.pool.QueryRow(ctx, `SELECT id,request_event_id,original_request_event_id,payment_id,
		order_id,amount_cents,currency,original_provider_transaction_id,
		coalesce(provider_transaction_id,''),status,coalesce(failure_reason,'') FROM refunds WHERE id=$1`, id).
		Scan(&r.ID, &r.RequestEventID, &r.OriginalRequestEventID, &r.PaymentID, &r.OrderID,
			&r.AmountCents, &r.Currency, &r.OriginalProviderTransactionID, &r.ProviderTransactionID, &r.Status, &r.FailureReason)
	return r, err
}

func (s *Store) RecordRefundResultAndEnqueue(
	ctx context.Context, refundID uuid.UUID, status domain.Status, reason, reference, topic string, payload []byte,
) error {
	if (status != domain.StatusSucceeded && status != domain.StatusFailed) ||
		(status == domain.StatusSucceeded && topic != "refund.succeeded") ||
		(status == domain.StatusFailed && topic != "refund.failed") {
		return ErrConflict
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var previous domain.Status
	var previousReason, previousReference string
	var paymentID uuid.UUID
	err = tx.QueryRow(ctx, `SELECT status,coalesce(failure_reason,''),coalesce(provider_transaction_id,''),payment_id
		FROM refunds WHERE id=$1 FOR UPDATE`, refundID).
		Scan(&previous, &previousReason, &previousReference, &paymentID)
	if err != nil {
		return err
	}
	if previous != domain.StatusRequested {
		if previous != status || previousReason != reason || previousReference != reference {
			return ErrConflict
		}
		return nil
	}
	if _, err := tx.Exec(ctx, `UPDATE refunds SET status=$2,failure_reason=$3,provider_transaction_id=$4,
		unresolved_reason=NULL,updated_at=now() WHERE id=$1`, refundID, status, nullIfEmpty(reason), nullIfEmpty(reference)); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO payment_outbox(payment_id,refund_id,topic,payload)
		VALUES($1,$2,$3,$4)`, paymentID, refundID, topic, payload); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// Progress uses the same outbox transaction, once per unresolved phase.
// REQUESTED stays nonterminal; a late progress attempt cannot downgrade it.
func (s *Store) RecordPaymentUnresolved(ctx context.Context, paymentID uuid.UUID, reason string, payload []byte) error {
	return s.recordUnresolved(ctx, paymentID, false, reason, payload)
}

func (s *Store) RecordRefundUnresolved(ctx context.Context, refundID uuid.UUID, reason string, payload []byte) error {
	return s.recordUnresolved(ctx, refundID, true, reason, payload)
}

func (s *Store) recordUnresolved(ctx context.Context, id uuid.UUID, refund bool, reason string, payload []byte) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	query := `UPDATE payments SET unresolved_reason=$2,unresolved_notified=true,updated_at=now()
		WHERE id=$1 AND status='REQUESTED' AND NOT unresolved_notified RETURNING id`
	topic := "payment.unresolved"
	if refund {
		query = `UPDATE refunds SET unresolved_reason=$2,unresolved_notified=true,updated_at=now()
			WHERE id=$1 AND status='REQUESTED' AND NOT unresolved_notified RETURNING payment_id`
		topic = "refund.unresolved"
	}
	var paymentID uuid.UUID
	err = tx.QueryRow(ctx, query, id, reason).Scan(&paymentID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	var refundID any
	if refund {
		refundID = id
	}
	if _, err := tx.Exec(ctx, `INSERT INTO payment_outbox(payment_id,refund_id,topic,payload)
		VALUES($1,$2,$3,$4)`, paymentID, refundID, topic, payload); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
