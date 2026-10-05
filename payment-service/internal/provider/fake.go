package provider

import (
	"context"
	"errors"
	"sync"

	"github.com/google/uuid"
)

// FakeProvider is a deterministic, in-process development stand-in for a
// real payment gateway. Decline rule (documented here and in the manual
// testing issue): a charge is declined whenever the amount's cents component
// equals 13 (e.g. 10.13, 39.13) - anything else succeeds. Deterministic on
// purpose so the full request->result Kafka flow can be proven end-to-end
// without a real card network.
type FakeProvider struct {
	mu      sync.Mutex
	charges map[uuid.UUID]fakeCharge
}

type fakeCharge struct {
	request ChargeRequest
	result  ChargeResult
}

// NewFakeProvider constructs a FakeProvider.
func NewFakeProvider() *FakeProvider {
	return &FakeProvider{charges: make(map[uuid.UUID]fakeCharge)}
}

const declineCents = 13

func (p *FakeProvider) ChargeIdempotently(ctx context.Context, paymentID uuid.UUID, req ChargeRequest) (ChargeResult, error) {
	if err := ctx.Err(); err != nil {
		return ChargeResult{}, err
	}
	if paymentID == uuid.Nil {
		return ChargeResult{}, errors.New("provider idempotency identity is required")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if previous, exists := p.charges[paymentID]; exists {
		if previous.request != req {
			return ChargeResult{}, errors.New("provider idempotency identity parameters conflict")
		}
		return previous.result, nil
	}
	cents := req.AmountCents % 100
	if cents < 0 {
		cents += 100
	}
	result := ChargeResult{Approved: true}
	if cents == declineCents {
		result = ChargeResult{
			Approved: false,
			Reason:   "FAKE_PROVIDER_DECLINED: amount ends in .13",
		}
	}
	p.charges[paymentID] = fakeCharge{request: req, result: result}
	return result, nil
}
