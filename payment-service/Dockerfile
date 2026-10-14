# Multi-stage build: golang:alpine builder -> minimal alpine runtime,
# matching the wget-based healthcheck convention used by hydra/kratos/
# oauth-service in infrastructure/compose.yml.
FROM golang:1.25-alpine AS builder

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -o /out/payment-service ./cmd/server

FROM alpine:3.20

RUN apk add --no-cache wget ca-certificates

COPY --from=builder /out/payment-service /usr/local/bin/payment-service

EXPOSE 8081

ENTRYPOINT ["/usr/local/bin/payment-service"]
