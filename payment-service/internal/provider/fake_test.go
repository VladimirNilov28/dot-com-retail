package provider

import (
	"context"
	"testing"

	"github.com/google/uuid"
)

func TestFakeProviderCharge(t *testing.T) {
	tests := []struct {
		name        string
		amountCents int64
		wantApprove bool
	}{
		{"round amount succeeds", 3900, true},
		{"non-13 fractional succeeds", 1099, true},
		{"amount ending in .13 declines", 1013, false},
		{"larger amount ending in .13 declines", 999913, false},
		{"zero amount succeeds", 0, true},
	}

	p := NewFakeProvider()
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := p.ChargeIdempotently(context.Background(), uuid.New(), ChargeRequest{
				OrderID:     1,
				AmountCents: tt.amountCents,
				Currency:    "EUR",
			})
			if err != nil {
				t.Fatalf("Charge returned error: %v", err)
			}
			if result.Approved != tt.wantApprove {
				t.Fatalf("Charge(%d) approved = %v, want %v", tt.amountCents, result.Approved, tt.wantApprove)
			}
			if !tt.wantApprove && result.Reason == "" {
				t.Fatalf("declined charge must carry a reason")
			}
		})
	}
}

func TestFakeProviderIsDeterministic(t *testing.T) {
	p := NewFakeProvider()
	req := ChargeRequest{OrderID: 1, AmountCents: 1013, Currency: "EUR"}
	paymentID := uuid.New()
	first, err := p.ChargeIdempotently(context.Background(), paymentID, req)
	if err != nil {
		t.Fatalf("Charge returned error: %v", err)
	}

	for i := 0; i < 5; i++ {
		result, err := p.ChargeIdempotently(context.Background(), paymentID, req)
		if err != nil {
			t.Fatalf("Charge returned error: %v", err)
		}
		if result.Approved != first.Approved {
			t.Fatalf("FakeProvider must be deterministic for the same input")
		}
	}
}

func TestFakeProviderRejectsMissingIdempotencyIdentity(t *testing.T) {
	_, err := NewFakeProvider().ChargeIdempotently(context.Background(), uuid.Nil, ChargeRequest{
		OrderID: 1, AmountCents: 2000, Currency: "EUR",
	})
	if err == nil {
		t.Fatal("provider must reject a charge without a durable idempotency identity")
	}
}

func TestFakeProviderRejectsChangedParametersForSameIdentity(t *testing.T) {
	p := NewFakeProvider()
	key := uuid.New()
	req := ChargeRequest{OrderID: 1, AmountCents: 2000, Currency: "EUR"}
	original, err := p.ChargeIdempotently(context.Background(), key, req)
	if err != nil {
		t.Fatal(err)
	}
	for _, changed := range []ChargeRequest{
		{OrderID: 2, AmountCents: 2000, Currency: "EUR"},
		{OrderID: 1, AmountCents: 1013, Currency: "EUR"},
		{OrderID: 1, AmountCents: 2000, Currency: "USD"},
	} {
		if _, err := p.ChargeIdempotently(context.Background(), key, changed); err == nil {
			t.Fatal("same key with changed parameters must fail without a new charge")
		}
		again, err := p.ChargeIdempotently(context.Background(), key, req)
		if err != nil || again != original {
			t.Fatalf("original outcome changed: result=%+v error=%v", again, err)
		}
	}
}
