package stripehttp

import (
	"testing"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
)

func failedIntent(code, declineCode, charge string) provider.StripeIntent {
	return provider.StripeIntent{
		ID: "pi_fixture", Status: "requires_payment_method",
		LastError: &provider.StripeError{Type: "card_error", Code: code, DeclineCode: declineCode, Charge: charge},
	}
}

func TestClassifyFailureMapsDefiniteDeclinesToSafeReasons(t *testing.T) {
	for _, tc := range []struct{ code, declineCode, reason string }{
		{"card_declined", "insufficient_funds", "insufficient_funds"},
		{"expired_card", "expired_card", "expired_card"},
		{"card_declined", "expired_card", "expired_card"},
		{"incorrect_cvc", "incorrect_cvc", "incorrect_cvc"},
		{"invalid_cvc", "", "incorrect_cvc"},
		{"incorrect_number", "incorrect_number", "incorrect_number"},
		{"invalid_number", "", "incorrect_number"},
		{"card_declined", "generic_decline", "card_declined"},
		// Fraud signals must never be revealed to the shopper.
		{"card_declined", "stolen_card", "card_declined"},
		{"card_declined", "", "card_declined"},
		{"payment_intent_authentication_failure", "", "authentication_failed"},
	} {
		outcome, reason, attempt := ClassifyFailure(failedIntent(tc.code, tc.declineCode, "ch_fixture1"))
		if outcome != OutcomeDeclined || reason != tc.reason || attempt != "ch_fixture1" {
			t.Fatalf("%s/%s: got outcome=%d reason=%q attempt=%q", tc.code, tc.declineCode, outcome, reason, attempt)
		}
	}
}

func TestClassifyFailureKeepsTransientAndUnknownUnresolved(t *testing.T) {
	for _, tc := range []struct{ code, declineCode string }{
		{"processing_error", "processing_error"},
		{"processing_error", ""},
		{"card_declined", "try_again_later"},
		{"card_declined", "issuer_not_available"},
		{"api_connection_error", ""},
		{"rate_limit", ""},
		{"", ""},
		{"some_future_code", ""},
	} {
		outcome, reason, attempt := ClassifyFailure(failedIntent(tc.code, tc.declineCode, "ch_fixture1"))
		if outcome != OutcomeUnresolved || reason != "" || attempt != "" {
			t.Fatalf("%s/%s must stay unresolved, got %d %q", tc.code, tc.declineCode, outcome, reason)
		}
	}
}

func TestClassifyFailureIgnoresIntentsWithoutAFailedAttempt(t *testing.T) {
	for _, intent := range []provider.StripeIntent{
		{ID: "pi_fixture", Status: "requires_payment_method"},
		{ID: "pi_fixture", Status: "processing"},
		{ID: "pi_fixture", Status: "succeeded", LastError: &provider.StripeError{Code: "card_declined"}},
	} {
		if outcome, _, _ := ClassifyFailure(intent); outcome != OutcomeNone {
			t.Fatalf("%s: unexpected outcome %d", intent.Status, outcome)
		}
	}
}

func TestAttemptIDIsStableBoundedAndSafeWithoutCharge(t *testing.T) {
	intent := failedIntent("incorrect_number", "", "")
	intent.LastError.PaymentMethod = &struct {
		ID string `json:"id"`
	}{ID: "pm_fixture"}
	_, _, first := ClassifyFailure(intent)
	_, _, again := ClassifyFailure(intent)
	if first != again || len(first) > 64 || !safeReference.MatchString(first) {
		t.Fatalf("unstable/unsafe attempt id %q", first)
	}
	intent.LastError.PaymentMethod.ID = "pm_other"
	if _, _, other := ClassifyFailure(intent); other == first {
		t.Fatal("different payment methods must be different attempts")
	}
	// Unsafe provider references are never echoed into the attempt id.
	_, _, hashed := ClassifyFailure(failedIntent("expired_card", "", "ch_bad/../x"))
	if !safeReference.MatchString(hashed) || hashed == "ch_bad/../x" {
		t.Fatalf("unsafe charge reference forwarded: %q", hashed)
	}
}
