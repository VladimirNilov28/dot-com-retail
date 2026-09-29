// Package provider defines the payment-provider boundary. No real provider
// is integrated in this milestone (per the brief) - only a deterministic
// fake/development implementation. Neither the interface nor any
// implementation may ever carry card/PAN/CVV data; ChargeRequest/Result are
// intentionally limited to what a real gateway integration would still need
// to expose across this boundary (amount, currency, a correlation id).
package provider

import "context"

// ChargeRequest is what the Payment Service asks a Provider to charge.
// PaymentID is passed through only for correlation/logging - never sent
// anywhere as payment credentials, because there are none here.
type ChargeRequest struct {
	PaymentID   string
	OrderID     int64
	AmountCents int64
	Currency    string
}

// ChargeResult is the outcome of a charge attempt.
type ChargeResult struct {
	Approved bool
	// Reason is a short, safe-to-log decline description when Approved is
	// false (never raw provider/gateway secrets).
	Reason string
}

// Provider is the boundary a real payment gateway integration would
// implement later; FakeProvider is the only implementation for now.
type Provider interface {
	Charge(ctx context.Context, req ChargeRequest) (ChargeResult, error)
}
