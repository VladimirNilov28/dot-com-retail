package provider

import (
	"context"

	"github.com/google/uuid"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

// DurableProvider is a simulated gateway whose atomic, immutable ledger
// commits independently of the consumer's result/outbox transaction.
// It has no external financial side effects and is not a production adapter.
type DurableProvider struct {
	store *store.Store
}

func NewDurableProvider(s *store.Store) *DurableProvider {
	return &DurableProvider{store: s}
}

func (p *DurableProvider) ChargeIdempotently(ctx context.Context, key uuid.UUID, req ChargeRequest) (ChargeResult, error) {
	result, err := p.store.LedgerCharge(ctx, key, req.OrderID, req.AmountCents, req.Currency)
	return chargeResult(result), err
}

func (p *DurableProvider) RefundIdempotently(ctx context.Context, key uuid.UUID, req RefundRequest) (ChargeResult, error) {
	result, err := p.store.LedgerRefund(ctx, key, req.PaymentID, req.OrderID, req.AmountCents, req.Currency, req.ProviderTransactionID)
	return chargeResult(result), err
}

func chargeResult(result domain.ProviderOutcome) ChargeResult {
	return ChargeResult{Approved: result.Approved, Reason: result.Reason, ProviderTransactionID: result.ProviderTransactionID}
}
