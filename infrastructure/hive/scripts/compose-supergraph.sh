#!/usr/bin/env bash
# Reproducible local supergraph generation:
#   retail subgraph (Spring/DGS, running on the host) -> SDL -> rover compose -> supergraph.graphql
#
# Requires: Spring backend running on :8080, oauth-service running (docker compose, :4447),
# and Rover CLI installed (https://rover.apollo.dev/nix/latest).
set -euo pipefail
cd "$(dirname "$0")/.."

SPRING_GRAPHQL_URL="${SPRING_GRAPHQL_URL:-http://127.0.0.1:8080/graphql}"
OAUTH_TOKEN_URL="${OAUTH_TOKEN_URL:-http://127.0.0.1:4447/internal/token}"
DEV_EMAIL="${DEV_EMAIL:-admin@bytecore.ee}"
DEV_PASSWORD="${DEV_PASSWORD:-admin-dev-password}"

echo "==> Fetching a dev token from oauth-service ($OAUTH_TOKEN_URL)"
TOKEN=$(curl -sf -X POST "$OAUTH_TOKEN_URL" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$DEV_EMAIL\",\"password\":\"$DEV_PASSWORD\"}" \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])')

echo "==> Fetching retail subgraph SDL from $SPRING_GRAPHQL_URL"
curl -sf -X POST "$SPRING_GRAPHQL_URL" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"{ _service { sdl } }"}' \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["data"]["_service"]["sdl"])' \
  > retail.graphql

echo "==> Composing local supergraph with Rover (Federation v2, no Hive Cloud / registry involved)"
rover supergraph compose --config ./supergraph-config.yaml --elv2-license accept > supergraph.graphql

echo "==> Wrote $(pwd)/supergraph.graphql"
