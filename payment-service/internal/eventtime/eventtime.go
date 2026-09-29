// Package eventtime provides an Instant type that (de)serializes exactly like
// Jackson's default jackson-datatype-jsr310 behavior for java.time.Instant:
// a JSON number of seconds since the epoch, with the fractional part
// carrying nanosecond precision (e.g. 1790714974.152856514). The Spring
// backend's PaymentRequestedEvent/PaymentSucceededEvent/PaymentFailedEvent
// records all serialize/deserialize Instant fields this way, so the Go side
// of the Kafka contract must match it exactly instead of assuming ISO-8601.
package eventtime

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Instant is a wire-compatible timestamp matching Jackson's default Instant
// JSON representation (epoch seconds as a decimal number).
type Instant struct {
	time.Time
}

// Now returns the current time as an Instant.
func Now() Instant {
	return Instant{time.Now().UTC()}
}

// FromTime wraps a time.Time as an Instant.
func FromTime(t time.Time) Instant {
	return Instant{t}
}

// MarshalJSON writes the instant as <seconds>.<nanoseconds>, matching
// Jackson's WRITE_DATES_AS_TIMESTAMPS default for Instant.
func (i Instant) MarshalJSON() ([]byte, error) {
	sec := i.Time.Unix()
	nsec := i.Time.Nanosecond()
	if nsec == 0 {
		return []byte(strconv.FormatInt(sec, 10)), nil
	}
	// Nine-digit, zero-padded fractional part, trailing zeros trimmed to
	// match typical Jackson output (e.g. ".152856514", not ".152856514000").
	frac := fmt.Sprintf("%09d", nsec)
	frac = strings.TrimRight(frac, "0")
	return []byte(fmt.Sprintf("%d.%s", sec, frac)), nil
}

// UnmarshalJSON accepts either the numeric epoch-seconds form produced by
// Jackson or a quoted RFC3339 string, so the consumer stays lenient about
// malformed-but-recoverable payloads from other producers/tests.
func (i *Instant) UnmarshalJSON(data []byte) error {
	s := strings.TrimSpace(string(data))
	if s == "null" || s == "" {
		i.Time = time.Time{}
		return nil
	}
	if strings.HasPrefix(s, "\"") {
		var str string
		str = strings.Trim(s, "\"")
		t, err := time.Parse(time.RFC3339Nano, str)
		if err != nil {
			return fmt.Errorf("eventtime: invalid quoted instant %q: %w", s, err)
		}
		i.Time = t.UTC()
		return nil
	}

	dot := strings.IndexByte(s, '.')
	var sec int64
	var nsec int64
	var err error
	if dot < 0 {
		sec, err = strconv.ParseInt(s, 10, 64)
		if err != nil {
			return fmt.Errorf("eventtime: invalid numeric instant %q: %w", s, err)
		}
	} else {
		sec, err = strconv.ParseInt(s[:dot], 10, 64)
		if err != nil {
			return fmt.Errorf("eventtime: invalid numeric instant %q: %w", s, err)
		}
		fracStr := (s[dot+1:] + "000000000")[:9]
		nsec, err = strconv.ParseInt(fracStr, 10, 64)
		if err != nil {
			return fmt.Errorf("eventtime: invalid numeric instant %q: %w", s, err)
		}
	}
	i.Time = time.Unix(sec, nsec).UTC()
	return nil
}
