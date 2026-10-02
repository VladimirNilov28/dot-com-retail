// Package kafka contains the Payment Service's Kafka consumer (processes
// payment.requested) and outbox publisher (publishes payment.succeeded /
// payment.failed). Both are built on segmentio/kafka-go.
package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/money"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

// DLQSuffix is appended to a topic name to build its dead-letter topic, e.g.
// "payment.requested" -> "payment.requested.dlq". A message only ever goes
// here when it is permanently unprocessable (malformed JSON, missing
// required fields) - never for a transient DB/broker failure, which instead
// is retried via non-commit + redelivery.
const DLQSuffix = ".dlq"

// transientRetryDelay bounds how fast the consumer loop can spin when a
// transient dependency (DB, broker) is unavailable, so a Payment Service
// restart or brief Postgres blip doesn't turn into a tight CPU-burning
// retry loop.
const transientRetryDelay = 500 * time.Millisecond

// RequestConsumer consumes payment.requested, idempotently records the
// request, charges via the configured Provider, and durably records the
// result via the transactional outbox (never publishing to Kafka directly
// from here).
type RequestConsumer struct {
	reader    *kafkago.Reader
	dlqWriter *kafkago.Writer
	store     *store.Store
	provider  provider.Provider
	logger    *slog.Logger
}

// NewRequestConsumer builds a RequestConsumer subscribed to
// events.PaymentRequested under the given consumer group.
func NewRequestConsumer(
	brokers []string,
	groupID string,
	s *store.Store,
	p provider.Provider,
	logger *slog.Logger,
) *RequestConsumer {
	reader := kafkago.NewReader(kafkago.ReaderConfig{
		Brokers:     brokers,
		GroupID:     groupID,
		Topic:       events.PaymentRequested,
		MinBytes:    1,
		MaxBytes:    10e6,
		StartOffset: kafkago.FirstOffset,
	})
	dlqWriter := &kafkago.Writer{
		Addr:                   kafkago.TCP(brokers...),
		Topic:                  events.PaymentRequested + DLQSuffix,
		Balancer:               &kafkago.LeastBytes{},
		AllowAutoTopicCreation: true,
	}
	return &RequestConsumer{reader: reader, dlqWriter: dlqWriter, store: s, provider: p, logger: logger}
}

// Close releases the underlying Kafka reader/writer.
func (c *RequestConsumer) Close() error {
	err1 := c.reader.Close()
	err2 := c.dlqWriter.Close()
	return errors.Join(err1, err2)
}

// Run consumes messages until ctx is cancelled. It never returns a non-nil
// error for message-level problems (those are logged/DLQ'd/retried
// internally) - only for a fatal reader-level failure.
func (c *RequestConsumer) Run(ctx context.Context) error {
	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return fmt.Errorf("kafka: fetch message: %w", err)
		}

		outcome := c.handle(ctx, msg)
		switch outcome {
		case outcomeCommit:
			if err := c.reader.CommitMessages(ctx, msg); err != nil {
				c.logger.Error("failed to commit message offset", "error", err)
			}
		case outcomeRetry:
			// Deliberately do not commit: the same message will be
			// redelivered (at-least-once). Bounded backoff avoids a tight
			// retry loop against an unavailable dependency.
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(transientRetryDelay):
			}
		}
	}
}

type outcome int

const (
	outcomeCommit outcome = iota
	outcomeRetry
)

