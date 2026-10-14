// Package health exposes liveness/readiness HTTP endpoints for the Payment
// Service.
package health

import (
	"context"
	"net/http"
)

// Pinger is anything that can verify connectivity to a dependency.
type Pinger interface {
	Ping(ctx context.Context) error
}

// NewMux builds an http.ServeMux with:
//   - GET /healthz - liveness: process is up, always 200.
//   - GET /readyz  - readiness: dependencies (Postgres) are reachable.
func NewMux(db Pinger) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
		if err := db.Ping(r.Context()); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte("not ready: " + err.Error()))
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ready"))
	})

	return mux
}
