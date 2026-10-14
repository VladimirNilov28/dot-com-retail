package stripehttp

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/events"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

type Handler struct {
	Store          *store.Store
	Stripe         *provider.Stripe
	InternalToken  string
	SigningSecret  string
	PublishableKey string
	Configured     bool
	Logger         *slog.Logger
}

func VerifySignature(body []byte, signature, secret string, now time.Time) bool {
	if secret == "" {
		return false
	}
	var timestamp string
	var signatures []string
	for _, part := range strings.Split(signature, ",") {
		key, value, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		if key == "t" {
			if timestamp != "" {
				return false
			}
			timestamp = value
		}
		if key == "v1" {
			signatures = append(signatures, value)
		}
	}
	seconds, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil || seconds < now.Add(-5*time.Minute).Unix() || seconds > now.Add(5*time.Minute).Unix() {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(timestamp + "."))
	mac.Write(body)
	expected := mac.Sum(nil)
	for _, value := range signatures {
		actual, err := hex.DecodeString(value)
		if err == nil && hmac.Equal(expected, actual) {
			return true
		}
	}
	return false
}

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /webhooks/stripe", h.Webhook)
	mux.HandleFunc("POST /internal/stripe/session", h.Session)
	mux.HandleFunc("POST /internal/stripe/config", h.Configuration)
}

