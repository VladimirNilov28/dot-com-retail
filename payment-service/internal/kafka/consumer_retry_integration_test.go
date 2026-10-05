package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	kafkago "github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/protocol"
	"github.com/testcontainers/testcontainers-go"
	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

type retryFixture struct {
	brokers []string
	store   *store.Store
	sql     *pgx.Conn
}

func newRetryFixture(t *testing.T) *retryFixture {
	t.Helper()
	ctx := context.Background()
	pg, err := tcpostgres.Run(ctx, "postgres:17",
		tcpostgres.WithDatabase("payments"), tcpostgres.WithUsername("test"), tcpostgres.WithPassword("test"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = testcontainers.TerminateContainer(pg) })
	dsn, err := pg.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	db, err := store.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err := db.Migrate(dsn); err != nil {
		t.Fatal(err)
	}
	sql, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sql.Close(ctx) })
	// A switchable, order-specific fault leaves the real pool and all other
	// requests healthy. Both INSERT and result UPDATE failures are exercised.
	_, err = sql.Exec(ctx, `
		CREATE TABLE retry_fault (order_id bigint, operation text);
		CREATE FUNCTION inject_retry_fault() RETURNS trigger LANGUAGE plpgsql AS $$
		BEGIN
			IF EXISTS (SELECT 1 FROM retry_fault WHERE order_id = NEW.order_id AND operation = TG_OP) THEN
				RAISE EXCEPTION 'controlled transient payment failure';
			END IF;
			RETURN NEW;
		END $$;
		CREATE TRIGGER retry_fault BEFORE INSERT OR UPDATE ON payments
		FOR EACH ROW EXECUTE FUNCTION inject_retry_fault();
	`)
	if err != nil {
		t.Fatal(err)
	}
	broker, err := tckafka.Run(ctx, "confluentinc/confluent-local:7.6.1")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = testcontainers.TerminateContainer(broker) })
	brokers, err := broker.Brokers(ctx)
	if err != nil {
		t.Fatal(err)
	}
	return &retryFixture{brokers: brokers, store: db, sql: sql}
}

func (f *retryFixture) consumer(t *testing.T, p provider.Provider, logs *retryLogs) (*RequestConsumer, string, string) {
	t.Helper()
	topic, group := "retry-"+uuid.NewString(), "retry-"+uuid.NewString()
	conn, err := kafkago.Dial("tcp", f.brokers[0])
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err := conn.CreateTopics(
		kafkago.TopicConfig{Topic: topic, NumPartitions: 1, ReplicationFactor: 1},
		kafkago.TopicConfig{Topic: topic + DLQSuffix, NumPartitions: 1, ReplicationFactor: 1},
	); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{topic, topic + DLQSuffix} {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		leader, err := kafkago.DialLeader(ctx, "tcp", f.brokers[0], name, 0)
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		_ = leader.Close()
	}
	c := NewRequestConsumer(f.brokers, group, f.store, p, slog.New(slog.NewTextHandler(logs, nil)))
	_ = c.reader.Close()
	_ = c.dlqWriter.Close()
	c.reader = kafkago.NewReader(kafkago.ReaderConfig{
		Brokers: f.brokers, Topic: topic, GroupID: group, MinBytes: 1, MaxBytes: 10e6,
		StartOffset: kafkago.FirstOffset,
	})
	c.dlqWriter = &kafkago.Writer{
		Addr: kafkago.TCP(f.brokers...), Topic: topic + DLQSuffix,
		MaxAttempts: 1, BatchTimeout: time.Millisecond, WriteTimeout: time.Second,
		RequiredAcks: kafkago.RequireAll,
	}
	t.Cleanup(func() { _ = c.Close() })
	return c, topic, group
}

func runRetryConsumer(t *testing.T, c *RequestConsumer) func() {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- c.Run(ctx) }()
	var once sync.Once
	stop := func() {
		once.Do(func() {
			cancel()
			select {
			case err := <-done:
				if err != nil {
					t.Errorf("Run on cancellation: %v", err)
				}

			case <-time.After(3 * time.Second):
				t.Error("consumer did not stop on cancellation")
			}
		})
	}
	t.Cleanup(stop)
	return stop
}

