// Command server is the Payment Service entrypoint: config -> DB pool ->
// migrate -> Kafka consumer/outbox publisher -> HTTP health server ->
// graceful shutdown on SIGTERM/SIGINT.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/config"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/health"
	paymentkafka "github.com/VladimirNilov28/dot-com-retail/payment-service/internal/kafka"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/provider"
	"github.com/VladimirNilov28/dot-com-retail/payment-service/internal/store"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)

	if err := run(logger); err != nil {
		logger.Error("payment-service exited with error", "error", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	db, err := store.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer db.Close()

	if err := db.Migrate(cfg.DatabaseURL); err != nil {
		return err
	}
	logger.Info("migrations applied")

	// Create topics up front rather than relying on lazy broker
	// auto-creation: a consumer group's first JoinGroup can otherwise race
	// a not-yet-existing topic, get assigned zero partitions, and never
	// rebalance again even after the topic appears - silently halting all
	// payment processing with no error logged.
	if err := paymentkafka.EnsureTopics(cfg.KafkaBrokers); err != nil {
		return err
	}

	fakeProvider := provider.NewDurableProvider(db)

	consumer := paymentkafka.NewRequestConsumer(cfg.KafkaBrokers, cfg.ConsumerGroupID, db, fakeProvider, logger)
	defer func() { _ = consumer.Close() }()

	refunds := paymentkafka.NewRefundConsumer(cfg.KafkaBrokers, cfg.ConsumerGroupID, db, fakeProvider, logger)
	defer func() { _ = refunds.Close() }()

	publisher := paymentkafka.NewOutboxPublisher(
		cfg.KafkaBrokers, db, time.Duration(cfg.OutboxPollIntervalMillis)*time.Millisecond, logger,
	)
	defer func() { _ = publisher.Close() }()

	var workers sync.WaitGroup
	workers.Add(3)
	go func() {
		defer workers.Done()
		publisher.Run(ctx)
	}()
	go func() {
		defer workers.Done()
		if err := consumer.Run(ctx); err != nil {
			logger.Error("payment.requested consumer stopped with error", "error", err)
			stop()
		}
	}()
	go func() {
		defer workers.Done()
		if err := refunds.Run(ctx); err != nil {
			logger.Error("refund.requested consumer stopped with error", "error", err)
			stop()
		}
	}()

	mux := health.NewMux(db)
	httpServer := &http.Server{Addr: ":" + cfg.HTTPPort, Handler: mux}
	go func() {
		logger.Info("health server listening", "port", cfg.HTTPPort)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("health server stopped with error", "error", err)
		}
	}()

	<-ctx.Done()
	logger.Info("shutting down payment-service")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = httpServer.Shutdown(shutdownCtx)
	workers.Wait()

	return nil
}
