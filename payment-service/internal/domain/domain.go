// Package domain holds the Payment Service's own persistence-independent
// domain types. The Payment Service is the sole owner of payment-processing
// state; the Spring backend never reads or writes these rows directly.
package domain

import (
	"time"

	"github.com/google/uuid"
)

// Status is the lifecycle state of a Payment row.
type Status string

const (
	StatusRequested Status = "REQUESTED"
	StatusSucceeded Status = "SUCCEEDED"
	StatusFailed    Status = "FAILED"
)

// Payment is one payment-processing attempt for a single order. RequestEventID
// uniquely identifies the persisted request. ID is the stable provider
// idempotency identity; database uniqueness alone cannot deduplicate remote
// charges while a payment remains REQUESTED.
type Payment struct {
	ID             uuid.UUID
	RequestEventID uuid.UUID
	OrderID        int64
	UserID         int64
	AmountCents    int64
	Currency       string
	Status         Status
	FailureReason  string
	CreatedAt      time.Time
	UpdatedAt      time.Time
}
