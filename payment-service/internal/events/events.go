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
	PaymentRequested = "payment.requested"
	PaymentSucceeded = "payment.succeeded"
	PaymentFailed    = "payment.failed"
)

// PaymentRequestedEvent mirrors
// ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent.
// Amount uses json.Number (not float64) to avoid any binary-floating-point
// rounding of a monetary value that started life as a Java BigDecimal.
type PaymentRequestedEvent struct {
	EventID    uuid.UUID         `json:"eventId"`
	OrderID    int64             `json:"orderId"`
	UserID     int64             `json:"userId"`
	Amount     json.Number       `json:"amount"`
	Currency   string            `json:"currency"`
	OccurredAt eventtime.Instant `json:"occurredAt"`
}

// PaymentSucceededEvent mirrors
// ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent.
type PaymentSucceededEvent struct {
	EventID        uuid.UUID        `json:"eventId"`
	RequestEventID uuid.UUID        `json:"requestEventId"`
	OrderID        int64            `json:"orderId"`
	PaymentID      uuid.UUID        `json:"paymentId"`
	OccurredAt     eventtime.Instant `json:"occurredAt"`
}

// PaymentFailedEvent mirrors
// ee.bytecore.backend.integration.payment.event.PaymentFailedEvent.
type PaymentFailedEvent struct {
	EventID        uuid.UUID        `json:"eventId"`
	RequestEventID uuid.UUID        `json:"requestEventId"`
	OrderID        int64            `json:"orderId"`
	PaymentID      uuid.UUID        `json:"paymentId"`
	Reason         string           `json:"reason"`
	OccurredAt     eventtime.Instant `json:"occurredAt"`
}
