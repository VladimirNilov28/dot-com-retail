// Package events defines the Kafka payload contracts shared with the Spring
// backend (see backend/.../integration/payment/event/*.java). Field names
// and the topic names in Topics must stay byte-for-byte in sync with those
// Java records/PaymentTopics - this is the one integration seam between the
// two services, and nothing else may assume a different shape.
package events

import (
	"encoding/json"

	"github.com/google/uuid"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
)

// Topics are the Kafka topic names used for backend<->Payment Service
// communication, matching ee.bytecore.backend.integration.payment.PaymentTopics.
const (
	PaymentRequested  = "payment.requested"
	PaymentSucceeded  = "payment.succeeded"
	PaymentFailed     = "payment.failed"
	PaymentUnresolved = "payment.unresolved"
	PaymentDeclined   = "payment.declined"
	RefundRequested   = "refund.requested"
	RefundSucceeded   = "refund.succeeded"
	RefundFailed      = "refund.failed"
	RefundUnresolved  = "refund.unresolved"
)

// PaymentRequestedEvent mirrors
// ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent.
// Amount uses json.Number (not float64) to avoid any binary-floating-point
// rounding of a monetary value that started life as a Java BigDecimal.
type PaymentRequestedEvent struct {
	Provider   string            `json:"provider,omitempty"`
	EventID    uuid.UUID         `json:"eventId"`
	OrderID    int64             `json:"orderId"`
	UserID     *int64            `json:"userId"`
	Amount     json.Number       `json:"amount"`
	Currency   string            `json:"currency"`
	OccurredAt eventtime.Instant `json:"occurredAt"`
}

// PaymentSucceededEvent mirrors
// ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent.
type PaymentSucceededEvent struct {
	EventID               uuid.UUID         `json:"eventId"`
	RequestEventID        uuid.UUID         `json:"requestEventId"`
	OrderID               int64             `json:"orderId"`
	PaymentID             uuid.UUID         `json:"paymentId"`
	ProviderTransactionID string            `json:"providerTransactionId,omitempty"`
	OccurredAt            eventtime.Instant `json:"occurredAt"`
}

// PaymentFailedEvent mirrors
// ee.bytecore.backend.integration.payment.event.PaymentFailedEvent.
type PaymentFailedEvent struct {
	EventID        uuid.UUID         `json:"eventId"`
	RequestEventID uuid.UUID         `json:"requestEventId"`
	OrderID        int64             `json:"orderId"`
	PaymentID      uuid.UUID         `json:"paymentId"`
	Reason         string            `json:"reason"`
	OccurredAt     eventtime.Instant `json:"occurredAt"`
}

type PaymentUnresolvedEvent PaymentFailedEvent

// PaymentDeclinedEvent mirrors
// ee.bytecore.backend.integration.payment.event.PaymentDeclinedEvent. It is a
// definite, non-terminal decline of one provider attempt: the payment stays
// retryable on the same intent. AttemptID is the provider attempt reference
// (charge id) used for per-attempt deduplication; Reason is an allow-listed
// code such as insufficient_funds, never card data or provider free text.
type PaymentDeclinedEvent struct {
	EventID        uuid.UUID         `json:"eventId"`
	RequestEventID uuid.UUID         `json:"requestEventId"`
	OrderID        int64             `json:"orderId"`
	PaymentID      uuid.UUID         `json:"paymentId"`
	AttemptID      string            `json:"attemptId"`
	Reason         string            `json:"reason"`
	OccurredAt     eventtime.Instant `json:"occurredAt"`
}

type RefundRequestedEvent struct {
	EventID               uuid.UUID         `json:"eventId"`
	RefundID              uuid.UUID         `json:"refundId"`
	RequestEventID        uuid.UUID         `json:"requestEventId"`
	OrderID               int64             `json:"orderId"`
	PaymentID             uuid.UUID         `json:"paymentId"`
	Amount                json.Number       `json:"amount"`
	Currency              string            `json:"currency"`
	ProviderTransactionID string            `json:"providerTransactionId,omitempty"`
	OccurredAt            eventtime.Instant `json:"occurredAt"`
}

type RefundSucceededEvent struct {
	EventID               uuid.UUID         `json:"eventId"`
	RequestEventID        uuid.UUID         `json:"requestEventId"`
	RefundID              uuid.UUID         `json:"refundId"`
	OrderID               int64             `json:"orderId"`
	PaymentID             uuid.UUID         `json:"paymentId"`
	ProviderTransactionID string            `json:"providerTransactionId,omitempty"`
	OccurredAt            eventtime.Instant `json:"occurredAt"`
}

type RefundFailedEvent struct {
	EventID               uuid.UUID         `json:"eventId"`
	RequestEventID        uuid.UUID         `json:"requestEventId"`
	RefundID              uuid.UUID         `json:"refundId"`
	OrderID               int64             `json:"orderId"`
	PaymentID             uuid.UUID         `json:"paymentId"`
	ProviderTransactionID string            `json:"providerTransactionId,omitempty"`
	Reason                string            `json:"reason"`
	OccurredAt            eventtime.Instant `json:"occurredAt"`
}

type RefundUnresolvedEvent = RefundFailedEvent
