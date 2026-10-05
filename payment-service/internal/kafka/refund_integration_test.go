package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
)

type lostRefundResponse struct {
	provider.RefundProvider
	lost     atomic.Bool
	unknown  atomic.Bool
	attempts atomic.Int32
	fail     bool
}

type lostChargeResponse struct {
	provider.Provider
	lost atomic.Bool
}

func (p *lostChargeResponse) ChargeIdempotently(ctx context.Context, key uuid.UUID, req provider.ChargeRequest) (provider.ChargeResult, error) {
	result, err := p.Provider.ChargeIdempotently(ctx, key, req)
	if err == nil && p.lost.CompareAndSwap(true, false) {
		return provider.ChargeResult{}, errors.New("simulated charge committed but response lost")
	}
	return result, err
}

func (p *lostRefundResponse) RefundIdempotently(ctx context.Context, key uuid.UUID, req provider.RefundRequest) (provider.ChargeResult, error) {
	p.attempts.Add(1)
	if p.fail {
		return provider.ChargeResult{Reason: "confirmed refund rejection"}, nil
	}
	result, err := p.RefundProvider.RefundIdempotently(ctx, key, req)
	if err == nil && (p.unknown.Load() || p.lost.CompareAndSwap(true, false)) {
		return provider.ChargeResult{}, errors.New("simulated refund committed but response lost")
	}
	return result, err
}

func successfulOriginal(t *testing.T, f *retryFixture, order int64) domain.Payment {
	t.Helper()
	req, payload := requestedBytes(t, order)
	// The original checkout total includes shipping; Go never subtracts or
	// recomputes it when refunding.
	req.Amount = json.Number("54.98")
	var marshalErr error
	payload, marshalErr = json.Marshal(req)
	if marshalErr != nil {
		t.Fatal(marshalErr)
	}
	c, _, _ := f.consumer(t, provider.NewDurableProvider(f.store), &retryLogs{})
	if got := c.handle(context.Background(), kafkago.Message{Value: payload}); got != outcomeCommit {
		t.Fatalf("original payment outcome=%v", got)
	}
	payment, err := f.store.GetByRequestEventID(context.Background(), req.EventID)
	if err != nil || payment.Status != domain.StatusSucceeded || payment.ProviderTransactionID == "" {
		t.Fatalf("original payment=%+v error=%v", payment, err)
	}
	return payment
}

func refundEvent(p domain.Payment) events.RefundRequestedEvent {
	return events.RefundRequestedEvent{
		EventID: uuid.New(), RefundID: uuid.New(), RequestEventID: p.RequestEventID,
		OrderID: p.OrderID, PaymentID: p.ID, Amount: json.Number(fmt.Sprintf("%d.%02d", p.AmountCents/100, p.AmountCents%100)),
		Currency: p.Currency, ProviderTransactionID: p.ProviderTransactionID, OccurredAt: eventtime.Now(),
	}
}

func refundMessage(t *testing.T, req events.RefundRequestedEvent) kafkago.Message {
	t.Helper()
	payload, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	return kafkago.Message{Value: payload}
}

func refundConsumer(t *testing.T, f *retryFixture, p provider.RefundProvider) (*RefundConsumer, string, string) {
	t.Helper()
	// Reuse the real broker fixture, including its per-test topics and DLQ.
	base, topic, group := f.consumer(t, provider.NewDurableProvider(f.store), &retryLogs{})
	c := NewRefundConsumer(f.brokers, group, f.store, p, slog.Default())
	_ = c.reader.Close()
	_ = c.dlqWriter.Close()
	c.reader, c.dlqWriter = base.reader, base.dlqWriter
	transport := &kafkago.Transport{}
	c.dlqWriter.Transport = transport
	t.Cleanup(transport.CloseIdleConnections)
	t.Cleanup(func() { _ = c.Close() })
	return c, topic, group
}

