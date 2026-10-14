package events

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/eventtime"
)

func TestRefundAndProgressUseISOInstant(t *testing.T) {
	input := `{"eventId":"10000000-0000-0000-0000-000000000001","refundId":"20000000-0000-0000-0000-000000000001","requestEventId":"30000000-0000-0000-0000-000000000001","orderId":42,"paymentId":"40000000-0000-0000-0000-000000000001","amount":54.98,"currency":"EUR","occurredAt":"2026-10-05T13:00:00Z"}`
	var request RefundRequestedEvent
	if err := json.Unmarshal([]byte(input), &request); err != nil {
		t.Fatal(err)
	}
	for _, event := range []any{
		request,
		RefundSucceededEvent{OccurredAt: request.OccurredAt},
		RefundFailedEvent{OccurredAt: request.OccurredAt},
		RefundUnresolvedEvent{OccurredAt: request.OccurredAt},
		PaymentUnresolvedEvent{OccurredAt: eventtime.FromTime(request.OccurredAt.Time)},
	} {
		payload, err := json.Marshal(event)
		if err != nil || !strings.Contains(string(payload), `"occurredAt":"2026-10-05T13:00:00Z"`) {
			t.Fatalf("new contract must emit ISO instant: %s error=%v", payload, err)
		}
	}
}

func TestPaymentSucceededLegacyPayloadStillReadable(t *testing.T) {
	var legacy PaymentSucceededEvent
	if err := json.Unmarshal([]byte(`{"eventId":"10000000-0000-0000-0000-000000000001","requestEventId":"30000000-0000-0000-0000-000000000001","orderId":42,"paymentId":"40000000-0000-0000-0000-000000000001","occurredAt":1791205200}`), &legacy); err != nil {
		t.Fatal(err)
	}
	if legacy.ProviderTransactionID != "" || legacy.PaymentID == uuid.Nil {
		t.Fatalf("old success not readable: %+v", legacy)
	}
	legacy.ProviderTransactionID = "sim-charge-" + legacy.PaymentID.String()
	payload, err := json.Marshal(legacy)
	if err != nil || !strings.Contains(string(payload), `"providerTransactionId":"sim-charge-`) {
		t.Fatalf("additive reference missing: %s %v", payload, err)
	}
}
