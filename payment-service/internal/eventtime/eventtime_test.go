package eventtime

import (
	"encoding/json"
	"testing"
	"time"
)

// TestMarshalJSON_MatchesRealBackendPayload pins the exact wire format
// captured from the Spring backend's payment_outbox.payload (see the plan's
// wire-format investigation): a decimal epoch-seconds number, not ISO-8601.
func TestMarshalJSON_MatchesRealBackendPayload(t *testing.T) {
	// 1790714974.152856514 == 2026-09-29T13:29:34.152856514Z
	sec := int64(1790714974)
	nsec := int64(152856514)
	instant := FromTime(time.Unix(sec, nsec).UTC())

	got, err := json.Marshal(instant)
	if err != nil {
		t.Fatalf("Marshal returned error: %v", err)
	}
	want := "1790714974.152856514"
	if string(got) != want {
		t.Fatalf("Marshal() = %s, want %s", got, want)
	}
}

func TestMarshalJSON_WholeSecondHasNoFraction(t *testing.T) {
	instant := FromTime(time.Unix(1700000000, 0).UTC())
	got, err := json.Marshal(instant)
	if err != nil {
		t.Fatalf("Marshal returned error: %v", err)
	}
	if string(got) != "1700000000" {
		t.Fatalf("Marshal() = %s, want 1700000000", got)
	}
}

func TestUnmarshalJSON_RoundTripsDecimalEpochSeconds(t *testing.T) {
	var i Instant
	if err := json.Unmarshal([]byte("1790714974.152856514"), &i); err != nil {
		t.Fatalf("Unmarshal returned error: %v", err)
	}
	if i.Unix() != 1790714974 {
		t.Fatalf("Unix() = %d, want 1790714974", i.Unix())
	}
	if i.Nanosecond() != 152856514 {
		t.Fatalf("Nanosecond() = %d, want 152856514", i.Nanosecond())
	}
}

func TestUnmarshalJSON_AcceptsQuotedRFC3339(t *testing.T) {
	var i Instant
	if err := json.Unmarshal([]byte(`"2024-01-02T03:04:05Z"`), &i); err != nil {
		t.Fatalf("Unmarshal returned error: %v", err)
	}
	if i.Unix() != time.Date(2024, 1, 2, 3, 4, 5, 0, time.UTC).Unix() {
		t.Fatalf("unexpected parsed time: %v", i.Time)
	}
}

func TestUnmarshalJSON_RejectsGarbage(t *testing.T) {
	var i Instant
	if err := json.Unmarshal([]byte(`"not-a-time"`), &i); err == nil {
		t.Fatalf("expected error for garbage input, got nil")
	}
}
