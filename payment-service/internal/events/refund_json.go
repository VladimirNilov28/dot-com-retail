package events

import (
	"encoding/json"
	"time"
)

// New refund/progress contracts emit ISO instants while the older payment
// contracts retain their numeric Jackson-compatible timestamp representation.
func (e RefundRequestedEvent) MarshalJSON() ([]byte, error) {
	type fields RefundRequestedEvent
	return json.Marshal(struct {
		fields
		OccurredAt time.Time `json:"occurredAt"`
	}{fields(e), e.OccurredAt.Time})
}

func (e RefundSucceededEvent) MarshalJSON() ([]byte, error) {
	type fields RefundSucceededEvent
	return json.Marshal(struct {
		fields
		OccurredAt time.Time `json:"occurredAt"`
	}{fields(e), e.OccurredAt.Time})
}

func (e RefundFailedEvent) MarshalJSON() ([]byte, error) {
	type fields RefundFailedEvent
	return json.Marshal(struct {
		fields
		OccurredAt time.Time `json:"occurredAt"`
	}{fields(e), e.OccurredAt.Time})
}

func (e PaymentUnresolvedEvent) MarshalJSON() ([]byte, error) {
	type fields PaymentUnresolvedEvent
	return json.Marshal(struct {
		fields
		OccurredAt time.Time `json:"occurredAt"`
	}{fields(e), e.OccurredAt.Time})
}
