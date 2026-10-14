package store

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// RecordPaymentDeclined durably records one declined provider attempt and its
// payment.declined outbox row in a single transaction. The payment stays
// REQUESTED (retryable on the same provider intent). Replays of the same
// attempt, or a decline observed after the payment settled, are no-ops.
func (s *Store) RecordPaymentDeclined(
	ctx context.Context, paymentID uuid.UUID, attemptID, reason, topic string, payload []byte,
) (bool, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("store: begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	tag, err := tx.Exec(ctx, `
		INSERT INTO payment_declines (payment_id, attempt_id, reason)
		SELECT id, $2, $3 FROM payments WHERE id = $1 AND status = 'REQUESTED'
		ON CONFLICT DO NOTHING
	`, paymentID, attemptID, reason)
	if err != nil {
		return false, fmt.Errorf("store: insert payment decline: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return false, nil
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO payment_outbox (payment_id, topic, payload) VALUES ($1, $2, $3)
	`, paymentID, topic, payload); err != nil {
		return false, fmt.Errorf("store: insert outbox row: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("store: commit tx: %w", err)
	}
	return true, nil
}
