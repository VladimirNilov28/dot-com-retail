// Package money converts the decimal string amounts carried in Kafka
// payloads (originating from a Java BigDecimal with scale 2, per the
// orders.total_amount decimal(10,2) column) into integer cents, so payment
// amounts are never compared or stored as floating point.
package money

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseCents converts a decimal amount string (e.g. "39.98", "10", "10.1")
// with at most two fractional digits into integer cents (3998, 1000, 1010).
func ParseCents(amount string) (int64, error) {
	amount = strings.TrimSpace(amount)
	if amount == "" {
		return 0, fmt.Errorf("money: empty amount")
	}
	neg := false
	if strings.HasPrefix(amount, "-") {
		neg = true
		amount = amount[1:]
	}

	whole, frac, hasFrac := strings.Cut(amount, ".")
	if hasFrac {
		if len(frac) > 2 {
			return 0, fmt.Errorf("money: amount %q has more than 2 fractional digits", amount)
		}
		for len(frac) < 2 {
			frac += "0"
		}
	} else {
		frac = "00"
	}

	if whole == "" {
		whole = "0"
	}

	wholeVal, err := strconv.ParseInt(whole, 10, 63)
	if err != nil {
		return 0, fmt.Errorf("money: invalid amount %q: %w", amount, err)
	}
	fracVal, err := strconv.ParseInt(frac, 10, 63)
	if err != nil {
		return 0, fmt.Errorf("money: invalid amount %q: %w", amount, err)
	}

	cents := wholeVal*100 + fracVal
	if neg {
		cents = -cents
	}
	return cents, nil
}

// FormatCents renders cents back into a decimal string with 2 fractional
// digits (e.g. 3998 -> "39.98"), the inverse of ParseCents.
func FormatCents(cents int64) string {
	neg := cents < 0
	if neg {
		cents = -cents
	}
	sign := ""
	if neg {
		sign = "-"
	}
	return fmt.Sprintf("%s%d.%02d", sign, cents/100, cents%100)
}
