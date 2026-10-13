package kafka

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"testing"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
)

type pendingProvider struct{ keys []uuid.UUID }

func (p *pendingProvider) ChargeIdempotently(_ context.Context, id uuid.UUID, req provider.ChargeRequest) (provider.ChargeResult, error) {
	p.keys = append(p.keys, id)
	if req.OrderID == 25 {
		return provider.ChargeResult{Pending: true, ProviderTransactionID: "pi_fixture"}, nil
	}
	return provider.ChargeResult{Approved: true}, nil
}

func TestStripePendingCommitsWithoutSettlementOrBlockingNextOrder(t *testing.T) {
	f := newRetryFixture(t)
	p := &pendingProvider{}
	c := NewRequestConsumer(f.brokers, "pending-fixture", f.store, p, slog.New(slog.NewTextHandler(io.Discard, nil)))
	defer c.Close()
	ctx := context.Background()
	request := events.PaymentRequestedEvent{EventID: uuid.New(), OrderID: 25, Amount: json.Number("24.98"), Currency: "EUR", Provider: "stripe"}
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if c.handle(ctx, kafkago.Message{Topic: events.PaymentRequested, Value: body}) != outcomeCommit {
			t.Fatal("browser interaction blocked the request consumer")
		}
	}
	payment, err := f.store.GetByRequestEventID(ctx, request.EventID)
	if err != nil || payment.Status != domain.StatusRequested || payment.Provider != "stripe" || payment.UserID != nil {
		t.Fatal("pending handoff lost provider, guest ownership or prematurely settled")
	}
	if len(p.keys) != 2 || p.keys[0] != payment.ID || p.keys[1] != payment.ID {
		t.Fatal("pending replay changed the canonical provider identity")
	}
	var results int
	if err := f.sql.QueryRow(ctx, "SELECT count(*) FROM payment_outbox WHERE payment_id=$1", payment.ID).Scan(&results); err != nil || results != 0 {
		t.Fatal("browser-pending payment emitted a financial outcome")
	}
	request.EventID, request.OrderID, request.Provider = uuid.New(), 74, "simulated"
	body, err = json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	if c.handle(ctx, kafkago.Message{Topic: events.PaymentRequested, Value: body}) != outcomeCommit {
		t.Fatal("subsequent payment did not commit")
	}
	next, err := f.store.GetByRequestEventID(ctx, request.EventID)
	if err != nil || next.Status != domain.StatusSucceeded {
		t.Fatal("pending browser payment blocked a later order")
	}
}
