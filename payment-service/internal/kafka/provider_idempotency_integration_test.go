package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	kafkago "github.com/segmentio/kafka-go"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

// This gateway simulation owns a durable charge ledger independently of the
// consumer's result transaction. Recreating the adapter cannot erase effects.
type ledgerProvider struct {
	pool           *pgxpool.Pool
	ambiguousOnce  atomic.Bool
	mu             sync.Mutex
	keys           []uuid.UUID
	concurrentGate chan struct{}
}

func newLedgerProvider(t *testing.T, f *retryFixture) *ledgerProvider {
	t.Helper()
	pool, err := pgxpool.New(context.Background(), f.sql.Config().ConnString())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return &ledgerProvider{pool: pool}
}

func (p *ledgerProvider) ChargeIdempotently(ctx context.Context, key uuid.UUID, req provider.ChargeRequest) (provider.ChargeResult, error) {
	if key == uuid.Nil {
		return provider.ChargeResult{}, errors.New("missing provider key")
	}
	p.mu.Lock()
	p.keys = append(p.keys, key)
	p.mu.Unlock()
	_, err := p.pool.Exec(ctx, `
		INSERT INTO gateway_charges (idempotency_key, order_id, amount_cents, currency)
		VALUES ($1, $2, $3, $4) ON CONFLICT (idempotency_key) DO NOTHING
	`, key, req.OrderID, req.AmountCents, req.Currency)
	if err != nil {
		return provider.ChargeResult{}, err
	}
	var original provider.ChargeRequest
	err = p.pool.QueryRow(ctx, `
		SELECT order_id, amount_cents, currency FROM gateway_charges WHERE idempotency_key=$1
	`, key).Scan(&original.OrderID, &original.AmountCents, &original.Currency)
	if err != nil {
		return provider.ChargeResult{}, err
	}
	if original != req {
		return provider.ChargeResult{}, errors.New("gateway idempotency parameter mismatch")
	}
	if p.concurrentGate != nil {
		select {
		case <-p.concurrentGate:
		case <-ctx.Done():
			return provider.ChargeResult{}, ctx.Err()
		}
	}
	if p.ambiguousOnce.CompareAndSwap(true, false) {
		return provider.ChargeResult{}, errors.New("gateway charged but response was lost")
	}
	return provider.ChargeResult{Approved: true}, nil
}

func (p *ledgerProvider) assertIdentity(t *testing.T, want uuid.UUID, minAttempts int) {
	t.Helper()
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.keys) < minAttempts {
		t.Fatalf("attempts=%d, want at least %d", len(p.keys), minAttempts)
	}
	for _, key := range p.keys {
		if key != want {
			t.Fatalf("provider key changed: got %s, durable Payment ID %s", key, want)
		}
	}
}

func assertOneChargeAndResult(t *testing.T, f *retryFixture, requestID uuid.UUID) uuid.UUID {
	t.Helper()
	payment, err := f.store.GetByRequestEventID(context.Background(), requestID)
	if err != nil || payment.Status != domain.StatusSucceeded {
		t.Fatalf("payment status=%s error=%v", payment.Status, err)
	}
	var payments, charges, results int
	err = f.sql.QueryRow(context.Background(), `
		SELECT (SELECT count(*) FROM payments WHERE request_event_id=$1),
		       (SELECT count(*) FROM gateway_charges WHERE order_id=$2),
		       (SELECT count(*) FROM payment_outbox WHERE payment_id=$3 AND topic IN ('payment.succeeded','payment.failed'))
	`, requestID, payment.OrderID, payment.ID).Scan(&payments, &charges, &results)
	if err != nil || payments != 1 || charges != 1 || results != 1 {
		t.Fatalf("logical effects: payments=%d charges=%d results=%d error=%v", payments, charges, results, err)
	}
	var payload []byte
	if err := f.sql.QueryRow(context.Background(), "SELECT payload FROM payment_outbox WHERE payment_id=$1 AND topic=$2", payment.ID, events.PaymentSucceeded).Scan(&payload); err != nil {
		t.Fatal(err)
	}
	var result events.PaymentSucceededEvent
	if err := json.Unmarshal(payload, &result); err != nil {
		t.Fatal(err)
	}
	if result.RequestEventID != requestID || result.PaymentID != payment.ID || result.OrderID != payment.OrderID || result.EventID == uuid.Nil {
		t.Fatalf("outbox result lost durable correlation: %+v", result)
	}
	return payment.ID
}

