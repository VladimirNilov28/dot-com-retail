package stripehttp

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
)

// TestStripeSandboxDeclineMapping confirms disposable test-mode PaymentIntents
// with Stripe's documented test PaymentMethods and classifies the real
// responses. Opt-in only: STRIPE_SANDBOX_LIVE_TEST=1 and an sk_test_ key.
func TestStripeSandboxDeclineMapping(t *testing.T) {
	key := os.Getenv("STRIPE_SECRET_KEY")
	if os.Getenv("STRIPE_SANDBOX_LIVE_TEST") != "1" || !strings.HasPrefix(key, "sk_test_") {
		t.Skip("live Stripe sandbox check is opt-in (STRIPE_SANDBOX_LIVE_TEST=1 with a test-mode key)")
	}
	cases := []struct {
		pm      string
		outcome Outcome
		reason  string
	}{
		{"pm_card_chargeDeclinedInsufficientFunds", OutcomeDeclined, "insufficient_funds"},
		{"pm_card_chargeDeclinedExpiredCard", OutcomeDeclined, "expired_card"},
		{"pm_card_chargeDeclinedIncorrectCvc", OutcomeDeclined, "incorrect_cvc"},
		{"pm_card_chargeDeclinedProcessingError", OutcomeUnresolved, ""},
		{"pm_card_visa", OutcomeNone, ""},
	}
	client := &http.Client{Timeout: 30 * time.Second}
	for _, tc := range cases {
		t.Run(tc.pm, func(t *testing.T) {
			form := url.Values{
				"amount": {"1999"}, "currency": {"eur"}, "confirm": {"true"},
				"payment_method": {tc.pm}, "automatic_payment_methods[enabled]": {"true"}, "automatic_payment_methods[allow_redirects]": {"never"},
				"metadata[purpose]": {"dcr-decline-mapping-disposable"},
			}
			req, _ := http.NewRequest(http.MethodPost, "https://api.stripe.com/v1/payment_intents", strings.NewReader(form.Encode()))
			req.SetBasicAuth(key, "")
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			res, err := client.Do(req)
			if err != nil {
				t.Fatalf("stripe request failed: %T", err)
			}
			defer res.Body.Close()
			var body struct {
				provider.StripeIntent
				Error *struct {
					Type          string                 `json:"type"`
					Code          string                 `json:"code"`
					Param         string                 `json:"param"`
					Message       string                 `json:"message"`
					PaymentIntent *provider.StripeIntent `json:"payment_intent"`
				} `json:"error"`
			}
			if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
				t.Fatalf("decode: %v", err)
			}
			intent := body.StripeIntent
			if body.Error != nil {
				if body.Error.PaymentIntent == nil {
					t.Fatalf("stripe error without payment_intent (HTTP %d %s/%s param=%s %s)", res.StatusCode, body.Error.Type, body.Error.Code, body.Error.Param, body.Error.Message)
				}
				intent = *body.Error.PaymentIntent
			}
			if intent.LiveMode {
				t.Fatal("refusing live-mode intent")
			}
			outcome, reason, attempt := ClassifyFailure(intent)
			t.Logf("intent=%s status=%s code=%s outcome=%d reason=%q attempt=%s", intent.ID, intent.Status,
				errCode(intent), outcome, reason, attempt)
			if outcome != tc.outcome || reason != tc.reason {
				t.Fatalf("got outcome=%d reason=%q, want %d %q", outcome, reason, tc.outcome, tc.reason)
			}
			if outcome == OutcomeDeclined && !strings.HasPrefix(attempt, "ch_") {
				t.Fatalf("expected the failed charge id as attempt id, got %q", attempt)
			}
			if outcome != OutcomeDeclined {
				return
			}
			// Retry on the SAME PaymentIntent with a good card: a decline is not terminal.
			retry, _ := http.NewRequest(http.MethodPost, "https://api.stripe.com/v1/payment_intents/"+url.PathEscape(intent.ID)+"/confirm",
				strings.NewReader(url.Values{"payment_method": {"pm_card_visa"}}.Encode()))
			retry.SetBasicAuth(key, "")
			retry.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			retryRes, err := client.Do(retry)
			if err != nil {
				t.Fatalf("stripe retry failed: %T", err)
			}
			defer retryRes.Body.Close()
			var retried provider.StripeIntent
			if err := json.NewDecoder(retryRes.Body).Decode(&retried); err != nil {
				t.Fatalf("decode retry: %v", err)
			}
			t.Logf("retry intent=%s status=%s", retried.ID, retried.Status)
			if retried.ID != intent.ID || retried.Status != "succeeded" {
				t.Fatalf("same-intent retry got id=%s status=%s", retried.ID, retried.Status)
			}
		})
	}
}

func errCode(i provider.StripeIntent) string {
	if i.LastError == nil {
		return ""
	}
	return i.LastError.Code + "/" + i.LastError.DeclineCode
}
