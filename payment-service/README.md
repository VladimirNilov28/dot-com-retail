# Payment Service

A standalone Go service that processes payments asynchronously, communicating
with the Spring backend exclusively through Kafka. It is **not** a GraphQL
subgraph and is never reached via the Hive Router — the two services never
call each other directly, and never touch each other's databases.

```
Spring backend (OrderService.createOrder, same DB tx)
  -> payment_outbox table -> PaymentOutboxPublisher (Spring) -> Kafka topic: payment.requested
       -> Payment Service RequestConsumer
            -> idempotent INSERT into payments (unique request_event_id)
            -> Provider.ChargeIdempotently(persisted Payment ID, ...)
            -> payments status update + payment_outbox row, same DB tx
       -> Payment Service OutboxPublisher -> Kafka topic: payment.succeeded | payment.failed
  -> Spring PaymentResultListener -> verified request/receipt + OrderService transition, same DB tx
```

## Why these libraries

- **`segmentio/kafka-go`** - pure Go, no cgo, so the Docker build stays a
  simple two-stage `golang:alpine` -> `alpine` image (no librdkafka to
  compile/ship, unlike `confluent-kafka-go`).
- **`jackc/pgx/v5`** - the standard high-performance native Postgres driver
  for Go; used directly (no ORM) since the schema here is two small tables.
- **`golang-migrate/migrate`** - schema migrations embedded into the binary
  (`internal/store/migrations/*.sql` via `//go:embed`), applied idempotently
  on every startup (including restarts).
- **`google/uuid`** - matches the UUID identifiers already used throughout
  the Kafka event contract and the Spring side's `UUID` fields.
- **`testcontainers-go`** (postgres + kafka modules) - integration tests run
  against a real Postgres and a real Kafka broker, not mocks, so consumer
  group / offset / redelivery semantics are actually exercised.
- No web framework: only two trivial `net/http` endpoints (`/healthz`,
  `/readyz`) are needed.

## Persistence & idempotency

- `payments.request_event_id` has a **unique constraint**. A
  redelivered/duplicate `payment.requested` message is inserted via
  `INSERT ... ON CONFLICT (request_event_id) DO NOTHING`; the pre-existing
  row is loaded and, if already resolved (`SUCCEEDED`/`FAILED`), the message
  is treated as a safe no-op instead of triggering a second charge. If the
  row exists but is still `REQUESTED` (a crash between recording the request
  and recording its result), processing resumes with the same payment ID.
  `Provider.ChargeIdempotently` requires that UUID separately
  from the immutable charge parameters; an adapter must use its canonical
  UUID string as the gateway idempotency key. It is never a per-attempt UUID.
  All adapters must atomically deduplicate concurrent/restarted calls at the
  gateway and return the original result. Changed parameters for a key must
  fail closed. The development fake rejects missing keys/changed parameters
  and memoizes outcomes, but performs no financial side effects.
- Result events (`payment.succeeded`/`payment.failed`) are **never**
  published directly from the request-processing transaction. Instead the
  status update and the outbox row are written in one DB transaction
  (`Store.RecordResultAndEnqueue`), and a separate poller
  (`kafka.OutboxPublisher`) publishes unpublished rows to Kafka afterwards.
  This is the transactional outbox pattern - the same pattern used on the
  Spring side - and avoids the dual-write hazard (DB commit succeeds, Kafka
  publish fails, or vice versa) without XA/distributed transactions.
- A duplicate *result* retains the same payload/event ID when republished.
  Spring verifies the persisted request/order and records immutable
  request/payment/result identities in `payment_result_receipts` atomically
  with the order/stock transition. Identical outcomes are idempotent;
  mismatched identities and contradictory outcomes cannot mutate the order.

### Distributed guarantees and the post-charge crash window

Kafka delivers at least once; offset commits do not make gateway calls
exactly once. Payment database uniqueness yields one logical Payment and
preserves its ID/parameters. The result transaction/outbox yields one durable
terminal state/event, but **cannot** roll back an external charge.

If the gateway charges and the response is lost, the result transaction fails,
or the service crashes, the row remains `REQUESTED`. Retrying the *same*
persisted ID asks the gateway for the original outcome, not another charge.
After durable completion, redelivery skips the provider altogether. Concurrent
attempts share the persisted ID, and the guarded terminal update writes only
one result/outbox row.

