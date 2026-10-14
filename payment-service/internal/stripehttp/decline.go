package stripehttp

import (
	"crypto/sha256"
	"encoding/hex"
	"regexp"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
)

// Outcome classifies a PaymentIntent that returned to requires_payment_method.
type Outcome int

const (
	// OutcomeNone: no failed attempt to report (no last_payment_error).
	OutcomeNone Outcome = iota
	// OutcomeDeclined: the issuer/provider definitively refused this attempt.
	// The order stays retryable on the same PaymentIntent.
	OutcomeDeclined
	// OutcomeUnresolved: transient/unknown failure (processing_error,
	// issuer unavailable, unrecognised codes). Never presented as a decline.
	OutcomeUnresolved
)

// Transient issuer answers that are not a definite refusal of the card.
var transientDeclineCodes = map[string]bool{
	"processing_error": true, "try_again_later": true, "issuer_not_available": true,
	"reenter_transaction": true, "approve_with_id": true,
}

// Definite decline codes mapped to the small allow-listed reason vocabulary
// shared with the backend/UI. Fraud-signal codes (lost/stolen/fraudulent,
// pickup_card, ...) intentionally collapse into the generic card_declined.
var declineReasons = map[string]string{
	"insufficient_funds":                    "insufficient_funds",
	"expired_card":                          "expired_card",
	"incorrect_cvc":                         "incorrect_cvc",
	"invalid_cvc":                           "incorrect_cvc",
	"incorrect_number":                      "incorrect_number",
	"invalid_number":                        "incorrect_number",
	"invalid_expiry_month":                  "invalid_expiry",
	"invalid_expiry_year":                   "invalid_expiry",
	"card_not_supported":                    "card_not_supported",
	"currency_not_supported":                "card_not_supported",
	"authentication_required":               "authentication_failed",
	"payment_intent_authentication_failure": "authentication_failed",
}

var safeReference = regexp.MustCompile(`^[A-Za-z0-9_]{1,60}$`)

// ClassifyFailure maps a PaymentIntent's last_payment_error to a definite
// decline (with a safe reason code and a per-attempt dedupe key) or to an
// unresolved outcome. Only intent.Status == requires_payment_method carries
// a meaningful failed attempt; other states return OutcomeNone.
func ClassifyFailure(intent provider.StripeIntent) (Outcome, string, string) {
	failure := intent.LastError
	if intent.Status != "requires_payment_method" || failure == nil {
		return OutcomeNone, "", ""
	}
	reason, ok := "", false
	switch failure.Code {
	case "card_declined":
		if transientDeclineCodes[failure.DeclineCode] {
			return OutcomeUnresolved, "", ""
		}
		reason, ok = declineReasons[failure.DeclineCode]
		if !ok {
			reason, ok = "card_declined", true
		}
	default:
		reason, ok = declineReasons[failure.Code]
	}
	if !ok {
		return OutcomeUnresolved, "", ""
	}
	return OutcomeDeclined, reason, attemptID(intent)
}

// attemptID identifies the failed provider attempt: the declined charge id
// when Stripe created one, otherwise a digest of the payment method and code
// (e.g. pre-charge validation failures). It is bounded for the backend's
// 64-character notification reference.
func attemptID(intent provider.StripeIntent) string {
	failure := intent.LastError
	// intent.latest_charge is deliberately not used: it may belong to an
	// earlier attempt and would wrongly deduplicate this one.
	if safeReference.MatchString(failure.Charge) {
		return failure.Charge
	}
	method := ""
	if failure.PaymentMethod != nil {
		method = failure.PaymentMethod.ID
	}
	digest := sha256.Sum256([]byte(intent.ID + "|" + method + "|" + failure.Code + "|" + failure.DeclineCode))
	return "att_" + hex.EncodeToString(digest[:16])
}
