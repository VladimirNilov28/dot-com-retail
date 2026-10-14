package events

import (
	"encoding/json"
	"testing"
)

func TestGuestRequestPreservesNullUser(t *testing.T) {
	var request PaymentRequestedEvent
	if err := json.Unmarshal([]byte(`{"eventId":"00000000-0000-0000-0000-000000000001","orderId":1,"userId":null,"amount":24.98,"currency":"EUR","occurredAt":"2026-10-05T12:00:00Z"}`), &request); err != nil {
		t.Fatal(err)
	}
	if request.UserID != nil {
		t.Fatal("guest owner was converted to an authenticated identity")
	}
	encoded, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &fields); err != nil {
		t.Fatal(err)
	}
	if string(fields["userId"]) != "null" {
		t.Fatal("guest userId must round-trip as null")
	}
}
