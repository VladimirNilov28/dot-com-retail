package money

import "testing"

func TestParseCentsRejectsOverflowAndInvalidComponents(t *testing.T) {
	for _, amount := range []string{"92233720368547758.08", "184467440737095570.14", "1.-1", "--1.00"} {
		if cents, err := ParseCents(amount); err == nil {
			t.Errorf("invalid amount %q accepted as %d cents", amount, cents)
		}
	}
	for _, example := range []struct {
		amount string
		cents  int64
	}{
		{"54.98", 5498}, {"10.13", 1013}, {"-10.13", -1013},
		{"92233720368547758.07", 9223372036854775807},
	} {
		if cents, err := ParseCents(example.amount); err != nil || cents != example.cents {
			t.Errorf("amount=%q cents=%d error=%v", example.amount, cents, err)
		}
	}
}
