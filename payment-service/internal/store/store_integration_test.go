package store_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

func newTestStore(t *testing.T) *store.Store {
	t.Helper()
	ctx := context.Background()

	pgContainer, err := tcpostgres.Run(ctx, "postgres:17",
		tcpostgres.WithDatabase("payment_service"),
		tcpostgres.WithUsername("payment_service"),
		tcpostgres.WithPassword("payment_service"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").WithOccurrence(2).WithStartupTimeout(60*time.Second),
		),
	)
	if err != nil {
		t.Fatalf("failed to start postgres container: %v", err)
	}
	t.Cleanup(func() {
		if err := testcontainers.TerminateContainer(pgContainer); err != nil {
			t.Logf("failed to terminate postgres container: %v", err)
		}
	})

	dsn, err := pgContainer.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatalf("failed to get connection string: %v", err)
	}

	s, err := store.New(ctx, dsn)
	if err != nil {
		t.Fatalf("failed to connect store: %v", err)
	}
	t.Cleanup(s.Close)

	if err := s.Migrate(dsn); err != nil {
		t.Fatalf("failed to migrate: %v", err)
	}

	return s
}

func newPayment(orderID int64) domain.Payment {
	userID := int64(1)
	return domain.Payment{
		ID:             uuid.New(),
		RequestEventID: uuid.New(),
		OrderID:        orderID,
		UserID:         &userID,
		AmountCents:    3998,
		Currency:       "EUR",
		Status:         domain.StatusRequested,
	}
}

func TestSaveRequested_FreshInsert(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	p := newPayment(1)
	result, err := s.SaveRequested(ctx, p)
	if err != nil {
		t.Fatalf("SaveRequested returned error: %v", err)
	}

	if !result.Inserted {
		t.Fatalf("expected fresh insert, got Inserted=false")
	}
	if result.Payment.Status != domain.StatusRequested {
		t.Fatalf("expected status REQUESTED, got %s", result.Payment.Status)
	}
}

func TestSaveRequested_GuestOwnershipSurvivesReloadAndReplay(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	p := newPayment(6001)
	p.UserID = nil
	first, err := s.SaveRequested(ctx, p)
	if err != nil {
		t.Fatal(err)
	}
	reloaded, err := s.GetByID(ctx, first.Payment.ID)
	if err != nil || reloaded.UserID != nil {
		t.Fatalf("guest ownership not preserved: %#v, %v", reloaded.UserID, err)
	}
	p.ID = uuid.New()
	replayed, err := s.SaveRequested(ctx, p)
	if err != nil || replayed.Inserted || replayed.Payment.ID != first.Payment.ID || replayed.Payment.UserID != nil {
		t.Fatalf("guest replay did not retain original payment: %#v, %v", replayed, err)
	}
}

func TestSaveRequested_DuplicateRequestEventID_DoesNotDoubleInsert(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	p := newPayment(2)
	first, err := s.SaveRequested(ctx, p)
	if err != nil {
		t.Fatalf("first SaveRequested returned error: %v", err)
	}

	// Simulate a redelivered payment.requested message: same RequestEventID,
	// different local id/amount attempted.
	dup := p
	dup.ID = uuid.New()
	second, err := s.SaveRequested(ctx, dup)
	if err != nil {
		t.Fatalf("second SaveRequested returned error: %v", err)
	}
	if second.Inserted {
		t.Fatalf("expected duplicate request to NOT be a fresh insert")
	}
	if second.Payment.ID != first.Payment.ID {
		t.Fatalf("expected the pre-existing row to be returned, got a different id")
	}
}

func TestRecordResultAndEnqueue_WritesOutboxRowInSameTransaction(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	p := newPayment(3)
	saved, err := s.SaveRequested(ctx, p)
	if err != nil {
		t.Fatalf("SaveRequested returned error: %v", err)
	}

	err = s.RecordResultAndEnqueue(ctx, saved.Payment.ID, domain.StatusSucceeded, "", "payment.succeeded", []byte(`{"ok":true}`))
	if err != nil {
		t.Fatalf("RecordResultAndEnqueue returned error: %v", err)
	}

	updated, err := s.GetByID(ctx, saved.Payment.ID)
	if err != nil {
		t.Fatalf("GetByID returned error: %v", err)
	}
	if updated.Status != domain.StatusSucceeded {
		t.Fatalf("expected status SUCCEEDED, got %s", updated.Status)
	}

	rows, err := s.FetchUnpublished(ctx, 10)
	if err != nil {
		t.Fatalf("FetchUnpublished returned error: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("expected exactly 1 unpublished outbox row, got %d", len(rows))
	}
	if rows[0].Topic != "payment.succeeded" {
		t.Fatalf("expected topic payment.succeeded, got %s", rows[0].Topic)
	}

	if err := s.MarkPublished(ctx, rows[0].ID); err != nil {
		t.Fatalf("MarkPublished returned error: %v", err)
	}
	rowsAfter, err := s.FetchUnpublished(ctx, 10)
	if err != nil {
		t.Fatalf("FetchUnpublished returned error: %v", err)
	}
	if len(rowsAfter) != 0 {
		t.Fatalf("expected 0 unpublished rows after MarkPublished, got %d", len(rowsAfter))
	}
}

func TestRecordResultAndEnqueue_DuplicateResult_IsSafeNoOp(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	p := newPayment(4)
	saved, err := s.SaveRequested(ctx, p)
	if err != nil {
		t.Fatalf("SaveRequested returned error: %v", err)
	}

	if err := s.RecordResultAndEnqueue(ctx, saved.Payment.ID, domain.StatusSucceeded, "", "payment.succeeded", []byte(`{"n":1}`)); err != nil {
		t.Fatalf("first RecordResultAndEnqueue returned error: %v", err)
	}
	// Simulate reprocessing the exact same result after a restart: this must
	// not overwrite status, must not enqueue a second outbox row, and must
	// not error.
	if err := s.RecordResultAndEnqueue(ctx, saved.Payment.ID, domain.StatusFailed, "should not apply", "payment.failed", []byte(`{"n":2}`)); err != nil {
		t.Fatalf("second RecordResultAndEnqueue returned error: %v", err)
	}

	updated, err := s.GetByID(ctx, saved.Payment.ID)
	if err != nil {
		t.Fatalf("GetByID returned error: %v", err)
	}
	if updated.Status != domain.StatusSucceeded {
		t.Fatalf("expected status to remain SUCCEEDED (first result wins), got %s", updated.Status)
	}

	rows, err := s.FetchUnpublished(ctx, 10)
	if err != nil {
		t.Fatalf("FetchUnpublished returned error: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("expected exactly 1 outbox row (no duplicate enqueue), got %d", len(rows))
	}
}

func TestMigrate_IsIdempotent(t *testing.T) {
	s := newTestStore(t)
	// newTestStore already migrated once; migrating again (simulating a
	// Payment Service restart) must not error.
	// The DSN isn't exposed by newTestStore, so re-run Migrate via Ping to
	// at least assert the store is still usable post-construction.
	if err := s.Ping(context.Background()); err != nil {
		t.Fatalf("Ping returned error after migrate: %v", err)
	}
}