func (h *Handler) authorized(r *http.Request) bool {
	expected := sha256.Sum256([]byte(h.InternalToken))
	actual := sha256.Sum256([]byte(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")))
	return len(h.InternalToken) >= 32 && strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") &&
		hmac.Equal(expected[:], actual[:])
}

func (h *Handler) Configuration(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "private, no-store")
	if !h.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(struct {
		Available      bool   `json:"available"`
		PublishableKey string `json:"publishableKey"`
	}{h.Configured, h.PublishableKey}); err != nil {
		h.Logger.Error("Stripe configuration response failed", "error", err)
	}
}

func (h *Handler) Webhook(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil || !VerifySignature(body, r.Header.Get("Stripe-Signature"), h.SigningSecret, time.Now()) {
		http.Error(w, "invalid webhook signature", http.StatusBadRequest)
		return
	}
	var event struct {
		ID       string `json:"id"`
		Type     string `json:"type"`
		LiveMode bool   `json:"livemode"`
		Data     struct {
			Object provider.StripeIntent `json:"object"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &event) != nil || event.ID == "" || event.LiveMode || event.Data.Object.LiveMode {
		http.Error(w, "invalid sandbox event", http.StatusBadRequest)
		return
	}
	switch event.Type {
	case "payment_intent.succeeded", "payment_intent.payment_failed", "payment_intent.processing", "payment_intent.canceled":
	default:
		w.WriteHeader(http.StatusNoContent)
		return
	}
	id, err := uuid.Parse(event.Data.Object.Metadata["payment_id"])
	if err != nil {
		http.Error(w, "invalid payment binding", http.StatusBadRequest)
		return
	}
	// Fetch current provider state rather than trusting arrival order or an old
	// event snapshot. A delayed failure cannot downgrade a successful intent.
	intent, err := h.Stripe.Intent(r.Context(), event.Data.Object.ID)
	if err == nil {
		err = h.settle(r.Context(), id, intent)
	}
	if err != nil {
		h.Logger.Error("Stripe webhook reconciliation failed", "eventId", event.ID, "error", err)
		http.Error(w, "reconciliation unavailable", http.StatusServiceUnavailable)
		return
	}
	h.Logger.Info("Stripe webhook reconciled", "eventId", event.ID, "eventType", event.Type, "paymentId", id, "intentId", intent.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) settle(ctx context.Context, id uuid.UUID, intent provider.StripeIntent) error {
	payment, err := h.Store.GetByID(ctx, id)
	if err != nil {
		return err
	}
	if payment.Provider != "stripe" {
		return store.ErrConflict
	}
	if err := provider.ValidateIntent(intent, id, provider.ChargeRequest{
		OrderID: payment.OrderID, AmountCents: payment.AmountCents, Currency: payment.Currency,
	}); err != nil {
		return err
	}
	if err := h.Store.BindStripeOperation(ctx, id, intent.ID); err != nil {
		return err
	}
	switch intent.Status {
	case "succeeded":
		payload, err := json.Marshal(events.PaymentSucceededEvent{
			EventID: uuid.New(), RequestEventID: payment.RequestEventID, OrderID: payment.OrderID,
			PaymentID: payment.ID, ProviderTransactionID: intent.ID, OccurredAt: eventtime.Now(),
		})
		if err != nil {
			return err
		}
		return h.Store.RecordPaymentResultAndEnqueue(ctx, id, domain.StatusSucceeded, "", intent.ID, events.PaymentSucceeded, payload)
	case "canceled":
		payload, err := json.Marshal(events.PaymentFailedEvent{
			EventID: uuid.New(), RequestEventID: payment.RequestEventID, OrderID: payment.OrderID,
			PaymentID: payment.ID, Reason: "provider_payment_canceled", OccurredAt: eventtime.Now(),
		})
		if err != nil {
			return err
		}
		return h.Store.RecordPaymentResultAndEnqueue(ctx, id, domain.StatusFailed, "provider_payment_canceled", intent.ID, events.PaymentFailed, payload)
	case "requires_payment_method":
		outcome, reason, attempt := ClassifyFailure(intent)
		if outcome == OutcomeDeclined {
			payload, err := json.Marshal(events.PaymentDeclinedEvent{
				EventID: uuid.New(), RequestEventID: payment.RequestEventID, OrderID: payment.OrderID,
				PaymentID: payment.ID, AttemptID: attempt, Reason: reason, OccurredAt: eventtime.Now(),
			})
			if err != nil {
				return err
			}
			_, err = h.Store.RecordPaymentDeclined(ctx, id, attempt, reason, events.PaymentDeclined, payload)
			return err
		}
		if outcome == OutcomeUnresolved {
			payload, err := json.Marshal(events.PaymentUnresolvedEvent{
				EventID: uuid.New(), RequestEventID: payment.RequestEventID, OrderID: payment.OrderID,
				PaymentID: payment.ID, Reason: "payment_retry_required", OccurredAt: eventtime.Now(),
			})
			if err != nil {
				return err
			}
			return h.Store.RecordPaymentUnresolved(ctx, id, "payment_retry_required", payload)
		}
	}
	return nil
}

func (h *Handler) Session(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "private, no-store")
	if !h.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var request struct {
		RequestEventID uuid.UUID `json:"requestEventId"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&request) != nil || request.RequestEventID == uuid.Nil {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	response := struct {
		Status         string  `json:"status"`
		PublishableKey string  `json:"publishableKey"`
		ClientSecret   *string `json:"clientSecret"`
		FailureCode    *string `json:"failureCode"`
	}{Status: "NOT_CONFIGURED", PublishableKey: h.PublishableKey}
	if h.Configured {
		response.Status = "QUEUED"
		payment, err := h.Store.GetByRequestEventID(r.Context(), request.RequestEventID)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			http.Error(w, "payment unavailable", http.StatusServiceUnavailable)
			return
		}
		if err == nil {
			if payment.Provider != "stripe" {
				http.Error(w, "not a Stripe payment", http.StatusConflict)
				return
			}
			reference, _, err := h.Store.StripeOperation(r.Context(), payment.ID, "intent")
			if err != nil {
				http.Error(w, "payment unavailable", http.StatusServiceUnavailable)
				return
			}
			if reference != "" {
				intent, err := h.Stripe.Intent(r.Context(), reference)
				if err != nil || provider.ValidateIntent(intent, payment.ID, provider.ChargeRequest{
					OrderID: payment.OrderID, AmountCents: payment.AmountCents, Currency: payment.Currency,
				}) != nil {
					http.Error(w, "provider unavailable", http.StatusServiceUnavailable)
					return
				}
				response.Status = intent.Status
				if intent.Status == "requires_payment_method" || intent.Status == "requires_action" || intent.Status == "requires_confirmation" {
					response.ClientSecret = &intent.ClientSecret
				}
				if intent.LastError != nil {
					// Same allow-listed vocabulary as payment.declined;
					// anything not a definite decline stays generic.
					code := "payment_failed"
					if outcome, reason, _ := ClassifyFailure(intent); outcome == OutcomeDeclined {
						code = reason
					}
					response.FailureCode = &code
				}
			}
		}
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(response); err != nil {
		h.Logger.Error("Stripe session response failed", "error", err)
	}
}

func (h *Handler) RunReconciliation(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if !h.Configured {
				continue
			}
			payments, err := h.Store.PendingStripePayments(ctx)
			if err != nil {
				h.Logger.Error("Stripe reconciliation scan failed", "error", err)
				continue
			}
			for _, payment := range payments {
				// Rotate attempted records even after provider failures, so a
				// permanently failing batch cannot starve later pending orders.
				err := h.Store.TouchStripePayment(ctx, payment.ID)
				if err == nil {
					var intent provider.StripeIntent
					intent, err = h.Stripe.Intent(ctx, payment.Reference)
					if err == nil {
						err = h.settle(ctx, payment.ID, intent)
					}
				}
				if err != nil {
					h.Logger.Error("Stripe reconciliation failed", "paymentId", payment.ID, "error", err)
				}
			}
		}
	}
}
