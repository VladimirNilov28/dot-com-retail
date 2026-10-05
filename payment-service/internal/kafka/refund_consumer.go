package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	kafkago "github.com/segmentio/kafka-go"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/money"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

// RefundConsumer owns a separate reader/group, so an uncertain charge cannot
// block refund requests. It shares durable DLQ/offset/retry conventions.
type RefundConsumer struct {
	*RequestConsumer
	refundProvider provider.RefundProvider
}

func NewRefundConsumer(brokers []string, groupID string, s *store.Store, p provider.RefundProvider, logger *slog.Logger) *RefundConsumer {
	c := &RefundConsumer{
		RequestConsumer: newRequestConsumer(brokers, groupID+"-refunds", events.RefundRequested, s, nil, logger),
		refundProvider:  p,
	}
	c.handleMessage = c.handle
	return c
}

func (c *RefundConsumer) handle(ctx context.Context, msg kafkago.Message) outcome {
	var req events.RefundRequestedEvent
	if err := json.Unmarshal(msg.Value, &req); err != nil {
		return c.sendToDLQ(ctx, msg, "malformed_json")
	}
	amount, err := money.ParseCents(req.Amount.String())
	if err != nil || amount <= 0 || req.EventID == uuid.Nil || req.RefundID == uuid.Nil ||
		req.PaymentID == uuid.Nil || req.RequestEventID == uuid.Nil || req.OrderID <= 0 ||
		req.EventID == req.RefundID || !validCurrency(req.Currency) {
		return c.sendToDLQ(ctx, msg, "invalid_refund_request")
	}
	refund, err := c.store.SaveRefundRequested(ctx, domain.Refund{
		ID: req.RefundID, RequestEventID: req.EventID, OriginalRequestEventID: req.RequestEventID,
		PaymentID: req.PaymentID, OrderID: req.OrderID, AmountCents: amount, Currency: req.Currency,
		OriginalProviderTransactionID: req.ProviderTransactionID, Status: domain.StatusRequested,
	})
	if errors.Is(err, store.ErrConflict) || errors.Is(err, pgx.ErrNoRows) {
		return c.sendToDLQ(ctx, msg, "invalid_original_or_immutable_refund_parameters_conflict")
	}
	if err != nil {
		c.logger.Error("failed to persist refund request, will retry", "error", err, "refundId", req.RefundID)
		return outcomeRetry
	}
	if refund.Status != domain.StatusRequested {
		return outcomeCommit
	}
	result, err := c.refundProvider.RefundIdempotently(ctx, refund.ID, provider.RefundRequest{
		PaymentID: refund.PaymentID, OrderID: refund.OrderID, AmountCents: refund.AmountCents,
		Currency: refund.Currency, ProviderTransactionID: refund.OriginalProviderTransactionID,
	})
	if errors.Is(err, store.ErrConflict) {
		return c.sendToDLQ(ctx, msg, "provider_refund_parameters_conflict")
	}
	if err != nil {
		if ctx.Err() == nil {
			payload, marshalErr := json.Marshal(events.RefundUnresolvedEvent{
				EventID: uuid.New(), RequestEventID: refund.RequestEventID, RefundID: refund.ID,
				OrderID: refund.OrderID, PaymentID: refund.PaymentID,
				Reason: "provider_outcome_unknown", OccurredAt: eventtime.Now(),
			})
			if marshalErr != nil {
				c.logger.Error("failed to marshal refund progress", "error", marshalErr)
			} else if progressErr := c.store.RecordRefundUnresolved(ctx, refund.ID, "provider_outcome_unknown", payload); progressErr != nil {
				c.logger.Error("failed to record refund progress", "error", progressErr)
			}
		}
		c.logger.Error("provider refund outcome uncertain, will retry same UUID", "error", err, "refundId", refund.ID)
		return outcomeRetry
	}
	var payload []byte
	status, topic, reason := domain.StatusSucceeded, events.RefundSucceeded, ""
	if result.Approved {
		payload, err = json.Marshal(events.RefundSucceededEvent{
			EventID: uuid.New(), RequestEventID: refund.RequestEventID, RefundID: refund.ID,
			OrderID: refund.OrderID, PaymentID: refund.PaymentID,
			ProviderTransactionID: result.ProviderTransactionID, OccurredAt: eventtime.Now(),
		})
	} else {
		status, topic, reason = domain.StatusFailed, events.RefundFailed, result.Reason
		payload, err = json.Marshal(events.RefundFailedEvent{
			EventID: uuid.New(), RequestEventID: refund.RequestEventID, RefundID: refund.ID,
			OrderID: refund.OrderID, PaymentID: refund.PaymentID, Reason: reason,
			ProviderTransactionID: result.ProviderTransactionID, OccurredAt: eventtime.Now(),
		})
	}
	if err == nil {
		err = c.store.RecordRefundResultAndEnqueue(ctx, refund.ID, status, reason, result.ProviderTransactionID, topic, payload)
	}
	if errors.Is(err, store.ErrConflict) {
		return c.sendToDLQ(ctx, msg, "conflicting_refund_result")
	}
	if err != nil {
		c.logger.Error("failed to record refund result, will retry", "error", err, "refundId", refund.ID)
		return outcomeRetry
	}
	return outcomeCommit
}
