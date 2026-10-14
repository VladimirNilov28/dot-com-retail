package kafka_test

import (
	"log/slog"
	"os"
	"testing"
)

// testLogger wraps a slog.Logger for integration tests. It writes to
// stderr (not t.Logf) because background consumer/publisher goroutines can
// still be shutting down briefly after a test completes; logging via
// t.Logf at that point would panic ("Log in goroutine after test has
// completed").
type testLogger struct {
	logger *slog.Logger
}

func newTestLogger(t *testing.T) *testLogger {
	t.Helper()
	return &testLogger{logger: slog.New(slog.NewTextHandler(os.Stderr, nil).WithAttrs([]slog.Attr{
		slog.String("test", t.Name()),
	}))}
}

func (l *testLogger) Logger() *slog.Logger {
	return l.logger
}
