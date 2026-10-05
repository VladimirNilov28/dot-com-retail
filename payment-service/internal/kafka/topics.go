package kafka

import (
	"fmt"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
)

// Topics lists every Kafka topic the Payment Service produces to or
// consumes from: charge/refund requests, their DLQs, results and progress.
var Topics = []string{
	events.PaymentRequested,
	events.PaymentRequested + DLQSuffix,
	events.PaymentSucceeded,
	events.PaymentFailed,
	events.PaymentUnresolved,
	events.RefundRequested,
	events.RefundRequested + DLQSuffix,
	events.RefundSucceeded,
	events.RefundFailed,
	events.RefundUnresolved,
}

// EnsureTopics explicitly creates every topic in Topics (idempotently -
// "topic already exists" is not an error) before any consumer/producer
// starts.
//
// This matters even with broker-side auto-topic-creation enabled: a
// consumer group's very first JoinGroup/metadata lookup can race a
// not-yet-existing topic, in which case it is assigned zero partitions and
// silently never rebalances again even after the topic is created moments
// later by a producer - the request topic then goes unconsumed forever with
// no error logged. Creating topics up front removes that race entirely.
func EnsureTopics(brokers []string) error {
	if len(brokers) == 0 {
		return fmt.Errorf("kafka: no brokers configured")
	}
	conn, err := kafkago.Dial("tcp", brokers[0])
	if err != nil {
		return fmt.Errorf("kafka: dial broker to ensure topics: %w", err)
	}
	defer func() { _ = conn.Close() }()

	configs := make([]kafkago.TopicConfig, len(Topics))
	for i, topic := range Topics {
		configs[i] = kafkago.TopicConfig{Topic: topic, NumPartitions: 1, ReplicationFactor: 1}
	}
	if err := conn.CreateTopics(configs...); err != nil {
		return fmt.Errorf("kafka: create topics: %w", err)
	}
	return nil
}
