package provider

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

type StripeIntent struct {
	ID           string            `json:"id"`
	ClientSecret string            `json:"client_secret"`
	Status       string            `json:"status"`
	Amount       int64             `json:"amount"`
	Currency     string            `json:"currency"`
	LiveMode     bool              `json:"livemode"`
	Metadata     map[string]string `json:"metadata"`
	LastError    *struct {
		Code        string `json:"code"`
		DeclineCode string `json:"decline_code"`
	} `json:"last_payment_error"`
}

type Stripe struct {
	store  *store.Store
	key    string
	client *http.Client
	base   string
}

func NewStripe(s *store.Store, key string) (*Stripe, error) {
	if key != "" && !strings.HasPrefix(key, "sk_test_") {
		return nil, errors.New("stripe: only test-mode secret keys are supported")
	}
	return &Stripe{store: s, key: key, client: &http.Client{Timeout: 10 * time.Second}, base: "https://api.stripe.com/v1"}, nil
}

func (s *Stripe) call(ctx context.Context, method, path, key string, form url.Values, result any) error {
	if s.key == "" {
		return errors.New("stripe: sandbox credentials are not configured")
	}
	request, err := http.NewRequestWithContext(ctx, method, s.base+path, strings.NewReader(form.Encode()))
	if err != nil {
		return errors.New("stripe: cannot construct request")
	}
	request.SetBasicAuth(s.key, "")
	request.Header.Set("Stripe-Version", "2025-06-30.basil")
	if method == http.MethodPost {
		request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		request.Header.Set("Idempotency-Key", key)
	}
	response, err := s.client.Do(request)
	if err != nil {
		return errors.New("stripe: network failure; original operation must be reconciled")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("stripe: API unavailable (HTTP %d); original operation retained", response.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(result); err != nil {
		return errors.New("stripe: invalid API response")
	}
	return nil
}

func (s *Stripe) Intent(ctx context.Context, reference string) (StripeIntent, error) {
	var intent StripeIntent
	if !strings.HasPrefix(reference, "pi_") || strings.ContainsAny(reference, "/?#") {
		return intent, errors.New("stripe: invalid intent reference")
	}
	err := s.call(ctx, http.MethodGet, "/payment_intents/"+reference, "", nil, &intent)
	return intent, err
}

func ValidateIntent(intent StripeIntent, id uuid.UUID, req ChargeRequest) error {
	if intent.LiveMode || !strings.HasPrefix(intent.ID, "pi_") || intent.Amount != req.AmountCents ||
		intent.Currency != strings.ToLower(req.Currency) || intent.Metadata["payment_id"] != id.String() ||
		intent.Metadata["order_id"] != strconv.FormatInt(req.OrderID, 10) {
		return store.ErrConflict
	}
	return nil
}

func (s *Stripe) ChargeIdempotently(ctx context.Context, id uuid.UUID, req ChargeRequest) (ChargeResult, error) {
	reference, created, err := s.store.StripeOperation(ctx, id, "intent")
	if err != nil {
		return ChargeResult{}, err
	}
	var intent StripeIntent
	if reference != "" {
		intent, err = s.Intent(ctx, reference)
	} else {
		if time.Since(created) >= 23*time.Hour {
			return ChargeResult{}, errors.New("stripe: expired idempotency window requires operator reconciliation")
		}
		err = s.call(ctx, http.MethodPost, "/payment_intents", id.String(), url.Values{
			"amount": {strconv.FormatInt(req.AmountCents, 10)}, "currency": {strings.ToLower(req.Currency)},
			"payment_method_types[]": {"card"}, "metadata[payment_id]": {id.String()},
			"metadata[order_id]": {strconv.FormatInt(req.OrderID, 10)},
		}, &intent)
	}
	if err != nil {
		return ChargeResult{}, err
	}
	if err := ValidateIntent(intent, id, req); err != nil {
		return ChargeResult{}, err
	}
	if err := s.store.BindStripeOperation(ctx, id, intent.ID); err != nil {
		return ChargeResult{}, err
	}
	// Only verified webhook/reconciliation settlement can publish success.
	return ChargeResult{Pending: true, ProviderTransactionID: intent.ID}, nil
}

func (s *Stripe) RefundIdempotently(ctx context.Context, id uuid.UUID, req RefundRequest) (ChargeResult, error) {
	reference, created, err := s.store.StripeOperation(ctx, id, "refund")
	if err != nil {
		return ChargeResult{}, err
	}
	var refund struct {
		ID            string `json:"id"`
		Status        string `json:"status"`
		Amount        int64  `json:"amount"`
		Currency      string `json:"currency"`
		PaymentIntent string `json:"payment_intent"`
	}
	if reference != "" {
		err = s.call(ctx, http.MethodGet, "/refunds/"+reference, "", nil, &refund)
	} else {
		if time.Since(created) >= 23*time.Hour {
			return ChargeResult{}, errors.New("stripe: expired refund key requires operator reconciliation")
		}
		err = s.call(ctx, http.MethodPost, "/refunds", id.String(), url.Values{
			"payment_intent": {req.ProviderTransactionID}, "amount": {strconv.FormatInt(req.AmountCents, 10)},
		}, &refund)
	}
	if err != nil {
		return ChargeResult{}, err
	}
	if !strings.HasPrefix(refund.ID, "re_") || refund.Amount != req.AmountCents ||
		refund.Currency != strings.ToLower(req.Currency) || refund.PaymentIntent != req.ProviderTransactionID {
		return ChargeResult{}, store.ErrConflict
	}
	if err := s.store.BindStripeOperation(ctx, id, refund.ID); err != nil {
		return ChargeResult{}, err
	}
	switch refund.Status {
	case "succeeded":
		return ChargeResult{Approved: true, ProviderTransactionID: refund.ID}, nil
	case "failed", "canceled":
		return ChargeResult{Reason: "provider_refund_failed", ProviderTransactionID: refund.ID}, nil
	default:
		return ChargeResult{}, errors.New("stripe: refund outcome pending")
	}
}

type Routing struct {
	Store     *store.Store
	Simulated *DurableProvider
	Stripe    *Stripe
}

func (r *Routing) ChargeIdempotently(ctx context.Context, id uuid.UUID, req ChargeRequest) (ChargeResult, error) {
	payment, err := r.Store.GetByID(ctx, id)
	if err != nil {
		return ChargeResult{}, err
	}
	if payment.Provider == "stripe" {
		return r.Stripe.ChargeIdempotently(ctx, id, req)
	}
	return r.Simulated.ChargeIdempotently(ctx, id, req)
}

func (r *Routing) RefundIdempotently(ctx context.Context, id uuid.UUID, req RefundRequest) (ChargeResult, error) {
	payment, err := r.Store.GetByID(ctx, req.PaymentID)
	if err != nil {
		return ChargeResult{}, err
	}
	if payment.Provider == "stripe" {
		return r.Stripe.RefundIdempotently(ctx, id, req)
	}
	return r.Simulated.RefundIdempotently(ctx, id, req)
}
