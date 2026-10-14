package kafka

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"

	kafkago "github.com/segmentio/kafka-go"
)

type controlledReader struct {
	fetch   func(context.Context) (kafkago.Message, error)
	commits atomic.Int32
}

func (r *controlledReader) FetchMessage(ctx context.Context) (kafkago.Message, error) {
	return r.fetch(ctx)
}

func (r *controlledReader) CommitMessages(context.Context, ...kafkago.Message) error {
	r.commits.Add(1)
	return nil
}

func (r *controlledReader) Close() error { return nil }

func controlledConsumer(r requestReader) *RequestConsumer {
	closed, cancel := context.WithCancel(context.Background())
	return &RequestConsumer{
		reader: r, dlqWriter: &kafkago.Writer{},
		logger: slog.New(slog.NewTextHandler(io.Discard, nil)), closed: closed, cancel: cancel,
	}
}

func TestRequestConsumerShutdown(t *testing.T) {
	for _, mode := range []string{"cancel", "deadline", "close"} {
		t.Run(mode, func(t *testing.T) {
			entered := make(chan struct{})
			r := &controlledReader{fetch: func(ctx context.Context) (kafkago.Message, error) {
				close(entered)
				<-ctx.Done()
				return kafkago.Message{}, ctx.Err()
			}}
			c := controlledConsumer(r)
			defer c.Close()
			ctx, cancel := context.WithCancel(context.Background())
			if mode == "deadline" {
				ctx, cancel = context.WithTimeout(context.Background(), 100*time.Millisecond)
			}
			defer cancel()
			done := make(chan error, 1)
			go func() { done <- c.Run(ctx) }()
			select {
			case <-entered:
			case <-time.After(time.Second):
				t.Fatal("fetch never started")
			}
			switch mode {
			case "cancel":
				cancel()
			case "close":
				if err := c.Close(); err != nil {
					t.Fatal(err)
				}
			}
			select {
			case err := <-done:
				if err != nil {
					t.Fatalf("shutdown returned an error: %v", err)
				}
			case <-time.After(time.Second):
				t.Fatal("Run hung on shutdown")
			}
			if r.commits.Load() != 0 {
				t.Fatal("shutdown committed an unhandled record")
			}
		})
	}
}

func TestRequestConsumerFetchFailure(t *testing.T) {
	want := errors.New("fatal reader failure")
	r := &controlledReader{fetch: func(context.Context) (kafkago.Message, error) { return kafkago.Message{}, want }}
	c := controlledConsumer(r)
	defer c.Close()
	if err := c.Run(context.Background()); !errors.Is(err, want) {
		t.Fatalf("fatal fetch failure swallowed: %v", err)
	}
}

func TestRequestConsumerDLQRequiresAcknowledgement(t *testing.T) {
	c := NewRequestConsumer([]string{"unused:9092"}, "unused", nil, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	defer c.Close()
	if c.dlqWriter.RequiredAcks != kafkago.RequireAll || c.dlqWriter.Async {
		t.Fatalf("DLQ writer must await durable broker acknowledgement: RequiredAcks=%d Async=%t",
			c.dlqWriter.RequiredAcks, c.dlqWriter.Async)
	}
}

func TestRequestConsumerClosedBeforeRun(t *testing.T) {
	var fetched atomic.Bool
	r := &controlledReader{fetch: func(context.Context) (kafkago.Message, error) {
		fetched.Store(true)
		return kafkago.Message{}, errors.New("reader is closed")
	}}
	c := controlledConsumer(r)
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if err := c.Run(context.Background()); err != nil {
		t.Errorf("closed consumer Run returned an error: %v", err)
	}
	if fetched.Load() {
		t.Error("closed consumer still fetched a record")
	}
}

func TestRequestConsumerCloseDuringFetch(t *testing.T) {
	for i := 0; i < 50; i++ {
		var c *RequestConsumer
		r := &controlledReader{fetch: func(context.Context) (kafkago.Message, error) {
			_ = c.Close()
			return kafkago.Message{}, io.EOF
		}}
		c = controlledConsumer(r)
		if err := c.Run(context.Background()); err != nil {
			t.Fatalf("reader closure raced cancellation: %v", err)
		}
	}
}
