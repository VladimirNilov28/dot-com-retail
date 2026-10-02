package provider

import (
	"context"
	"testing"
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
			result, err := p.Charge(context.Background(), ChargeRequest{
				PaymentID:   "test",
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
	req := ChargeRequest{PaymentID: "x", OrderID: 1, AmountCents: 1013, Currency: "EUR"}
	first, err := p.Charge(context.Background(), req)
	if err != nil {
		t.Fatalf("Charge returned error: %v", err)
	}
	for i := 0; i < 5; i++ {
		result, err := p.Charge(context.Background(), req)
		if err != nil {
			t.Fatalf("Charge returned error: %v", err)
		}
		if result.Approved != first.Approved {
			t.Fatalf("FakeProvider must be deterministic for the same input")
		}
	}
}
