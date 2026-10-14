// Package config loads Payment Service configuration from environment
// variables only - no hardcoded secrets, matching the rest of the repo's
// Docker Compose / .env convention.
package config

import (
	"fmt"
	"os"
	"strings"
)

// Config holds all runtime configuration for the Payment Service.
type Config struct {
	// DatabaseURL is a standard postgres:// connection string for the
	// Payment Service's own database (never the backend's `retail` DB).
	DatabaseURL string

	// KafkaBrokers is a comma-separated list of bootstrap servers.
	KafkaBrokers []string

	// ConsumerGroupID is the Kafka consumer group for payment.requested.
	ConsumerGroupID string

	// HTTPPort is the port the health/readiness server listens on.
	HTTPPort string

	// OutboxPollInterval, in milliseconds, controls how often the outbox
	// publisher polls for unpublished rows.
	OutboxPollIntervalMillis int
}

// Load reads configuration from the environment, applying sensible local-dev
// defaults for anything not marked required below.
func Load() (Config, error) {
	cfg := Config{
		DatabaseURL:              getEnv("PAYMENT_DB_URL", ""),
		KafkaBrokers:             splitCSV(getEnv("KAFKA_BROKERS", "localhost:9092")),
		ConsumerGroupID:          getEnv("KAFKA_CONSUMER_GROUP", "payment-service"),
		HTTPPort:                 getEnv("HTTP_PORT", "8081"),
		OutboxPollIntervalMillis: getEnvInt("OUTBOX_POLL_INTERVAL_MS", 1000),
	}

	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("config: PAYMENT_DB_URL is required")
	}
	if len(cfg.KafkaBrokers) == 0 {
		return Config{}, fmt.Errorf("config: KAFKA_BROKERS is required")
	}

	return cfg, nil
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	v, ok := os.LookupEnv(key)
	if !ok || v == "" {
		return fallback
	}
	var parsed int
	if _, err := fmt.Sscanf(v, "%d", &parsed); err != nil {
		return fallback
	}
	return parsed
}

func splitCSV(v string) []string {
	if v == "" {
		return nil
	}
	parts := strings.Split(v, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
