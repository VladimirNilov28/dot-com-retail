package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// Persist before the remote call. An aged key without a reference must be
// reconciled by an operator, never submitted again after provider key expiry.
func (s *Store) StripeOperation(ctx context.Context, id uuid.UUID, kind string) (string, time.Time, error) {
	_, err := s.pool.Exec(ctx, `INSERT INTO stripe_operations(id,kind) VALUES($1,$2) ON CONFLICT DO NOTHING`, id, kind)
	if err != nil {
		return "", time.Time{}, err
	}
	var reference string
	var created time.Time
	err = s.pool.QueryRow(ctx, `SELECT coalesce(provider_id,''),created_at FROM stripe_operations WHERE id=$1 AND kind=$2`, id, kind).Scan(&reference, &created)
	return reference, created, err
}

func (s *Store) BindStripeOperation(ctx context.Context, id uuid.UUID, reference string) error {
	tag, err := s.pool.Exec(ctx, `UPDATE stripe_operations SET provider_id=$2
		WHERE id=$1 AND (provider_id IS NULL OR provider_id=$2)`, id, reference)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return ErrConflict
	}
	return nil
}

func (s *Store) PendingStripePayments(ctx context.Context) ([]domainStripePayment, error) {
	rows, err := s.pool.Query(ctx, `SELECT p.id,o.provider_id FROM payments p
		JOIN stripe_operations o ON o.id=p.id AND o.kind='intent'
		WHERE p.status='REQUESTED' AND p.provider='stripe' AND o.provider_id IS NOT NULL
		ORDER BY p.updated_at LIMIT 100`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []domainStripePayment
	for rows.Next() {
		var p domainStripePayment
		if err := rows.Scan(&p.ID, &p.Reference); err != nil {
			return nil, err
		}
		result = append(result, p)
	}
	return result, rows.Err()
}

type domainStripePayment struct {
	ID        uuid.UUID
	Reference string
}

func (s *Store) TouchStripePayment(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `UPDATE payments SET updated_at=now() WHERE id=$1`, id)
	return err
}