func TestDurableProviderRefundLedger(t *testing.T) {
	f := newRetryFixture(t)
	ctx := context.Background()
	p := provider.NewDurableProvider(f.store)
	key := uuid.New()
	req := provider.ChargeRequest{OrderID: 9001, AmountCents: 5498, Currency: "EUR"}
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := p.ChargeIdempotently(ctx, key, req)
			if err != nil || !result.Approved || result.ProviderTransactionID == "" {
				t.Errorf("concurrent charge=%+v err=%v", result, err)
			}
		}()
	}
	wg.Wait()
	reconstructed := provider.NewDurableProvider(reopenPaymentStore(t, f))
	first, err := reconstructed.ChargeIdempotently(ctx, key, req)
	if err != nil {
		t.Fatal(err)
	}
	changed := req
	changed.AmountCents++
	if _, err := reconstructed.ChargeIdempotently(ctx, key, changed); err == nil {
		t.Fatal("changed charge parameters were accepted")
	}
	decline := req
	decline.AmountCents = 1013
	if result, err := reconstructed.ChargeIdempotently(ctx, uuid.New(), decline); err != nil || result.Approved {
		t.Fatalf(".13 decline lost: %+v %v", result, err)
	}
	original := successfulOriginal(t, f, 9002)
	refundID := uuid.New()
	refundReq := provider.RefundRequest{PaymentID: original.ID, OrderID: original.OrderID,
		AmountCents: original.AmountCents, Currency: original.Currency, ProviderTransactionID: original.ProviderTransactionID}
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := reconstructed.RefundIdempotently(ctx, refundID, refundReq)
			if err != nil || !result.Approved || result.ProviderTransactionID == "" {
				t.Errorf("concurrent refund=%+v err=%v", result, err)
			}
		}()
	}
	wg.Wait()
	again, err := provider.NewDurableProvider(reopenPaymentStore(t, f)).RefundIdempotently(ctx, refundID, refundReq)
	if err != nil || !again.Approved {
		t.Fatalf("restart refund=%+v error=%v", again, err)
	}
	if _, err := reconstructed.RefundIdempotently(ctx, uuid.New(), refundReq); err == nil {
		t.Fatal("new refund key for already-refunded payment accepted")
	}
	refundReq.AmountCents--
	if _, err := reconstructed.RefundIdempotently(ctx, refundID, refundReq); err == nil {
		t.Fatal("partial/changed refund accepted")
	}
	var charges, refunds int
	if err := f.sql.QueryRow(ctx, `SELECT
		(SELECT count(*) FROM provider_ledger WHERE operation='CHARGE' AND idempotency_key=$1),
		(SELECT count(*) FROM provider_ledger WHERE operation='REFUND' AND payment_id=$2)`,
		key, original.ID).Scan(&charges, &refunds); err != nil || charges != 1 || refunds != 1 {
		t.Fatalf("effects: charges=%d refunds=%d err=%v original=%+v", charges, refunds, err, first)
	}
	for index, scenario := range []string{"result_rollback", "lost_charge_response"} {
		t.Run(scenario, func(t *testing.T) {
			req, payload := requestedBytes(t, int64(9050+index))
			p := &lostChargeResponse{Provider: provider.NewDurableProvider(f.store)}
			if scenario == "result_rollback" {
				if _, err := f.sql.Exec(ctx, "INSERT INTO retry_fault VALUES($1,'UPDATE')", req.OrderID); err != nil {
					t.Fatal(err)
				}
			} else {
				p.lost.Store(true)
			}
			c, _, _ := f.consumer(t, p, &retryLogs{})
			if got := c.handle(ctx, kafkago.Message{Value: payload}); got != outcomeRetry {
				t.Fatalf("uncertain charge must remain unacknowledged: %v", got)
			}
			payment, err := f.store.GetByRequestEventID(ctx, req.EventID)
			if err != nil || payment.Status != domain.StatusRequested {
				t.Fatalf("result rollback claimed finality: %+v %v", payment, err)
			}
			var effects int
			if err := f.sql.QueryRow(ctx, "SELECT count(*) FROM provider_ledger WHERE operation='CHARGE' AND idempotency_key=$1",
				payment.ID).Scan(&effects); err != nil || effects != 1 {
				t.Fatalf("runtime ledger did not independently commit: effects=%d %v", effects, err)
			}
			if _, err := f.sql.Exec(ctx, "DELETE FROM retry_fault WHERE order_id=$1", req.OrderID); err != nil {
				t.Fatal(err)
			}
			reopened := reopenPaymentStore(t, f)
			next, _, _ := f.consumer(t, provider.NewDurableProvider(reopened), &retryLogs{})
			next.store = reopened
			for delivery := 0; delivery < 3; delivery++ {
				if got := next.handle(ctx, kafkago.Message{Value: payload}); got != outcomeCommit {
					t.Fatalf("runtime charge restart/replay=%v", got)
				}
			}
			payment, err = reopened.GetByRequestEventID(ctx, req.EventID)
			if err != nil || payment.Status != domain.StatusSucceeded || payment.ProviderTransactionID == "" {
				t.Fatalf("runtime charge did not converge: %+v %v", payment, err)
			}
			var terminal int
			if err := f.sql.QueryRow(ctx, "SELECT count(*) FROM payment_outbox WHERE payment_id=$1 AND topic=$2",
				payment.ID, events.PaymentSucceeded).Scan(&terminal); err != nil || terminal != 1 {
				t.Fatalf("runtime terminal outbox=%d %v", terminal, err)
			}
		})
	}
}

