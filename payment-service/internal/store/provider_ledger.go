package store

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
)

// ErrConflict is a permanent immutable-identity/parameter violation. Consumers
// must DLQ it rather than retry indefinitely or silently swallow a duplicate.
var ErrConflict = errors.New("immutable financial identity or parameters conflict")

func validFinancialParameters(key uuid.UUID, orderID, amount int64, currency string) bool {
	if key == uuid.Nil || orderID <= 0 || amount <= 0 || len(currency) != 3 {
		return false
	}
	for _, c := range currency {
		if c < 'A' || c > 'Z' {
			return false
		}
	}
	return true
}

// LedgerCharge simulates a gateway operation in an independently committed
// database write. It never participates in a payment/result transaction.
func (s *Store) LedgerCharge(ctx context.Context, key uuid.UUID, orderID, amount int64, currency string) (domain.ProviderOutcome, error) {
	if !validFinancialParameters(key, orderID, amount, currency) {
		return domain.ProviderOutcome{}, ErrConflict
	}
	approved, reason := amount%100 != 13, ""
	if !approved {
		reason = "FAKE_PROVIDER_DECLINED: amount ends in .13"
	}
	return s.ledgerOperation(ctx, "CHARGE", key, key, orderID, amount, currency, "", approved, reason)
}

// LedgerRefund checks the original authoritative successful Payment and its
// simulated charge before recording a full refund. A missing legacy reference
// resolves via the original Payment UUID; caller-supplied references cannot
// override the original charge identity.
func (s *Store) LedgerRefund(ctx context.Context, key, paymentID uuid.UUID, orderID, amount int64, currency, reference string) (domain.ProviderOutcome, error) {
	if !validFinancialParameters(key, orderID, amount, currency) || paymentID == uuid.Nil || key == paymentID {
		return domain.ProviderOutcome{}, ErrConflict
	}
	payment, err := s.GetByID(ctx, paymentID)
	if err != nil {
		return domain.ProviderOutcome{}, err
	}
	if payment.Status != domain.StatusSucceeded || payment.OrderID != orderID ||
		payment.AmountCents != amount || payment.Currency != currency {
		return domain.ProviderOutcome{}, ErrConflict
	}
	var chargeOrder, chargeAmount int64
	var chargeCurrency, chargeReference string
	var approved bool
	err = s.pool.QueryRow(ctx, `SELECT order_id,amount_cents,currency,approved,provider_transaction_id
		FROM provider_ledger WHERE operation='CHARGE' AND idempotency_key=$1`, paymentID).
		Scan(&chargeOrder, &chargeAmount, &chargeCurrency, &approved, &chargeReference)
	if err != nil {
		return domain.ProviderOutcome{}, err
	}
	if !approved || chargeOrder != orderID || chargeAmount != amount || chargeCurrency != currency ||
		(reference != "" && reference != chargeReference) ||
		(payment.ProviderTransactionID != "" && payment.ProviderTransactionID != chargeReference) {
		return domain.ProviderOutcome{}, ErrConflict
	}
	return s.ledgerOperation(ctx, "REFUND", key, paymentID, orderID, amount, currency, reference, true, "")
}

func (s *Store) ledgerOperation(
	ctx context.Context, operation string, key, paymentID uuid.UUID,
	orderID, amount int64, currency, reference string, approved bool, reason string,
) (domain.ProviderOutcome, error) {
	transactionID := "sim-charge-" + key.String()
	if operation == "REFUND" {
		transactionID = "sim-refund-" + key.String()
	}
	// ON CONFLICT waits for concurrent inserts to commit. A separate SELECT
	// sees the committed original even when this call lost the insert race.
	_, err := s.pool.Exec(ctx, `INSERT INTO provider_ledger
		(operation,idempotency_key,payment_id,order_id,amount_cents,currency,
		 original_provider_transaction_id,approved,reason,provider_transaction_id)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT DO NOTHING`,
		operation, key, paymentID, orderID, amount, currency, reference, approved, reason, transactionID)
	if err != nil {
		return domain.ProviderOutcome{}, fmt.Errorf("store: commit simulated provider operation: %w", err)
	}
	var previousKey, previousPayment uuid.UUID
	var previousOrder, previousAmount int64
	var previousCurrency, previousReference string
	var result domain.ProviderOutcome
	err = s.pool.QueryRow(ctx, `SELECT idempotency_key,payment_id,order_id,amount_cents,currency,
		original_provider_transaction_id,approved,reason,provider_transaction_id
		FROM provider_ledger WHERE operation=$1 AND payment_id=$2`, operation, paymentID).
		Scan(&previousKey, &previousPayment, &previousOrder, &previousAmount, &previousCurrency,
			&previousReference, &result.Approved, &result.Reason, &result.ProviderTransactionID)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.ProviderOutcome{}, ErrConflict
	}
	if err != nil {
		return domain.ProviderOutcome{}, err
	}
	if previousKey != key || previousPayment != paymentID || previousOrder != orderID ||
		previousAmount != amount || previousCurrency != currency || previousReference != reference {
		return domain.ProviderOutcome{}, ErrConflict
	}
	return result, nil
}