func (f *retryFixture) resumeConsumer(t *testing.T, topic, group string, p provider.Provider, logs *retryLogs) *RequestConsumer {
	t.Helper()
	c := NewRequestConsumer(f.brokers, group, f.store, p, slog.New(slog.NewTextHandler(logs, nil)))
	_ = c.reader.Close()
	c.reader = kafkago.NewReader(kafkago.ReaderConfig{
		Brokers: f.brokers, Topic: topic, GroupID: group, MinBytes: 1, MaxBytes: 10e6,
		StartOffset: kafkago.FirstOffset,
	})
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func (f *retryFixture) write(t *testing.T, topic string, values ...[]byte) {
	t.Helper()
	transport := &kafkago.Transport{}
	defer transport.CloseIdleConnections()
	w := &kafkago.Writer{
		Addr: kafkago.TCP(f.brokers...), Topic: topic, BatchTimeout: time.Millisecond,
		Transport: transport, RequiredAcks: kafkago.RequireAll,
	}
	defer w.Close()
	msgs := make([]kafkago.Message, len(values))
	for i, value := range values {
		msgs[i].Value = value
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := w.WriteMessages(ctx, msgs...); err != nil {
		t.Fatal(err)
	}
}

func requestedBytes(t *testing.T, orderID int64) (events.PaymentRequestedEvent, []byte) {
	t.Helper()
	userID := int64(1)
	req := events.PaymentRequestedEvent{
		EventID: uuid.New(), OrderID: orderID, UserID: &userID,
		Amount: json.Number("20.00"), Currency: "EUR", OccurredAt: eventtime.Now(),
	}
	b, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	return req, b
}

func (f *retryFixture) offset(t *testing.T, topic, group string) int64 {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	client := &kafkago.Client{Addr: kafkago.TCP(f.brokers...)}
	res, err := client.OffsetFetch(ctx, &kafkago.OffsetFetchRequest{
		GroupID: group, Topics: map[string][]int{topic: {0}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.Error != nil {
		t.Fatal(res.Error)
	}
	partitions := res.Topics[topic]
	if len(partitions) != 1 || partitions[0].Error != nil {
		t.Fatalf("unexpected offset response: %+v", res)
	}
	return partitions[0].CommittedOffset
}

func awaitRetry(t *testing.T, description string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("timed out: %s", description)
}

type retryLogs struct {
	mu   sync.Mutex
	text string
}

func (l *retryLogs) Write(b []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.text += string(b)
	return len(b), nil
}

func (l *retryLogs) count(s string) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Count(l.text, s)
}

type failingDLQTransport struct {
	fail     atomic.Bool
	attempts atomic.Int32
	base     kafkago.Transport
}

func (f *failingDLQTransport) RoundTrip(ctx context.Context, addr net.Addr, req kafkago.Request) (kafkago.Response, error) {
	if req.ApiKey() == protocol.Produce && f.fail.Load() {
		f.attempts.Add(1)
		return nil, errors.New("controlled DLQ write failure")
	}
	return f.base.RoundTrip(ctx, addr, req)
}

type idempotentRetryProvider struct {
	mu       sync.Mutex
	payments map[string]bool
	attempts int
	fail     atomic.Bool
}

func (p *idempotentRetryProvider) ChargeIdempotently(ctx context.Context, paymentID uuid.UUID, _ provider.ChargeRequest) (provider.ChargeResult, error) {
	if err := ctx.Err(); err != nil {
		return provider.ChargeResult{}, err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.attempts++
	if p.fail.Load() {
		return provider.ChargeResult{}, errors.New("controlled provider failure")
	}
	if p.payments == nil {
		p.payments = make(map[string]bool)
	}
	p.payments[paymentID.String()] = true
	return provider.ChargeResult{Approved: true}, nil
}

type failingCommitReader struct {
	requestReader
	fail     atomic.Bool
	attempts atomic.Int32
}

type blockingRetryProvider struct{ entered chan struct{} }

func (p *blockingRetryProvider) ChargeIdempotently(ctx context.Context, _ uuid.UUID, _ provider.ChargeRequest) (provider.ChargeResult, error) {
	close(p.entered)
	<-ctx.Done()
	return provider.ChargeResult{}, ctx.Err()
}

type blockingDLQTransport struct {
	entered chan struct{}
	once    sync.Once
	base    kafkago.Transport
}

func (p *blockingDLQTransport) RoundTrip(ctx context.Context, addr net.Addr, req kafkago.Request) (kafkago.Response, error) {
	if req.ApiKey() == protocol.Produce {
		p.once.Do(func() { close(p.entered) })
		<-ctx.Done()
		return nil, ctx.Err()
	}
	return p.base.RoundTrip(ctx, addr, req)
}

func (r *failingCommitReader) CommitMessages(ctx context.Context, msgs ...kafkago.Message) error {
	r.attempts.Add(1)
	if r.fail.Load() && len(msgs) > 0 && msgs[0].Offset == 0 {
		return errors.New("controlled offset commit failure")
	}
	return r.requestReader.CommitMessages(ctx, msgs...)
}

func TestRequestConsumerOffsetSafety(t *testing.T) {
	f := newRetryFixture(t)
	for i, operation := range []string{"INSERT", "UPDATE"} {
		t.Run("same_partition_"+operation, func(t *testing.T) {
			logs := &retryLogs{}
			c, topic, group := f.consumer(t, provider.NewFakeProvider(), logs)
			a, aBytes := requestedBytes(t, int64(1000+i*2))
			b, bBytes := requestedBytes(t, a.OrderID+1)
			if _, err := f.sql.Exec(context.Background(), "INSERT INTO retry_fault VALUES ($1, $2)", a.OrderID, operation); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				_, _ = f.sql.Exec(context.Background(), "DELETE FROM retry_fault WHERE order_id=$1", a.OrderID)
			})
			f.write(t, topic, aBytes, bBytes)
			runRetryConsumer(t, c)
			awaitRetry(t, "A failure", func() bool { return logs.count("will retry") > 0 })
			time.Sleep(1500 * time.Millisecond)
			if offset := f.offset(t, topic, group); offset >= 0 {
				t.Errorf("committed offset %d past unresolved A at offset 0 (B is offset 1)", offset)
			}
			if _, err := f.store.GetByRequestEventID(context.Background(), b.EventID); err == nil {
				t.Error("B was processed while same-partition A remained unresolved")
			}
			if logs.count("will retry") < 2 {
				t.Error("persistent A failure was not retried/logged by the running consumer")
			}
			if _, err := f.sql.Exec(context.Background(), "DELETE FROM retry_fault WHERE order_id=$1", a.OrderID); err != nil {
				t.Fatal(err)
			}
			awaitRetry(t, "A and B recover without restart", func() bool {
				pa, ea := f.store.GetByRequestEventID(context.Background(), a.EventID)
				pb, eb := f.store.GetByRequestEventID(context.Background(), b.EventID)
				return ea == nil && eb == nil && pa.Status == domain.StatusSucceeded && pb.Status == domain.StatusSucceeded
			})
			awaitRetry(t, "both offsets durably acknowledged", func() bool { return f.offset(t, topic, group) == 2 })
		})
	}

	t.Run("provider_recovers_without_new_record", func(t *testing.T) {
		logs, p := &retryLogs{}, &idempotentRetryProvider{}
		p.fail.Store(true)
		c, topic, group := f.consumer(t, p, logs)
		req, payload := requestedBytes(t, 2500)
		f.write(t, topic, payload)
		runRetryConsumer(t, c)
		awaitRetry(t, "persistent provider failure", func() bool {
			return logs.count("provider charge attempt failed") >= 2
		})
		if offset := f.offset(t, topic, group); offset >= 0 {
			t.Fatalf("provider failure acknowledged: %d", offset)
		}
		p.fail.Store(false)
		awaitRetry(t, "provider recovers without restart or another message", func() bool {
			return f.offset(t, topic, group) == 1
		})
		payment, err := f.store.GetByRequestEventID(context.Background(), req.EventID)
		if err != nil || payment.Status != domain.StatusSucceeded {
			t.Fatalf("recovered payment status=%s error=%v", payment.Status, err)
		}
	})

	for i, restart := range []bool{false, true} {
		t.Run(fmt.Sprintf("commit_failure_restart_%t", restart), func(t *testing.T) {
			logs, p := &retryLogs{}, &idempotentRetryProvider{}
			c, topic, group := f.consumer(t, p, logs)
			reader := &failingCommitReader{requestReader: c.reader}
			reader.fail.Store(true)
			c.reader = reader
			a, aBytes := requestedBytes(t, int64(2600+i*2))
			_, bBytes := requestedBytes(t, a.OrderID+1)
			f.write(t, topic, aBytes, bBytes)
			stop := runRetryConsumer(t, c)
			awaitRetry(t, "first commit failure", func() bool { return reader.attempts.Load() >= 1 })
			time.Sleep(1200 * time.Millisecond)
			if attempts := reader.attempts.Load(); attempts < 3 || attempts > 4 {
				t.Errorf("commit retry attempts=%d, want 3-4 bounded attempts on A", attempts)
			}
			if offset := f.offset(t, topic, group); offset >= 0 {
				t.Fatalf("failed acknowledgement skipped: offset=%d", offset)
			}
			p.mu.Lock()
			attempts := p.attempts
			p.mu.Unlock()
			if attempts != 1 {
				t.Errorf("commit retries rehandled A or fetched B: provider calls=%d", attempts)
			}
			if logs.count("failed to commit message offset") < 3 {
				t.Error("persistent commit failures were not logged")
			}
			if restart {
				stop()
				_ = c.Close()
				next := f.resumeConsumer(t, topic, group, p, logs)
				runRetryConsumer(t, next)
			} else {
				reader.fail.Store(false)
			}
			awaitRetry(t, "commit recovery without losing either record", func() bool {
				return f.offset(t, topic, group) == 2
			})
			p.mu.Lock()
			defer p.mu.Unlock()
			if p.attempts != 2 || len(p.payments) != 2 {
				t.Fatalf("terminal payment recharged on redelivery: calls=%d distinct keys=%d", p.attempts, len(p.payments))
			}
			var count int
			if err := f.sql.QueryRow(context.Background(),
				"SELECT count(*) FROM payment_outbox WHERE payment_id=(SELECT id FROM payments WHERE request_event_id=$1)", a.EventID).Scan(&count); err != nil {
				t.Fatal(err)
			}
			if count != 1 {
				t.Fatalf("A result duplicated: outbox rows=%d", count)
			}
		})
	}

	for _, dependency := range []string{"provider", "DLQ"} {
		t.Run("shutdown_during_"+dependency, func(t *testing.T) {
			entered := make(chan struct{})
			var p provider.Provider = provider.NewFakeProvider()
			if dependency == "provider" {
				p = &blockingRetryProvider{entered: entered}
			}
			c, topic, group := f.consumer(t, p, &retryLogs{})
			_, payload := requestedBytes(t, 2800)
			if dependency == "DLQ" {
				transport := &blockingDLQTransport{entered: entered}
				t.Cleanup(transport.base.CloseIdleConnections)
				c.dlqWriter.Transport = transport
				c.dlqWriter.WriteTimeout = 200 * time.Millisecond
				payload = []byte("not-json")
			}
			f.write(t, topic, payload)
			stop := runRetryConsumer(t, c)
			select {
			case <-entered:
			case <-time.After(15 * time.Second):
				t.Fatal("dependency operation never started")
			}
			stop()
			if offset := f.offset(t, topic, group); offset >= 0 {
				t.Fatalf("cancelled dependency call acknowledged: offset=%d", offset)
			}
		})
	}

	for _, invalid := range []struct{ name, payload string }{
		{"malformed", "not-json"}, {"missing_fields", "{}"},
		{"invalid_amount", fmt.Sprintf(`{"eventId":%q,"orderId":2000,"amount":"not-money"}`, uuid.NewString())},
	} {
		t.Run("DLQ_"+invalid.name, func(t *testing.T) {
			logs := &retryLogs{}
			c, topic, group := f.consumer(t, provider.NewFakeProvider(), logs)
			transport := &failingDLQTransport{}
			t.Cleanup(transport.base.CloseIdleConnections)
			transport.fail.Store(true)
			c.dlqWriter.Transport = transport
			_, b := requestedBytes(t, 2001)
			f.write(t, topic, []byte(invalid.payload), b)
			runRetryConsumer(t, c)
			awaitRetry(t, "DLQ write failure", func() bool { return transport.attempts.Load() > 0 })
			time.Sleep(1500 * time.Millisecond)
			if offset := f.offset(t, topic, group); offset >= 0 {
				t.Errorf("failed DLQ record acknowledged: committed offset=%d", offset)
			}
			if logs.count("failed to publish message to DLQ") < 2 {
				t.Error("failed DLQ write was not retried/logged")
			}
			transport.fail.Store(false)
			awaitRetry(t, "DLQ recovery and subsequent valid request", func() bool { return f.offset(t, topic, group) == 2 })
			reader := kafkago.NewReader(kafkago.ReaderConfig{Brokers: f.brokers, Topic: topic + DLQSuffix, Partition: 0, MinBytes: 1, MaxBytes: 10e6})
			defer reader.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			msg, err := reader.FetchMessage(ctx)
			if err != nil || string(msg.Value) != invalid.payload {
				t.Fatalf("durable DLQ copy missing or changed: message=%q error=%v", msg.Value, err)
			}
		})
	}

	t.Run("restart_redelivery_idempotency", func(t *testing.T) {
		logs, p := &retryLogs{}, &idempotentRetryProvider{}
		c, topic, group := f.consumer(t, p, logs)
		req, payload := requestedBytes(t, 3000)
		if _, err := f.sql.Exec(context.Background(), "INSERT INTO retry_fault VALUES ($1, 'UPDATE')", req.OrderID); err != nil {
			t.Fatal(err)
		}
		f.write(t, topic, payload)
		stop := runRetryConsumer(t, c)
		awaitRetry(t, "charged but result not durable", func() bool { return logs.count("failed to record payment result") > 0 })
		stop()
		_ = c.Close()
		if offset := f.offset(t, topic, group); offset >= 0 {
			t.Fatalf("unresolved request acknowledged before restart: %d", offset)
		}
		if _, err := f.sql.Exec(context.Background(), "DELETE FROM retry_fault WHERE order_id=$1", req.OrderID); err != nil {
			t.Fatal(err)
		}
		restarted := f.resumeConsumer(t, topic, group, p, logs)
		runRetryConsumer(t, restarted)
		// No republish: the original unacknowledged record must be redelivered.
		awaitRetry(t, "original record redelivery after restart", func() bool { return f.offset(t, topic, group) == 1 })
		f.write(t, topic, payload)
		awaitRetry(t, "duplicate acknowledged", func() bool { return f.offset(t, topic, group) == 2 })
		var count int
		if err := f.sql.QueryRow(context.Background(), "SELECT count(*) FROM payment_outbox WHERE payment_id=(SELECT id FROM payments WHERE request_event_id=$1)", req.EventID).Scan(&count); err != nil {
			t.Fatal(err)
		}
		p.mu.Lock()
		defer p.mu.Unlock()
		if count != 1 || len(p.payments) != 1 || p.attempts != 2 {
			t.Fatalf("idempotency: outbox=%d distinct provider keys=%d attempts=%d", count, len(p.payments), p.attempts)
		}
	})
}