func TestRefundConsumerDurabilityAndFinality(t *testing.T) {
	f := newRetryFixture(t)
	ctx := context.Background()
	// The provider ledger commits before (and independently of) result/outbox writes.
	if _, err := f.sql.Exec(ctx, `
		CREATE TABLE refund_fault (operation text);
		CREATE FUNCTION fail_refund_result() RETURNS trigger LANGUAGE plpgsql AS $$
		BEGIN
			IF EXISTS (SELECT 1 FROM refund_fault WHERE operation=TG_TABLE_NAME) THEN
				RAISE EXCEPTION 'injected refund result failure';
			END IF;
			RETURN NEW;
		END $$;
		CREATE TRIGGER refund_result_fault BEFORE UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION fail_refund_result();
		CREATE TRIGGER refund_outbox_fault BEFORE INSERT ON payment_outbox FOR EACH ROW EXECUTE FUNCTION fail_refund_result();
	`); err != nil {
		t.Fatal(err)
	}
	for i, fault := range []string{"refunds", "payment_outbox", "lost_response"} {
		t.Run(fault, func(t *testing.T) {
			original := successfulOriginal(t, f, int64(9100+i))
			req := refundEvent(original)
			p := &lostRefundResponse{RefundProvider: provider.NewDurableProvider(f.store)}
			c, _, _ := refundConsumer(t, f, p)
			if fault == "lost_response" {
				p.lost.Store(true)
			} else if _, err := f.sql.Exec(ctx, "INSERT INTO refund_fault VALUES ($1)", fault); err != nil {
				t.Fatal(err)
			}
			if got := c.handle(ctx, refundMessage(t, req)); got != outcomeRetry {
				t.Fatalf("failure outcome=%v", got)
			}
			refund, err := f.store.GetRefundByID(ctx, req.RefundID)
			if err != nil || refund.Status != domain.StatusRequested {
				t.Fatalf("failed result transaction changed status: %+v %v", refund, err)
			}
			var effects int
			if err := f.sql.QueryRow(ctx, "SELECT count(*) FROM provider_ledger WHERE operation='REFUND' AND payment_id=$1", original.ID).Scan(&effects); err != nil || effects != 1 {
				t.Fatalf("independent effect count=%d err=%v", effects, err)
			}
			if _, err := f.sql.Exec(ctx, "DELETE FROM refund_fault"); err != nil {
				t.Fatal(err)
			}
			next, _, _ := refundConsumer(t, f, provider.NewDurableProvider(reopenPaymentStore(t, f)))
			next.store = reopenPaymentStore(t, f)
			for delivery := 0; delivery < 3; delivery++ {
				if got := next.handle(ctx, refundMessage(t, req)); got != outcomeCommit {
					t.Fatalf("restart/replay outcome=%v", got)
				}
			}
			refund, err = next.store.GetRefundByID(ctx, req.RefundID)
			if err != nil || refund.Status != domain.StatusSucceeded || refund.ProviderTransactionID == "" ||
				refund.AmountCents != original.AmountCents || refund.Currency != original.Currency {
				t.Fatalf("refund result=%+v err=%v", refund, err)
			}
			var payload []byte
			if err := f.sql.QueryRow(ctx, "SELECT payload FROM payment_outbox WHERE refund_id=$1 AND topic=$2",
				req.RefundID, events.RefundSucceeded).Scan(&payload); err != nil {
				t.Fatal(err)
			}
			var result events.RefundSucceededEvent
			if err := json.Unmarshal(payload, &result); err != nil {
				t.Fatal(err)
			}
			if result.RequestEventID != req.EventID || result.RefundID != req.RefundID ||
				result.OrderID != original.OrderID || result.PaymentID != original.ID ||
				result.ProviderTransactionID != refund.ProviderTransactionID || result.EventID == uuid.Nil {
				t.Fatalf("refund result correlation lost: %+v", result)
			}
			if err := next.store.RecordRefundUnresolved(ctx, req.RefundID, "late progress", []byte(`{}`)); err != nil {
				t.Fatal(err)
			}
			if err := next.store.RecordRefundResultAndEnqueue(ctx, req.RefundID, domain.StatusFailed, "late failure", "", events.RefundFailed, []byte(`{}`)); err == nil {
				t.Fatal("contradictory terminal result accepted")
			}
			var terminal, progress int
			if err := f.sql.QueryRow(ctx, `SELECT count(*) FILTER (WHERE topic=$2),count(*) FILTER (WHERE topic=$3)
				FROM payment_outbox WHERE refund_id=$1`, req.RefundID, events.RefundSucceeded, events.RefundUnresolved).Scan(&terminal, &progress); err != nil || terminal != 1 || progress > 1 {
				t.Fatalf("outbox terminal=%d progress=%d err=%v", terminal, progress, err)
			}
			changed := req
			changed.Amount = json.Number("0.01")
			if got := next.handle(ctx, refundMessage(t, changed)); got != outcomeCommit {
				t.Fatalf("changed duplicate not DLQ'd: %v", got)
			}
		})
	}
}

