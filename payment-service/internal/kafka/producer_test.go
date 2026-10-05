package kafka

import (
	"log/slog"
	"testing"
	"time"

	kafkago "github.com/segmentio/kafka-go"
)

func TestResultOutboxRequiresBrokerAcknowledgement(t *testing.T) {
	publisher := NewOutboxPublisher([]string{"unused:9092"}, nil, time.Second, slog.Default())
	defer publisher.Close()
	if publisher.writer.RequiredAcks != kafkago.RequireAll || publisher.writer.Async {
		t.Fatalf("financial result outbox must await durable acknowledgement: RequiredAcks=%v Async=%v",
			publisher.writer.RequiredAcks, publisher.writer.Async)
	}
}
