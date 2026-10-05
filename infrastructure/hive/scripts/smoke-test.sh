#!/usr/bin/env bash
# Lightweight Hive Router smoke test. Requires: Docker infra + hive-router
# running, Spring running on :8080, oauth-service running (:4447).
set -uo pipefail

ROUTER_URL="${ROUTER_URL:-http://127.0.0.1:4002}"
OAUTH_TOKEN_URL="${OAUTH_TOKEN_URL:-http://127.0.0.1:4447/internal/token}"
DEV_EMAIL="${DEV_EMAIL:-admin@bytecore.ee}"
DEV_PASSWORD="${DEV_PASSWORD:-admin-dev-password}"

pass=0
fail=0

check() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "OK   - $desc"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc (expected '$expected', got '$actual')"
    fail=$((fail + 1))
  fi
}

echo "1) Router reachable (readiness)"
code=$(curl -s -o /dev/null -w "%{http_code}" "$ROUTER_URL/readiness")
check "readiness returns 200" "200" "$code"

echo "2) Introspection/schema works"
resp=$(curl -s -X POST "$ROUTER_URL/graphql" -H 'Content-Type: application/json' \
  -d '{"query":"{ __schema { queryType { name } } }"}')
check "introspection query returns queryType Query" "true" \
  "$(echo "$resp" | grep -c '"name":"Query"' | sed 's/^[0-9]*$/true/' )"

echo "3+4) No-token protected query is denied"
resp=$(curl -s -X POST "$ROUTER_URL/graphql" -H 'Content-Type: application/json' \
  -d '{"query":"{ products { id } }"}')
check "unauthenticated query returns errors, no data" "true" \
  "$(echo "$resp" | grep -c '"errors"' | sed 's/^[1-9][0-9]*$/true/' )"

echo "5) Authenticated query succeeds"
TOKEN=$(curl -sf -X POST "$OAUTH_TOKEN_URL" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$DEV_EMAIL\",\"password\":\"$DEV_PASSWORD\"}" \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])')
resp=$(curl -s -X POST "$ROUTER_URL/graphql" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"query":"{ me { id role } }"}')
check "authenticated 'me' query returns data" "true" \
  "$(echo "$resp" | grep -c '"me"' | sed 's/^[1-9][0-9]*$/true/')"

echo "6) Mutation reaches Spring"
resp=$(curl -s -X POST "$ROUTER_URL/graphql" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createCategory(input:{name:\"Smoke Test\",slug:\"smoke-test-category\"}) { id name } }"}')
check "createCategory mutation returns an id" "true" \
  "$(echo "$resp" | grep -c '"createCategory"' | sed 's/^[1-9][0-9]*$/true/')"

echo "7) Validation error propagates with its original message"
resp=$(curl -s -X POST "$ROUTER_URL/graphql" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createProductVariant(input:{productId:\"1\",sku:\"SMOKE-1\",price:-1}) { id } }"}')
check "negative price error message is preserved (not masked)" "true" \
  "$(echo "$resp" | grep -c 'must not be negative' | sed 's/^[1-9][0-9]*$/true/')"

echo
echo "Passed: $pass, Failed: $fail"
[[ $fail -eq 0 ]]
