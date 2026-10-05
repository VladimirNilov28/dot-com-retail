// Package store is the Payment Service's sole persistence layer (pgx over
// its own Postgres database). No other package issues SQL directly.
package store

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"time"

	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/postgres"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// Store wraps a Postgres connection pool for the Payment Service's own
// schema (payments, payment_outbox).
type Store struct {
	pool *pgxpool.Pool
}

// New connects to the given DSN and returns a ready-to-use Store. Callers
// must call Close when done.
func New(ctx context.Context, databaseURL string) (*Store, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("store: connect: %w", err)
	}
	return &Store{pool: pool}, nil
}

// Close releases the underlying connection pool.
func (s *Store) Close() {
	s.pool.Close()
}

// Ping verifies the database is reachable, for readiness checks.
func (s *Store) Ping(ctx context.Context) error {
	return s.pool.Ping(ctx)
}

// Migrate applies all pending embedded migrations. Safe to call on every
// startup (including Payment Service restarts) - golang-migrate tracks the
// applied version in its own schema_migrations table and is a no-op if
// nothing changed.
func (s *Store) Migrate(databaseURL string) error {
	sourceDriver, err := iofs.New(migrationsFS, "migrations")
	if err != nil {
		return fmt.Errorf("store: load embedded migrations: %w", err)
	}
	m, err := migrate.NewWithSourceInstance("iofs", sourceDriver, databaseURL)
	if err != nil {
		return fmt.Errorf("store: init migrator: %w", err)
	}
	defer func() { _, _ = m.Close() }()

	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("store: apply migrations: %w", err)
	}
	return nil
}

// SaveRequestedResult reports whether SaveRequested performed a fresh insert
// (Inserted=true) or found an existing row for the same RequestEventID
// (Inserted=false, Payment is the pre-existing row) - the durable
// idempotency check for at-least-once payment.requested delivery.
type SaveRequestedResult struct {
	Inserted bool
	Payment  domain.Payment
}

// SaveRequested idempotently records a new payment request. A duplicate
// RequestEventID never creates a second row. Terminal payments need no charge
// call; unresolved payments resume with the existing ID and immutable fields.
// Preventing a second external charge requires provider-side idempotency.
func (s *Store) SaveRequested(ctx context.Context, p domain.Payment) (SaveRequestedResult, error) {
	row := s.pool.QueryRow(ctx, `
		INSERT INTO payments (id, request_event_id, order_id, user_id, amount_cents, currency, status)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (request_event_id) DO NOTHING
		RETURNING id, created_at, updated_at
	`, p.ID, p.RequestEventID, p.OrderID, p.UserID, p.AmountCents, p.Currency, p.Status)

	var id uuid.UUID
	var createdAt, updatedAt time.Time
	err := row.Scan(&id, &createdAt, &updatedAt)
	if err == nil {
		p.ID = id
		p.CreatedAt = createdAt
		p.UpdatedAt = updatedAt
		return SaveRequestedResult{Inserted: true, Payment: p}, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return SaveRequestedResult{}, fmt.Errorf("store: insert payment: %w", err)
	}

	existing, err := s.GetByRequestEventID(ctx, p.RequestEventID)
	if err != nil {
		return SaveRequestedResult{}, fmt.Errorf("store: load existing payment after conflict: %w", err)
	}
	return SaveRequestedResult{Inserted: false, Payment: existing}, nil
}

// GetByRequestEventID looks up a payment by its idempotency key.
func (s *Store) GetByRequestEventID(ctx context.Context, requestEventID uuid.UUID) (domain.Payment, error) {
	return s.scanOne(ctx, `
		SELECT id, request_event_id, order_id, user_id, amount_cents, currency, status,
		       coalesce(failure_reason, ''), created_at, updated_at
		FROM payments WHERE request_event_id = $1
	`, requestEventID)
}

// GetByID looks up a payment by its own id.
func (s *Store) GetByID(ctx context.Context, id uuid.UUID) (domain.Payment, error) {
	return s.scanOne(ctx, `
		SELECT id, request_event_id, order_id, user_id, amount_cents, currency, status,
		       coalesce(failure_reason, ''), created_at, updated_at
		FROM payments WHERE id = $1
	`, id)
}

func (s *Store) scanOne(ctx context.Context, query string, arg any) (domain.Payment, error) {
	var p domain.Payment
	err := s.pool.QueryRow(ctx, query, arg).Scan(
		&p.ID, &p.RequestEventID, &p.OrderID, &p.UserID, &p.AmountCents, &p.Currency, &p.Status,
		&p.FailureReason, &p.CreatedAt, &p.UpdatedAt,
	)
	if err != nil {
		return domain.Payment{}, err
	}
	return p, nil
}

// OutboxRow is one unpublished (or, once fetched, about-to-be-published)
// payment_outbox row.
type OutboxRow struct {
	ID      uuid.UUID
	Topic   string
	Payload []byte
}

// RecordResultAndEnqueue updates a payment's terminal status and writes the
// corresponding result-event outbox row in the *same* database transaction -
// the transactional outbox pattern, avoiding the dual-write hazard between
// "processed the charge" and "published to Kafka". Nothing here talks to
// Kafka directly; a separate poller (see kafka.OutboxPublisher) does that
// outside any domain transaction.
func (s *Store) RecordResultAndEnqueue(
	ctx context.Context,
	paymentID uuid.UUID,
	status domain.Status,
	failureReason string,
	topic string,
	payload []byte,
) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("store: begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	tag, err := tx.Exec(ctx, `
		UPDATE payments
		SET status = $1, failure_reason = $2, updated_at = now()
		WHERE id = $3 AND status = $4
	`, status, nullIfEmpty(failureReason), paymentID, domain.StatusRequested)
	if err != nil {
		return fmt.Errorf("store: update payment status: %w", err)
	}
	if tag.RowsAffected() == 0 {
		// Already terminal (duplicate result processing, e.g. after a crash
		// mid-outbox-publish and reprocessing on restart) - safe no-op, the
		// outbox row was already written the first time this ran.
		return nil
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO payment_outbox (payment_id, topic, payload)
		VALUES ($1, $2, $3)
	`, paymentID, topic, payload); err != nil {
		return fmt.Errorf("store: insert outbox row: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("store: commit tx: %w", err)
	}
	return nil
}

// FetchUnpublished returns up to limit unpublished outbox rows, oldest first.
func (s *Store) FetchUnpublished(ctx context.Context, limit int) ([]OutboxRow, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, topic, payload
		FROM payment_outbox
		WHERE published = false
		ORDER BY created_at ASC
		LIMIT $1
	`, limit)
	if err != nil {
		return nil, fmt.Errorf("store: fetch unpublished outbox rows: %w", err)
	}
	defer rows.Close()

	var out []OutboxRow
	for rows.Next() {
		var row OutboxRow
		if err := rows.Scan(&row.ID, &row.Topic, &row.Payload); err != nil {
			return nil, fmt.Errorf("store: scan outbox row: %w", err)
		}
		out = append(out, row)
	}
	return out, rows.Err()
}

// MarkPublished marks an outbox row as published so it is never resent by
// the poller.
func (s *Store) MarkPublished(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE payment_outbox SET published = true, published_at = now() WHERE id = $1
	`, id)
	if err != nil {
		return fmt.Errorf("store: mark outbox row published: %w", err)
	}
	return nil
}

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}
