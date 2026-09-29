package provider

import "context"

// FakeProvider is a deterministic, in-process development stand-in for a
// real payment gateway. Decline rule (documented here and in the manual
// testing issue): a charge is declined whenever the amount's cents component
// equals 13 (e.g. 10.13, 39.13) - anything else succeeds. Deterministic on
// purpose so the full request->result Kafka flow can be proven end-to-end
// without a real card network.
type FakeProvider struct{}

// NewFakeProvider constructs a FakeProvider.
func NewFakeProvider() *FakeProvider {
	return &FakeProvider{}
}

const declineCents = 13

func (p *FakeProvider) Charge(_ context.Context, req ChargeRequest) (ChargeResult, error) {
	cents := req.AmountCents % 100
	if cents < 0 {
		cents += 100
	}
	if cents == declineCents {
		return ChargeResult{
			Approved: false,
			Reason:   "FAKE_PROVIDER_DECLINED: amount ends in .13",
		}, nil
	}
	return ChargeResult{Approved: true}, nil
}
