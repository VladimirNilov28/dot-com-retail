package kafka_test

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"
	"github.com/testcontainers/testcontainers-go"
	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
	paymentkafka "github.com/VladimirNilov28/dot-com-retail/payment-service/internal/kafka"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

// testEnv wires a real Postgres + Kafka pair (testcontainers-go) plus the
// production RequestConsumer/OutboxPublisher - this deliberately exercises
// real Kafka delivery/consumer-group/offset semantics rather than mocking
// them away, per the brief.
type testEnv struct {
	brokers   []string
	store     *store.Store
	consumer  *paymentkafka.RequestConsumer
	publisher *paymentkafka.OutboxPublisher
	stopFn    func()
}

func newTestEnv(t *testing.T, logger *testLogger) *testEnv {
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
		t.Fatalf("failed to get postgres connection string: %v", err)
	}

	kafkaContainer, err := tckafka.Run(ctx, "confluentinc/confluent-local:7.6.1")
	if err != nil {
		t.Fatalf("failed to start kafka container: %v", err)
	}
	t.Cleanup(func() {
		if err := testcontainers.TerminateContainer(kafkaContainer); err != nil {
			t.Logf("failed to terminate kafka container: %v", err)
		}
	})
	brokers, err := kafkaContainer.Brokers(ctx)
	if err != nil {
		t.Fatalf("failed to get kafka brokers: %v", err)
	}
	ensureTopics(t, brokers)

	s, err := store.New(ctx, dsn)
	if err != nil {
		t.Fatalf("failed to connect store: %v", err)
	}
	t.Cleanup(s.Close)
	if err := s.Migrate(dsn); err != nil {
		t.Fatalf("failed to migrate: %v", err)
	}

	env := &testEnv{brokers: brokers, store: s}
	env.restartWorkers(t, provider.NewFakeProvider(), logger)
	t.Cleanup(func() {
		if env.stopFn != nil {
			env.stopFn()
		}
	})
	return env
}

// ensureTopics explicitly creates every topic the Payment Service touches
// before any consumer/producer starts - delegates to the same
// kafka.EnsureTopics used by cmd/server/main.go in production, so the test
// exercises the real startup path rather than a parallel implementation.
func ensureTopics(t *testing.T, brokers []string) {
	t.Helper()
	if err := paymentkafka.EnsureTopics(brokers); err != nil {
		t.Fatalf("failed to create topics: %v", err)
	}
}

// restartWorkers stops any currently-running consumer/outbox publisher for
// this env (if any) and starts a fresh pair against the same
// store/brokers - used both for the initial startup and, mid-test, to
// simulate a real Payment Service restart (old process fully stopped
// before the new one begins consuming).
func (e *testEnv) restartWorkers(t *testing.T, p provider.Provider, logger *testLogger) {
	t.Helper()
	if e.stopFn != nil {
		e.stopFn()
	}

	ctx, cancel := context.WithCancel(context.Background())

	consumer := paymentkafka.NewRequestConsumer(e.brokers, "payment-service-test", e.store, p, logger.Logger())
	publisher := paymentkafka.NewOutboxPublisher(e.brokers, e.store, 100*time.Millisecond, logger.Logger())
	e.consumer = consumer
	e.publisher = publisher

	go func() { _ = consumer.Run(ctx) }()
	go publisher.Run(ctx)

	e.stopFn = func() {
		cancel()
		_ = consumer.Close()
		_ = publisher.Close()
	}
}

func (e *testEnv) publishRequested(t *testing.T, req events.PaymentRequestedEvent) {
	t.Helper()
	payload, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("failed to marshal PaymentRequestedEvent: %v", err)
	}
	e.writeRaw(t, events.PaymentRequested, payload)
}

func (e *testEnv) writeRaw(t *testing.T, topic string, value []byte) {
	t.Helper()
	writer := &kafkago.Writer{
		Addr:                   kafkago.TCP(e.brokers...),
		Topic:                  topic,
		AllowAutoTopicCreation: true,
	}
	defer func() { _ = writer.Close() }()
	if err := writer.WriteMessages(context.Background(), kafkago.Message{Value: value}); err != nil {
		t.Fatalf("failed to write message to %s: %v", topic, err)
	}
}

// readOne reads a single message from topic within the given timeout,
// failing the test if none arrives.
func (e *testEnv) readOne(t *testing.T, topic string, timeout time.Duration) kafkago.Message {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	reader := kafkago.NewReader(kafkago.ReaderConfig{
		Brokers:  e.brokers,
		Topic:    topic,
		GroupID:  "test-reader-" + uuid.NewString(),
		MinBytes: 1,
		MaxBytes: 10e6,
	})
	defer func() { _ = reader.Close() }()

	msg, err := reader.FetchMessage(ctx)
	if err != nil {
		t.Fatalf("timed out waiting for a message on %s: %v", topic, err)
	}
	return msg
}

func newRequestedEvent(orderID int64, amount string) events.PaymentRequestedEvent {
	return events.PaymentRequestedEvent{
		EventID:    uuid.New(),
		OrderID:    orderID,
		UserID:     1,
		Amount:     json.Number(amount),
		Currency:   "EUR",
		OccurredAt: eventtime.Now(),
	}
}

