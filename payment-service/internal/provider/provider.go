// Package provider defines the payment-provider boundary. No real provider
// is integrated in this milestone (per the brief) - only a deterministic
// fake/development implementation. Neither the interface nor any
// implementation may ever carry card/PAN/CVV data; ChargeRequest/Result are
// intentionally limited to what a real gateway integration would still need
// to expose across this boundary (amount, currency, durable payment identity).
package provider

import (
	"context"

	"github.com/google/uuid"
)

// ChargeRequest is what the Payment Service asks a Provider to charge.
// The immutable parameters must be reused with the same idempotency identity.
type ChargeRequest struct {
	OrderID     int64
	AmountCents int64
	Currency    string
}

// ChargeResult is the outcome of a charge attempt.
type ChargeResult struct {
	Approved              bool
	ProviderTransactionID string
	// Reason is a short, safe-to-log decline description when Approved is
	// false (never raw provider/gateway secrets).
	Reason string
}

// Provider must atomically deduplicate charges at the gateway using the
// persisted Payment ID (its canonical UUID string) as the idempotency key.
// Concurrent calls and retries after uncertain responses, DB failure or
// service restart must return the original outcome without another charge.
// Reusing a key with different parameters must fail, never create a charge.
// The gateway's key/outcome retention must cover the entire retry/redelivery
// lifetime; after expiry an adapter must reconcile or fail closed, not charge
// again. Local caches or marking a payment successful before this call do not
// satisfy this contract. No production gateway is integrated yet.
// Returned errors/reasons must be safe to log: never include credentials,
// tokens, card data or raw gateway response bodies.
type Provider interface {
	ChargeIdempotently(ctx context.Context, paymentID uuid.UUID, req ChargeRequest) (ChargeResult, error)
}

type RefundRequest struct {
	PaymentID             uuid.UUID
	OrderID               int64
	AmountCents           int64
	Currency              string
	ProviderTransactionID string
}

// RefundProvider must retain the original outcome for a refund UUID and
// reject changed parameters or another UUID for the same full refund.
// Errors mean uncertainty; Approved=false without an error is definitive.
type RefundProvider interface {
	RefundIdempotently(context.Context, uuid.UUID, RefundRequest) (ChargeResult, error)
}
