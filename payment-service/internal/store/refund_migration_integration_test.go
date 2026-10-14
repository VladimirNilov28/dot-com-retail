package store

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/domain"
)

func TestRefundMigrationFrom0002HistoricalSimulator(t *testing.T) {
	ctx := context.Background()
	pg, err := tcpostgres.Run(ctx, "postgres:17",
		tcpostgres.WithDatabase("payments"), tcpostgres.WithUsername("test"), tcpostgres.WithPassword("test"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = testcontainers.TerminateContainer(pg) })
	dsn, err := pg.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	sql, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer sql.Close(ctx)
	for _, name := range []string{"0001_init.up.sql", "0002_guest_ownership.up.sql"} {
		body, err := migrationsFS.ReadFile("migrations/" + name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := sql.Exec(ctx, string(body)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := sql.Exec(ctx, "CREATE TABLE schema_migrations(version bigint NOT NULL PRIMARY KEY,dirty boolean NOT NULL); INSERT INTO schema_migrations VALUES(2,false)"); err != nil {
		t.Fatal(err)
	}
	ids := []uuid.UUID{uuid.New(), uuid.New(), uuid.New()}
	for i, status := range []domain.Status{domain.StatusSucceeded, domain.StatusFailed, domain.StatusRequested} {
		amount := int64(5498)
		if status == domain.StatusSucceeded {
			amount = 1013
		}
		_, err := sql.Exec(ctx, `INSERT INTO payments(id,request_event_id,order_id,user_id,amount_cents,currency,status,failure_reason)
			VALUES($1,$2,$3,NULL,$4,'EUR',$5,$6)`, ids[i], uuid.New(), i+1, amount, status, "historical decline")
		if err != nil {
			t.Fatal(err)
		}
	}
	s, err := New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.Migrate(dsn); err != nil {
		t.Fatal(err)
	}
	if err := s.Migrate(dsn); err != nil {
		t.Fatal("migration not idempotent:", err)
	}
	var terminal, unresolved, simulation int
	err = sql.QueryRow(ctx, `SELECT count(*),count(*) FILTER(WHERE payment_id=$1),
		count(*) FILTER(WHERE simulation AND historical) FROM provider_ledger`, ids[2]).Scan(&terminal, &unresolved, &simulation)
	if err != nil || terminal != 2 || unresolved != 0 || simulation != 2 {
		t.Fatalf("historical seed terminal=%d unresolved=%d simulation=%d err=%v", terminal, unresolved, simulation, err)
	}
	// Historical success is authoritative simulated history, even when its
	// amount ends in .13; migration must not re-run the decline rule.
	approved, err := s.LedgerCharge(ctx, ids[0], 1, 1013, "EUR")
	if err != nil || !approved.Approved {
		t.Fatalf("historical success lost: %+v %v", approved, err)
	}
	refund, err := s.LedgerRefund(ctx, uuid.New(), ids[0], 1, 1013, "EUR", "")
	if err != nil || !refund.Approved {
		t.Fatalf("legacy original UUID resolution failed: %+v %v", refund, err)
	}
	if _, err := s.LedgerRefund(ctx, uuid.New(), ids[1], 2, 5498, "EUR", ""); err == nil {
		t.Fatal("historical declined original was refunded")
	}
	if _, err := s.LedgerRefund(ctx, uuid.New(), ids[2], 3, 5498, "EUR", ""); err == nil {
		t.Fatal("unresolved historical original was refunded")
	}
	for _, statement := range []string{
		"UPDATE provider_ledger SET amount_cents=1",
		"DELETE FROM provider_ledger",
		"UPDATE payments SET amount_cents=1 WHERE status='SUCCEEDED'",
		"UPDATE payments SET status='SUCCEEDED' WHERE status='FAILED'",
	} {
		if _, err := sql.Exec(ctx, statement); err == nil {
			t.Fatalf("immutable financial identity/outcome changed: %s", statement)
		}
	}
	// Exercise the embedded down/up SQL only on this disposable fixture.
	for _, name := range []string{"0003_refunds_provider_ledger.down.sql", "0003_refunds_provider_ledger.up.sql"} {
		body, err := migrationsFS.ReadFile("migrations/" + name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := sql.Exec(ctx, string(body)); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
	}
}