At-most-once financial effect therefore depends on the gateway's durable,
atomic idempotency contract, not on a local cache or a premature success flag.
Its key/outcome retention must cover the entire retry/redelivery lifetime.
If a gateway expires keys sooner, the adapter must reconcile the original
charge or stop for operator recovery; it must not submit a fresh charge.
Provider-specific durable reconciliation and retention verification are
mandatory acceptance requirements for #25. No real gateway is integrated or
certified by this development implementation.

Spring correlation prevents unrelated/conflicting results from settling an
order, but still trusts the Payment Service/producer for the first valid
financial outcome; it does not independently prove a provider charge.

## Fake payment provider

`internal/provider.FakeProvider` is deterministic: it **declines whenever the
charged amount's cents component equals `13`** (e.g. `10.13`), and approves
everything else. No real provider is integrated in this milestone. Nothing in
`ChargeRequest`/`ChargeResult` carries card/PAN/CVV data - there is no such
field to store or log, by design. Its in-memory outcome cache is a development
simulation only; it is not the source of restart safety for a real gateway.

## Failure handling

- **Malformed/invalid messages** (unparsable JSON, missing `eventId`/
  `orderId`, unparsable amount) are routed to `payment.requested.dlq` and
  committed only after the synchronous DLQ write receives acknowledgements
  from all in-sync replicas (`RequireAll`). Failed DLQ writes retry the
  same source record; they never acknowledge it without a durable copy.
- **Transient failures** (DB unavailable, provider error) are *not*
  committed; the **same fetched record** is retried in memory, with a
  bounded 500ms backoff between attempts to avoid a tight retry loop.
  Withholding a commit does not rewind Kafka's running reader. The
  sequential consumer does not fetch another record until the current one
  is durably handled and its offset successfully committed, preventing
  later offsets from acknowledging unresolved work. Commit failures retry
  only the acknowledgement, not the charge or DLQ write.
- **Persistent failures** are logged on each bounded retry with the source
  topic, partition and offset (plus payment/request IDs where available).
  An unresolved record intentionally blocks this consumer, including its
  other assigned partitions, until recovery or shutdown.
- **Cancellation/shutdown** interrupts retries and context-aware dependency
  calls without acknowledging unresolved work. `Close` also cancels the
  running consumer before releasing Kafka resources. Kafka redelivers
  uncommitted records on restart; an uncertain DLQ acknowledgement can
  produce duplicate DLQ copies (at-least-once delivery).
- **Restart safety**: because migrations, idempotent inserts, and the
  transactional outbox are all durable (Postgres, not in-memory), a Payment
  Service restart at any point resumes correctly - in-flight requests are
  either fully unprocessed (redelivered), recorded-but-unresolved (the gateway
  may already have charged; recover its outcome with the same key), or fully
  resolved with their result outbox row waiting to be published.

## Configuration (environment variables)

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `PAYMENT_DB_URL` | yes | - | Postgres connection string for this service's own database |
| `KAFKA_BROKERS` | yes | `localhost:9092` | Comma-separated Kafka bootstrap servers |
| `KAFKA_CONSUMER_GROUP` | no | `payment-service` | Consumer group for `payment.requested` |
| `HTTP_PORT` | no | `8081` | Port for `/healthz` and `/readyz` |
| `OUTBOX_POLL_INTERVAL_MS` | no | `1000` | Outbox poller interval, milliseconds |

## Running locally

Via Docker Compose (recommended - see `infrastructure/compose.yml`):

```bash
cd infrastructure
docker compose up -d postgres kafka payment-service
```

Standalone (e.g. for local debugging against Dockerized Postgres/Kafka):

```bash
cd payment-service
PAYMENT_DB_URL="postgres://payment_service:payment_service_password@localhost:5432/payment_service?sslmode=disable" \
KAFKA_BROKERS="localhost:9092" \
go run ./cmd/server
```

## Tests

```bash
cd payment-service

# Unit tests only (no Docker required)
go test ./internal/eventtime/... ./internal/provider/...

# Integration tests (require Docker - real Postgres/Kafka via testcontainers-go)
go test ./internal/store/...
go test ./internal/kafka/...

# Consumer retry/offset/shutdown regressions (real Kafka/Postgres, isolated fixtures)
GOMAXPROCS=2 go test -p 2 ./internal/kafka -run '^TestRequestConsumer'

# Durable gateway simulation: post-charge DB/outbox failures, restart,
# lost gateway response, Kafka acknowledgment failure and concurrent duplicates
GOMAXPROCS=2 go test -race -p 2 ./internal/kafka -run '^TestRequestConsumerProviderIdempotency$'

# Everything
go test ./...
```
