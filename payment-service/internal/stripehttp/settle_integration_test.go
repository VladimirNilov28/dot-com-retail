package stripehttp

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

func newSettleFixture(t *testing.T) (*Handler, *pgx.Conn) {
	t.Helper()
	ctx := context.Background()
	container, err := tcpostgres.Run(ctx, "postgres:17",
		tcpostgres.WithDatabase("payment_service"), tcpostgres.WithUsername("payment_service"),
		tcpostgres.WithPassword("payment_service"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(60*time.Second)))
	if err != nil {
		t.Fatalf("postgres: %v", err)
	}
	t.Cleanup(func() { _ = testcontainers.TerminateContainer(container) })
	dsn, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	s, err := store.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	if err := s.Migrate(dsn); err != nil {
		t.Fatal(err)
	}
	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close(ctx) })
	return &Handler{Store: s, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}, conn
}

func outbox(t *testing.T, conn *pgx.Conn, paymentID uuid.UUID) []string {
	t.Helper()
	rows, err := conn.Query(context.Background(),
		"SELECT topic||':'||coalesce(payload->>'reason','')||':'||coalesce(payload->>'attemptId','') FROM payment_outbox WHERE payment_id=$1 ORDER BY created_at", paymentID)
	if err != nil {
		t.Fatal(err)
	}
	topics, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		t.Fatal(err)
	}
	return topics
}

func TestDeclinedAttemptsPublishOncePerAttemptAndSuccessStillSettles(t *testing.T) {
	h, conn := newSettleFixture(t)
	ctx := context.Background()
	payment := domain.Payment{ID: uuid.New(), RequestEventID: uuid.New(), OrderID: 41, AmountCents: 2498,
		Currency: "EUR", Status: domain.StatusRequested, Provider: "stripe"}
	if _, err := h.Store.SaveRequested(ctx, payment); err != nil {
		t.Fatal(err)
	}
	if _, _, err := h.Store.StripeOperation(ctx, payment.ID, "intent"); err != nil {
		t.Fatal(err)
	}
	intent := func(status string, failure *provider.StripeError) provider.StripeIntent {
		return provider.StripeIntent{ID: "pi_settlefixture", Status: status, Amount: 2498, Currency: "eur",
			Metadata: map[string]string{"payment_id": payment.ID.String(), "order_id": "41"}, LastError: failure}
	}
	insufficient := intent("requires_payment_method", &provider.StripeError{Code: "card_declined", DeclineCode: "insufficient_funds", Charge: "ch_first"})
	// Webhook + replay + reconciliation of the same attempt: one event.
	for i := 0; i < 3; i++ {
		if err := h.settle(ctx, payment.ID, insufficient); err != nil {
			t.Fatal(err)
		}
	}
	expired := intent("requires_payment_method", &provider.StripeError{Code: "expired_card", Charge: "ch_second"})
	for i := 0; i < 2; i++ {
		if err := h.settle(ctx, payment.ID, expired); err != nil {
			t.Fatal(err)
		}
	}
	// A transient processing error is unresolved, never a decline.
	if err := h.settle(ctx, payment.ID, intent("requires_payment_method", &provider.StripeError{Code: "processing_error", Charge: "ch_third"})); err != nil {
		t.Fatal(err)
	}
	stored, err := h.Store.GetByID(ctx, payment.ID)
	if err != nil || stored.Status != domain.StatusRequested {
		t.Fatal("a decline must keep the payment retryable on the same intent")
	}
	for i := 0; i < 2; i++ {
		if err := h.settle(ctx, payment.ID, intent("succeeded", nil)); err != nil {
			t.Fatal(err)
		}
	}
	// A stale decline replayed after success cannot publish anything.
	if err := h.settle(ctx, payment.ID, insufficient); err != nil {
		t.Fatal(err)
	}
	got := outbox(t, conn, payment.ID)
	want := []string{
		events.PaymentDeclined + ":insufficient_funds:ch_first",
		events.PaymentDeclined + ":expired_card:ch_second",
		events.PaymentUnresolved + ":payment_retry_required:",
		events.PaymentSucceeded + "::",
	}
	if len(got) != len(want) {
		t.Fatalf("outbox = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("outbox = %v, want %v", got, want)
		}
	}
	var payload events.PaymentDeclinedEvent
	if err := conn.QueryRow(ctx, "SELECT payload FROM payment_outbox WHERE topic=$1 ORDER BY created_at LIMIT 1",
		events.PaymentDeclined).Scan(&payload); err != nil {
		t.Fatal(err)
	}
	if payload.RequestEventID != payment.RequestEventID || payload.OrderID != 41 || payload.PaymentID != payment.ID {
		t.Fatal("decline event lost its correlation")
	}
	if stored, _ = h.Store.GetByID(ctx, payment.ID); stored.Status != domain.StatusSucceeded {
		t.Fatal("success after declines must settle normally")
	}
}
