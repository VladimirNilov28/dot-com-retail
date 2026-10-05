package kafka

import (
	"context"
	"log/slog"
	"time"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

// OutboxPublisher polls the payment_outbox table and publishes unpublished
// rows to Kafka, outside any domain transaction - this is the only component
// that talks to Kafka on the producer side, matching the same
// transactional-outbox split used by the Spring backend's
// PaymentOutboxPublisher.
type OutboxPublisher struct {
	writer       *kafkago.Writer
	store        *store.Store
	logger       *slog.Logger
	pollInterval time.Duration
	batchSize    int
}

// NewOutboxPublisher builds an OutboxPublisher writing to the given brokers.
func NewOutboxPublisher(brokers []string, s *store.Store, pollInterval time.Duration, logger *slog.Logger) *OutboxPublisher {
	writer := &kafkago.Writer{
		Addr:                   kafkago.TCP(brokers...),
		Balancer:               &kafkago.LeastBytes{},
		RequiredAcks:           kafkago.RequireAll,
		AllowAutoTopicCreation: true,
	}
	return &OutboxPublisher{writer: writer, store: s, logger: logger, pollInterval: pollInterval, batchSize: 50}
}

// Close releases the underlying Kafka writer.
func (p *OutboxPublisher) Close() error {
	return p.writer.Close()
}

// Run polls on pollInterval until ctx is cancelled.
func (p *OutboxPublisher) Run(ctx context.Context) {
	ticker := time.NewTicker(p.pollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.PublishUnpublished(ctx)
		}
	}
}

// PublishUnpublished publishes every currently-unpublished outbox row. It is
// exported so tests (and the initial startup pass) can drive it
// deterministically instead of waiting on the ticker.
func (p *OutboxPublisher) PublishUnpublished(ctx context.Context) {
	rows, err := p.store.FetchUnpublished(ctx, p.batchSize)
	if err != nil {
		p.logger.Error("failed to fetch unpublished outbox rows", "error", err)
		return
	}
	for _, row := range rows {
		err := p.writer.WriteMessages(ctx, kafkago.Message{
			Topic: row.Topic,
			Value: row.Payload,
		})
		if err != nil {
			p.logger.Warn("failed to publish outbox row, will retry on next poll",
				"error", err, "outboxId", row.ID, "topic", row.Topic)
			continue
		}
		if err := p.store.MarkPublished(ctx, row.ID); err != nil {
			p.logger.Error("published outbox row but failed to mark it published - "+
				"may be resent on next poll (at-least-once, safe: consumer idempotency handles it)",
				"error", err, "outboxId", row.ID)
			continue
		}
		p.logger.Info("published outbox row", "outboxId", row.ID, "topic", row.Topic)
	}
}