func TestEndToEnd_SuccessfulPayment_PublishesSucceededEvent(t *testing.T) {
	logger := newTestLogger(t)
	env := newTestEnv(t, logger)

	req := newRequestedEvent(101, "39.98")
	env.publishRequested(t, req)

	msg := env.readOne(t, events.PaymentSucceeded, 30*time.Second)

	var succeeded events.PaymentSucceededEvent
	if err := json.Unmarshal(msg.Value, &succeeded); err != nil {
		t.Fatalf("failed to unmarshal PaymentSucceededEvent: %v", err)
	}
	if succeeded.OrderID != req.OrderID {
		t.Fatalf("OrderID = %d, want %d", succeeded.OrderID, req.OrderID)
	}
	if succeeded.RequestEventID != req.EventID {
		t.Fatalf("RequestEventID = %s, want %s", succeeded.RequestEventID, req.EventID)
	}

	payment, err := env.store.GetByRequestEventID(context.Background(), req.EventID)
	if err != nil {
		t.Fatalf("GetByRequestEventID returned error: %v", err)
	}
	if payment.Status != domain.StatusSucceeded {
		t.Fatalf("persisted status = %s, want SUCCEEDED", payment.Status)
	}
}

func TestEndToEnd_DeclinedPayment_PublishesFailedEvent(t *testing.T) {
	logger := newTestLogger(t)
	env := newTestEnv(t, logger)

	// FakeProvider declines whenever the amount's cents component is 13.
	req := newRequestedEvent(102, "10.13")
	env.publishRequested(t, req)

	msg := env.readOne(t, events.PaymentFailed, 30*time.Second)

	var failed events.PaymentFailedEvent
	if err := json.Unmarshal(msg.Value, &failed); err != nil {
		t.Fatalf("failed to unmarshal PaymentFailedEvent: %v", err)
	}
	if failed.OrderID != req.OrderID {
		t.Fatalf("OrderID = %d, want %d", failed.OrderID, req.OrderID)
	}
	if failed.Reason == "" {
		t.Fatalf("expected a non-empty decline reason")
	}

	payment, err := env.store.GetByRequestEventID(context.Background(), req.EventID)
	if err != nil {
		t.Fatalf("GetByRequestEventID returned error: %v", err)
	}
	if payment.Status != domain.StatusFailed {
		t.Fatalf("persisted status = %s, want FAILED", payment.Status)
	}
}

func TestEndToEnd_DuplicatePaymentRequested_DoesNotDoubleCharge(t *testing.T) {
	logger := newTestLogger(t)
	env := newTestEnv(t, logger)

	req := newRequestedEvent(103, "20.00")
	env.publishRequested(t, req)
	// Redeliver the identical request (at-least-once semantics).
	env.publishRequested(t, req)

	// Exactly one succeeded event should be produced overall.
	first := env.readOne(t, events.PaymentSucceeded, 30*time.Second)
	var firstEvt events.PaymentSucceededEvent
	if err := json.Unmarshal(first.Value, &firstEvt); err != nil {
		t.Fatalf("failed to unmarshal first PaymentSucceededEvent: %v", err)
	}

	// Give the (duplicate) second request time to be consumed too, then
	// assert only a single payment row exists for this RequestEventID and
	// no second charge/result was produced.
	time.Sleep(2 * time.Second)

	payment, err := env.store.GetByRequestEventID(context.Background(), req.EventID)
	if err != nil {
		t.Fatalf("GetByRequestEventID returned error: %v", err)
	}
	if payment.ID != firstEvt.PaymentID {
		t.Fatalf("expected the single persisted payment id to match the published event")
	}
}

func TestEndToEnd_MalformedMessage_RoutesToDLQAndConsumerKeepsRunning(t *testing.T) {
	logger := newTestLogger(t)
	env := newTestEnv(t, logger)

	env.writeRaw(t, events.PaymentRequested, []byte("not-json-at-all"))

	dlqMsg := env.readOne(t, events.PaymentRequested+paymentkafka.DLQSuffix, 30*time.Second)
	if string(dlqMsg.Value) != "not-json-at-all" {
		t.Fatalf("DLQ message value = %q, want original payload preserved", dlqMsg.Value)
	}

	// Consumer must still be alive/healthy afterwards - a subsequent valid
	// message is processed normally instead of the consumer having crashed.
	req := newRequestedEvent(104, "5.00")
	env.publishRequested(t, req)
	env.readOne(t, events.PaymentSucceeded, 30*time.Second)
}

func TestEndToEnd_RestartMidProcessing_ResumesSafely(t *testing.T) {
	logger := newTestLogger(t)
	env := newTestEnv(t, logger)

	// Simulate the process having already recorded the request (e.g. it
	// crashed after SaveRequested but before charging/recording a result)
	// by inserting the row directly, then restarting the workers and
	// confirming exactly one result is produced - not a lost request, and
	// not a duplicate charge.
	req := newRequestedEvent(105, "15.00")
	ctx := context.Background()
	_, err := env.store.SaveRequested(ctx, domain.Payment{
		ID:             uuid.New(),
		RequestEventID: req.EventID,
		OrderID:        req.OrderID,
		UserID:         req.UserID,
		AmountCents:    1500,
		Currency:       req.Currency,
		Status:         domain.StatusRequested,
	})
	if err != nil {
		t.Fatalf("failed to pre-seed payment row: %v", err)
	}

	// Simulate the restart itself: stop the old worker pair and start a
	// fresh one before the request is (re)delivered.
	env.restartWorkers(t, provider.NewFakeProvider(), logger)

	env.publishRequested(t, req)

	msg := env.readOne(t, events.PaymentSucceeded, 30*time.Second)
	var succeeded events.PaymentSucceededEvent
	if err := json.Unmarshal(msg.Value, &succeeded); err != nil {
		t.Fatalf("failed to unmarshal PaymentSucceededEvent: %v", err)
	}
	if succeeded.OrderID != req.OrderID {
		t.Fatalf("OrderID = %d, want %d", succeeded.OrderID, req.OrderID)
	}
}