func TestRefundConsumerValidationAndConfirmedFailure(t *testing.T) {
	f := newRetryFixture(t)
	ctx := context.Background()
	original := successfulOriginal(t, f, 9200)
	c, _, _ := refundConsumer(t, f, provider.NewDurableProvider(f.store))
	for _, alter := range []func(*events.RefundRequestedEvent){
		func(r *events.RefundRequestedEvent) { r.Amount = json.Number("0.01") },
		func(r *events.RefundRequestedEvent) { r.Amount = json.Number("184467440737095571.14") },
		func(r *events.RefundRequestedEvent) { r.Currency = "USD" },
		func(r *events.RefundRequestedEvent) { r.OrderID++ },
		func(r *events.RefundRequestedEvent) { r.RequestEventID = uuid.New() },
		func(r *events.RefundRequestedEvent) { r.ProviderTransactionID = "wrong-reference" },
		func(r *events.RefundRequestedEvent) { r.EventID = r.RefundID },
	} {
		req := refundEvent(original)
		alter(&req)
		if got := c.handle(ctx, refundMessage(t, req)); got != outcomeCommit {
			t.Fatalf("invalid request not DLQ'd: %+v outcome=%v", req, got)
		}

	}
	req, payload := requestedBytes(t, 9201)
	var raw map[string]any
	_ = json.Unmarshal(payload, &raw)
	raw["amount"] = json.Number("10.13")
	payload, _ = json.Marshal(raw)
	charge, _, _ := f.consumer(t, provider.NewDurableProvider(f.store), &retryLogs{})
	if got := charge.handle(ctx, kafkago.Message{Value: payload}); got != outcomeCommit {
		t.Fatal("declined charge not committed")
	}
	failed, err := f.store.GetByRequestEventID(ctx, req.EventID)
	if err != nil {
		t.Fatal(err)
	}
	if got := c.handle(ctx, refundMessage(t, refundEvent(failed))); got != outcomeCommit {
		t.Fatal("failed original not DLQ'd")
	}
	var count int
	if err := f.sql.QueryRow(ctx, "SELECT count(*) FROM provider_ledger WHERE operation='REFUND'").Scan(&count); err != nil || count != 0 {
		t.Fatalf("invalid original called provider: effects=%d %v", count, err)
	}
	refundReq := refundEvent(original)
	reject := &lostRefundResponse{RefundProvider: provider.NewDurableProvider(f.store), fail: true}
	rejected, _, _ := refundConsumer(t, f, reject)
	if got := rejected.handle(ctx, refundMessage(t, refundReq)); got != outcomeCommit {
		t.Fatal("confirmed failure not settled")
	}
	// A later delivery must not retry a confirmed failure with any key.
	if got := c.handle(ctx, refundMessage(t, refundReq)); got != outcomeCommit {
		t.Fatal("failed refund replay not committed")
	}
	newKey := refundReq
	newKey.EventID, newKey.RefundID = uuid.New(), uuid.New()
	if got := c.handle(ctx, refundMessage(t, newKey)); got != outcomeCommit {
		t.Fatal("replacement key not DLQ'd")
	}
	state, err := f.store.GetRefundByID(ctx, refundReq.RefundID)
	if err != nil || state.Status != domain.StatusFailed {
		t.Fatalf("confirmed failure changed: %+v %v", state, err)
	}
	if err := f.sql.QueryRow(ctx, "SELECT count(*) FROM provider_ledger WHERE operation='REFUND'").Scan(&count); err != nil || count != 0 {
		t.Fatalf("confirmed failure replay called provider: effects=%d %v", count, err)
	}
}