func (c *RequestConsumer) handle(ctx context.Context, msg kafkago.Message) outcome {
	var req events.PaymentRequestedEvent
	if err := json.Unmarshal(msg.Value, &req); err != nil {
		c.logger.Warn("malformed payment.requested message, routing to DLQ",
			"error", err, "offset", msg.Offset, "partition", msg.Partition)
		c.sendToDLQ(ctx, msg, "malformed_json: "+err.Error())
		return outcomeCommit
	}
	if req.EventID == uuid.Nil || req.OrderID == 0 {
		c.logger.Warn("invalid payment.requested message (missing eventId/orderId), routing to DLQ",
			"offset", msg.Offset, "partition", msg.Partition)
		c.sendToDLQ(ctx, msg, "missing_required_fields")
		return outcomeCommit
	}

	amountCents, err := money.ParseCents(req.Amount.String())
	if err != nil {
		c.logger.Warn("invalid amount in payment.requested message, routing to DLQ",
			"error", err, "eventId", req.EventID, "orderId", req.OrderID)
		c.sendToDLQ(ctx, msg, "invalid_amount: "+err.Error())
		return outcomeCommit
	}

	logger := c.logger.With("eventId", req.EventID, "orderId", req.OrderID)

	saveResult, err := c.store.SaveRequested(ctx, domain.Payment{
		ID:             uuid.New(),
		RequestEventID: req.EventID,
		OrderID:        req.OrderID,
		UserID:         req.UserID,
		AmountCents:    amountCents,
		Currency:       req.Currency,
		Status:         domain.StatusRequested,
	})
	if err != nil {
		logger.Error("failed to persist payment request, will retry", "error", err)
		return outcomeRetry
	}

	payment := saveResult.Payment
	if !saveResult.Inserted {
		if payment.Status != domain.StatusRequested {
			// Already processed to a terminal state by an earlier delivery
			// of this exact request - a genuine duplicate. Do not charge
			// again; the result outbox row (and thus the Kafka result
			// event) was already produced the first time.
			logger.Info("duplicate payment.requested ignored, already processed",
				"paymentId", payment.ID, "status", payment.Status)
			return outcomeCommit
		}
		// Inserted earlier but the process crashed before charging/
		// recording a result (restart/reprocessing safety) - fall through
		// and charge using the existing row's id.
		logger.Info("resuming previously-recorded but unresolved payment request", "paymentId", payment.ID)
	}

	result, err := c.provider.Charge(ctx, provider.ChargeRequest{
		PaymentID:   payment.ID.String(),
		OrderID:     payment.OrderID,
		AmountCents: payment.AmountCents,
		Currency:    payment.Currency,
	})
	if err != nil {
		logger.Error("provider charge attempt failed transiently, will retry", "error", err, "paymentId", payment.ID)
		return outcomeRetry
	}

	if err := c.recordResult(ctx, payment, req.EventID, result); err != nil {
		logger.Error("failed to record payment result, will retry", "error", err, "paymentId", payment.ID)
		return outcomeRetry
	}

	logger.Info("processed payment request", "paymentId", payment.ID, "approved", result.Approved)
	return outcomeCommit
}

func (c *RequestConsumer) recordResult(
	ctx context.Context,
	payment domain.Payment,
	requestEventID uuid.UUID,
	result provider.ChargeResult,
) error {
	now := eventtime.Now()
	if result.Approved {
		payload, err := json.Marshal(events.PaymentSucceededEvent{
			EventID:        uuid.New(),
			RequestEventID: requestEventID,
			OrderID:        payment.OrderID,
			PaymentID:      payment.ID,
			OccurredAt:     now,
		})
		if err != nil {
			return fmt.Errorf("marshal PaymentSucceededEvent: %w", err)
		}
		return c.store.RecordResultAndEnqueue(ctx, payment.ID, domain.StatusSucceeded, "", events.PaymentSucceeded, payload)
	}

	payload, err := json.Marshal(events.PaymentFailedEvent{
		EventID:        uuid.New(),
		RequestEventID: requestEventID,
		OrderID:        payment.OrderID,
		PaymentID:      payment.ID,
		Reason:         result.Reason,
		OccurredAt:     now,
	})
	if err != nil {
		return fmt.Errorf("marshal PaymentFailedEvent: %w", err)
	}
	return c.store.RecordResultAndEnqueue(ctx, payment.ID, domain.StatusFailed, result.Reason, events.PaymentFailed, payload)
}

func (c *RequestConsumer) sendToDLQ(ctx context.Context, msg kafkago.Message, reason string) {
	err := c.dlqWriter.WriteMessages(ctx, kafkago.Message{
		Key:   msg.Key,
		Value: msg.Value,
		Headers: []kafkago.Header{
			{Key: "x-dlq-reason", Value: []byte(reason)},
			{Key: "x-original-topic", Value: []byte(events.PaymentRequested)},
		},
	})
	if err != nil {
		c.logger.Error("failed to publish message to DLQ", "error", err, "reason", reason)
	}
}