func reopenPaymentStore(t *testing.T, f *retryFixture) *store.Store {
	t.Helper()
	reopened, err := store.New(context.Background(), f.sql.Config().ConnString())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(reopened.Close)
	return reopened
}

func TestRequestConsumerProviderIdempotency(t *testing.T) {
	f := newRetryFixture(t)
	if _, err := f.sql.Exec(context.Background(), `
		CREATE TABLE gateway_charges (
			idempotency_key uuid PRIMARY KEY, order_id bigint NOT NULL,
			amount_cents bigint NOT NULL, currency text NOT NULL
		)
	`); err != nil {
		t.Fatal(err)
	}

	if _, err := f.sql.Exec(context.Background(), `
		CREATE FUNCTION inject_outbox_fault() RETURNS trigger LANGUAGE plpgsql AS $$
		BEGIN
			IF EXISTS (
				SELECT 1 FROM retry_fault f JOIN payments p ON p.order_id=f.order_id
				WHERE p.id=NEW.payment_id AND f.operation='OUTBOX'
			) THEN RAISE EXCEPTION 'controlled result outbox failure'; END IF;
			RETURN NEW;
		END $$;
		CREATE TRIGGER gateway_outbox_fault BEFORE INSERT ON payment_outbox
		FOR EACH ROW EXECUTE FUNCTION inject_outbox_fault();
	`); err != nil {
		t.Fatal(err)
	}
	for index, scenario := range []struct {
		name      string
		operation string
		restart   bool
	}{
		{"result_database_failure", "UPDATE", false},
		{"restart_after_successful_charge", "UPDATE", true},
		{"outbox_failure_rolls_back_terminal_status", "OUTBOX", false},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			p, logs := newLedgerProvider(t, f), &retryLogs{}
			c, topic, group := f.consumer(t, p, logs)
			req, payload := requestedBytes(t, int64(5100+index))
			if _, err := f.sql.Exec(context.Background(), "INSERT INTO retry_fault VALUES ($1, $2)", req.OrderID, scenario.operation); err != nil {
				t.Fatal(err)
			}
			f.write(t, topic, payload)
			stop := runRetryConsumer(t, c)
			awaitRetry(t, "successful gateway charge followed by result rollback and retry", func() bool {
				return logs.count("failed to record payment result") >= 2
			})
			payment, err := f.store.GetByRequestEventID(context.Background(), req.EventID)
			if err != nil || payment.Status != domain.StatusRequested {
				t.Fatalf("failed result must remain REQUESTED: %+v error=%v", payment, err)
			}
			p.assertIdentity(t, payment.ID, 2)
			var charges, results int
			if err := f.sql.QueryRow(context.Background(), `
				SELECT (SELECT count(*) FROM gateway_charges WHERE idempotency_key=$1),
				       (SELECT count(*) FROM payment_outbox WHERE payment_id=$1)
			`, payment.ID).Scan(&charges, &results); err != nil || charges != 1 || results != 0 {
				t.Fatalf("failure window: charges=%d outbox=%d error=%v", charges, results, err)
			}
			if scenario.restart {
				stop()
				_ = c.Close()
			}
			if _, err := f.sql.Exec(context.Background(), "DELETE FROM retry_fault WHERE order_id=$1", req.OrderID); err != nil {
				t.Fatal(err)
			}
			if scenario.restart {
				p = newLedgerProvider(t, f)
				next := f.resumeConsumer(t, topic, group, p, logs)
				next.store = reopenPaymentStore(t, f)
				runRetryConsumer(t, next)
			}
			awaitRetry(t, "original record converges without republishing", func() bool { return f.offset(t, topic, group) == 1 })
			f.write(t, topic, payload, payload)
			awaitRetry(t, "duplicate Kafka deliveries acknowledged", func() bool { return f.offset(t, topic, group) == 3 })
			key := assertOneChargeAndResult(t, f, req.EventID)
			p.assertIdentity(t, key, 1)
		})
	}

	t.Run("uncertain_gateway_response", func(t *testing.T) {
		p := newLedgerProvider(t, f)
		p.ambiguousOnce.Store(true)
		c, topic, group := f.consumer(t, p, &retryLogs{})
		req, payload := requestedBytes(t, 5002)
		f.write(t, topic, payload)
		runRetryConsumer(t, c)
		awaitRetry(t, "lost gateway response converges", func() bool { return f.offset(t, topic, group) == 1 })
		p.assertIdentity(t, assertOneChargeAndResult(t, f, req.EventID), 2)
		var progress int
		if err := f.sql.QueryRow(context.Background(), `SELECT count(*) FROM payment_outbox
			WHERE topic=$1 AND payment_id=(SELECT id FROM payments WHERE request_event_id=$2)`,
			events.PaymentUnresolved, req.EventID).Scan(&progress); err != nil || progress != 1 {
			t.Fatalf("uncertainty must emit exactly one nonterminal result: progress=%d error=%v", progress, err)
		}
	})

	t.Run("offset_failure_then_redelivery", func(t *testing.T) {
		p, logs := newLedgerProvider(t, f), &retryLogs{}
		c, topic, group := f.consumer(t, p, logs)
		reader := &failingCommitReader{requestReader: c.reader}
		reader.fail.Store(true)
		c.reader = reader
		req, payload := requestedBytes(t, 5003)
		f.write(t, topic, payload)
		stop := runRetryConsumer(t, c)
		awaitRetry(t, "offset failure after durable result", func() bool { return reader.attempts.Load() >= 2 })
		key := assertOneChargeAndResult(t, f, req.EventID)
		p.assertIdentity(t, key, 1)
		stop()
		_ = c.Close()
		nextProvider := newLedgerProvider(t, f)
		next := f.resumeConsumer(t, topic, group, nextProvider, logs)
		next.store = reopenPaymentStore(t, f)
		runRetryConsumer(t, next)
		awaitRetry(t, "terminal redelivery acknowledged", func() bool { return f.offset(t, topic, group) == 1 })
		assertOneChargeAndResult(t, f, req.EventID)
		nextProvider.mu.Lock()
		defer nextProvider.mu.Unlock()
		if len(nextProvider.keys) != 0 {
			t.Fatal("terminal redelivery called provider again")
		}
	})

	t.Run("concurrent_duplicates", func(t *testing.T) {
		p := newLedgerProvider(t, f)
		p.concurrentGate = make(chan struct{})
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		c, _, _ := f.consumer(t, p, &retryLogs{})
		req, payload := requestedBytes(t, 5004)
		const attempts = 6
		done := make(chan outcome, attempts)
		for i := 0; i < attempts; i++ {
			go func() { done <- c.handle(ctx, kafkago.Message{Value: payload}) }()
		}
		awaitRetry(t, "all concurrent provider attempts reach the durable gateway", func() bool {
			p.mu.Lock()
			defer p.mu.Unlock()
			return len(p.keys) == attempts
		})
		close(p.concurrentGate)
		for i := 0; i < attempts; i++ {
			if result := <-done; result != outcomeCommit {
				t.Fatalf("concurrent handling returned %v", result)
			}
		}
		p.assertIdentity(t, assertOneChargeAndResult(t, f, req.EventID), attempts)
	})
}