type unresolvedCharges struct{}

func (unresolvedCharges) ChargeIdempotently(context.Context, uuid.UUID, provider.ChargeRequest) (provider.ChargeResult, error) {
	return provider.ChargeResult{}, errors.New("unknown provider outcome")
}

func TestIndependentRefundConsumerKafkaOffsets(t *testing.T) {
	f := newRetryFixture(t)
	ctx := context.Background()
	original := successfulOriginal(t, f, 9300)
	blocked, paymentTopic, paymentGroup := f.consumer(t, unresolvedCharges{}, &retryLogs{})
	unknown, payload := requestedBytes(t, 9301)
	f.write(t, paymentTopic, payload)
	runRetryConsumer(t, blocked)
	awaitRetry(t, "durable nonterminal payment progress", func() bool {
		var count int
		err := f.sql.QueryRow(ctx, "SELECT count(*) FROM payment_outbox WHERE topic=$1", events.PaymentUnresolved).Scan(&count)
		return err == nil && count == 1
	})
	if offset := f.offset(t, paymentTopic, paymentGroup); offset > 0 {
		t.Fatalf("unresolved charge committed offset=%d", offset)
	}
	uncertain := &lostRefundResponse{RefundProvider: provider.NewDurableProvider(f.store)}
	uncertain.unknown.Store(true)
	c, topic, group := refundConsumer(t, f, uncertain)
	req := refundEvent(original)
	message := refundMessage(t, req)
	f.write(t, topic, message.Value, message.Value)
	runCtx, cancel := context.WithCancel(ctx)
	done := make(chan error, 1)
	go func() { done <- c.Run(runCtx) }()
	t.Cleanup(func() {
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Error(err)
			}
		case <-time.After(3 * time.Second):
			t.Error("refund consumer did not stop")
		}
	})
	awaitRetry(t, "unresolved refund retries the same source record", func() bool {
		return uncertain.attempts.Load() >= 2
	})
	if offset := f.offset(t, topic, group); offset > 0 {
		t.Fatalf("unresolved refund advanced offset=%d", offset)
	}
	uncertain.unknown.Store(false)
	awaitRetry(t, "independent refund settles and duplicate offsets commit", func() bool {
		return f.offset(t, topic, group) == 2
	})
	changed := req
	changed.Currency = "USD"
	f.write(t, topic, refundMessage(t, changed).Value)
	awaitRetry(t, "altered terminal duplicate is DLQ'd then acknowledged", func() bool {
		return f.offset(t, topic, group) == 3
	})
	dlq := kafkago.NewReader(kafkago.ReaderConfig{
		Brokers: f.brokers, Topic: topic + DLQSuffix, Partition: 0, MinBytes: 1, MaxBytes: 10e6,
	})
	defer dlq.Close()
	dlqCtx, stopDLQ := context.WithTimeout(ctx, 5*time.Second)
	defer stopDLQ()
	dead, err := dlq.ReadMessage(dlqCtx)
	if err != nil {
		t.Fatalf("altered request was not durably copied to DLQ: %v", err)
	}
	var rejected events.RefundRequestedEvent
	if err := json.Unmarshal(dead.Value, &rejected); err != nil || rejected.EventID != req.EventID || rejected.Currency != "USD" {
		t.Fatalf("wrong DLQ record: %s %v", dead.Value, err)
	}
	if offset := f.offset(t, paymentTopic, paymentGroup); offset > 0 {
		t.Fatal("refund processing acknowledged unresolved charge")
	}
	payment, err := f.store.GetByRequestEventID(ctx, unknown.EventID)
	if err != nil || payment.Status != domain.StatusRequested {
		t.Fatalf("uncertainty became terminal: %+v %v", payment, err)
	}
	var results, progress, effects int
	err = f.sql.QueryRow(ctx, `SELECT
		(SELECT count(*) FROM payment_outbox WHERE refund_id=$1 AND topic=$2),
		(SELECT count(*) FROM payment_outbox WHERE refund_id=$1 AND topic=$3),
		(SELECT count(*) FROM provider_ledger WHERE operation='REFUND' AND idempotency_key=$1)`,
		req.RefundID, events.RefundSucceeded, events.RefundUnresolved).Scan(&results, &progress, &effects)
	if err != nil || results != 1 || progress != 1 || effects != 1 {
		t.Fatalf("refund results=%d progress=%d effects=%d error=%v", results, progress, effects, err)
	}
}
