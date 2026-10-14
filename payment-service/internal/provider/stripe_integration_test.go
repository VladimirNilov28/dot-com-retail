package provider

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

func TestStripeIntentCreationUsesDurableIdentityAndFailClosedExpiry(t *testing.T) {
	ctx := context.Background()
	container, err := tcpostgres.Run(ctx, "postgres:17", tcpostgres.WithDatabase("stripe_test"),
		tcpostgres.WithUsername("test"), tcpostgres.WithPassword("test"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").WithOccurrence(2)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = testcontainers.TerminateContainer(container) })
	dsn, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	db, err := store.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.Migrate(dsn); err != nil {
		t.Fatal(err)
	}
	payment := domain.Payment{ID: uuid.New(), RequestEventID: uuid.New(), OrderID: 74, AmountCents: 4497, Currency: "EUR", Status: domain.StatusRequested, Provider: "stripe"}
	if _, err := db.SaveRequested(ctx, payment); err != nil {
		t.Fatal(err)
	}
	var creations atomic.Int32
	intent := StripeIntent{ID: "pi_fixture", ClientSecret: "pi_fixture_secret_fixture", Status: "requires_payment_method", Amount: 4497, Currency: "eur",
		Metadata: map[string]string{"payment_id": payment.ID.String(), "order_id": "74"}}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			creations.Add(1)
			if r.Header.Get("Idempotency-Key") != payment.ID.String() {
				t.Error("noncanonical provider key")
			}
			if err := r.ParseForm(); err != nil {
				t.Error(err)
			}
			if r.Form.Get("amount") != "4497" || r.Form.Get("currency") != "eur" || r.Form.Get("payment_method_types[]") != "card" {
				t.Error("incorrect authoritative parameters")
			}
			for _, key := range []string{"card[number]", "card[cvc]", "payment_method", "confirm"} {
				if r.Form.Has(key) {
					t.Error("server received instrument/confirmation data")
				}
			}
		}
		_ = json.NewEncoder(w).Encode(intent)
	}))
	defer server.Close()
	stripe, err := NewStripe(db, "sk_test_fixture")
	if err != nil {
		t.Fatal(err)
	}
	stripe.base = server.URL
	req := ChargeRequest{OrderID: 74, AmountCents: 4497, Currency: "EUR"}
	for i := 0; i < 2; i++ {
		result, err := stripe.ChargeIdempotently(ctx, payment.ID, req)
		if err != nil || !result.Pending || result.Approved {
			t.Fatalf("pending intent: %+v %v", result, err)
		}
	}
	if creations.Load() != 1 {
		t.Fatal("replay created another intent")
	}
	original, err := db.GetByID(ctx, payment.ID)
	if err != nil || original.Status != domain.StatusRequested {
		t.Fatal("intent creation prematurely settled payment")
	}
	req.AmountCents++
	if _, err := stripe.ChargeIdempotently(ctx, payment.ID, req); !errors.Is(err, store.ErrConflict) {
		t.Fatal("changed amount accepted")
	}
	expired := uuid.New()
	if _, _, err := db.StripeOperation(ctx, expired, "intent"); err != nil {
		t.Fatal(err)
	}
	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	if _, err := conn.Exec(ctx, "UPDATE stripe_operations SET created_at=$2 WHERE id=$1", expired, time.Now().Add(-24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, err := stripe.ChargeIdempotently(ctx, expired, req); err == nil {
		t.Fatal("expired unknown creation retried")
	}
	if creations.Load() != 1 {
		t.Fatal("expired key submitted to provider")
	}
	second := domain.Payment{ID: uuid.New(), RequestEventID: uuid.New(), OrderID: 76, AmountCents: 1999, Currency: "EUR", Status: domain.StatusRequested, Provider: "stripe"}
	if _, err := db.SaveRequested(ctx, second); err != nil {
		t.Fatal(err)
	}
	if _, _, err := db.StripeOperation(ctx, second.ID, "intent"); err != nil {
		t.Fatal(err)
	}
	if err := db.BindStripeOperation(ctx, second.ID, "pi_second"); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Exec(ctx, "UPDATE payments SET updated_at=$2 WHERE id=$1", payment.ID, time.Now().Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	pending, err := db.PendingStripePayments(ctx)
	if err != nil || len(pending) != 2 || pending[0].ID != payment.ID {
		t.Fatal("pending reconciliation must start with oldest attempt")
	}
	if err := db.TouchStripePayment(ctx, payment.ID); err != nil {
		t.Fatal(err)
	}
	pending, err = db.PendingStripePayments(ctx)
	if err != nil || len(pending) != 2 || pending[0].ID != second.ID {
		t.Fatal("a failed reconciliation attempt must not starve other pending orders")
	}
}

func TestStripeBindingRejectsLiveModeAndAlteredFinancialFacts(t *testing.T) {
	id := uuid.New()
	req := ChargeRequest{OrderID: 75, AmountCents: 1999, Currency: "EUR"}
	intent := StripeIntent{ID: "pi_fixture", Amount: 1999, Currency: "eur", Metadata: map[string]string{"payment_id": id.String(), "order_id": strconv.Itoa(75)}}
	if err := ValidateIntent(intent, id, req); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*StripeIntent){
		func(i *StripeIntent) { i.LiveMode = true }, func(i *StripeIntent) { i.Amount++ },
		func(i *StripeIntent) { i.Currency = "usd" }, func(i *StripeIntent) { i.ID = "invalid" },
		func(i *StripeIntent) {
			i.Metadata = map[string]string{"payment_id": uuid.NewString(), "order_id": "75"}
		},
	} {
		altered := intent
		change(&altered)
		if err := ValidateIntent(altered, id, req); err == nil {
			t.Fatal("altered binding accepted")
		}
	}
	if _, err := NewStripe(nil, "sk_live_fixture"); err == nil {
		t.Fatal("live key accepted")
	}
}
