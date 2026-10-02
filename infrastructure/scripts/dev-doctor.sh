#!/usr/bin/env bash
# Read-only diagnostic for the local dev infrastructure (Postgres, Hydra,
# Kratos, oauth-service, and the Docker->host:8080 path to a manually-run
# Spring). Prints PASS/WARN/FAIL per check; never prints secret values.
#
# Usage: ./infrastructure/scripts/dev-doctor.sh   (or: make doctor)

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/compose.yml"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE=(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE")
DEV_SUBNET="172.28.88.0/24"
NETWORK_NAME="infrastructure_default"

PASS=0
WARN=0
FAIL=0

pass() { printf "PASS  %s\n" "$1"; PASS=$((PASS + 1)); }
warn() { printf "WARN  %s\n" "$1"; WARN=$((WARN + 1)); }
fail() { printf "FAIL  %s\n" "$1"; FAIL=$((FAIL + 1)); }

echo "== dev infrastructure doctor =="

# 1. Docker daemon running
if docker info >/dev/null 2>&1; then
  pass "Docker daemon is running"
else
  fail "Docker daemon is not reachable"
fi

# 2. Root .env exists
if [ -f "$ENV_FILE" ]; then
  pass ".env exists at repo root"
else
  fail ".env missing at repo root (run: cp .env.example .env)"
fi

# 3-4. Compose config resolves (also validates required vars, thanks to
# the ${VAR:?...} guards in compose.yml)
COMPOSE_CONFIG="$("${COMPOSE[@]}" config 2>&1)"
if [ $? -eq 0 ]; then
  pass "docker compose config resolves (no missing required variables)"
else
  fail "docker compose config failed:"
  echo "$COMPOSE_CONFIG" | grep -i "required\|error" | sed 's/^/      /'
fi

# 5. Network subnet pinned as expected
ACTUAL_SUBNET="$(docker network inspect "$NETWORK_NAME" --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}' 2>/dev/null)"
if [ "$ACTUAL_SUBNET" = "$DEV_SUBNET" ]; then
  pass "network $NETWORK_NAME subnet is pinned to $DEV_SUBNET"
elif [ -z "$ACTUAL_SUBNET" ]; then
  warn "network $NETWORK_NAME does not exist yet (run: make dev)"
else
  warn "network $NETWORK_NAME subnet is $ACTUAL_SUBNET, expected $DEV_SUBNET — recreate it: make down && make dev"
fi

# 6. oauth-service resolves host.docker.internal
if "${COMPOSE[@]}" exec -T oauth-service python3 -c "import socket; socket.gethostbyname('host.docker.internal')" >/dev/null 2>&1; then
  pass "oauth-service resolves host.docker.internal"
else
  warn "oauth-service cannot resolve host.docker.internal (is it running? make dev)"
fi

# 7. oauth-service -> host:8080 (Spring)
if "${COMPOSE[@]}" exec -T oauth-service python3 -c "import socket; socket.create_connection(('host.docker.internal', 8080), timeout=3)" >/dev/null 2>&1; then
  pass "oauth-service can reach host.docker.internal:8080 (Spring)"
else
  fail "oauth-service cannot reach host.docker.internal:8080 — is Spring running? is the firewall rule in place? (make firewall-check)"
fi

# 8. Hydra admin + public
if curl -sf --max-time 3 http://127.0.0.1:4445/health/ready >/dev/null 2>&1; then
  pass "Hydra admin (127.0.0.1:4445) is ready"
else
  fail "Hydra admin (127.0.0.1:4445) is not reachable/ready"
fi
if curl -sf --max-time 3 http://127.0.0.1:4444/health/alive >/dev/null 2>&1; then
  pass "Hydra public (127.0.0.1:4444) is alive"
else
  warn "Hydra public (127.0.0.1:4444) health check failed"
fi

# 9. Kratos admin + public
if curl -sf --max-time 3 http://127.0.0.1:4434/health/ready >/dev/null 2>&1; then
  pass "Kratos admin (127.0.0.1:4434) is ready"
else
  fail "Kratos admin (127.0.0.1:4434) is not reachable/ready"
fi
if curl -sf --max-time 3 http://127.0.0.1:4433/health/alive >/dev/null 2>&1; then
  pass "Kratos public (127.0.0.1:4433) is alive"
else
  warn "Kratos public (127.0.0.1:4433) health check failed"
fi

# 10. oauth-service ports listening
if nc -z -w2 127.0.0.1 4446 2>/dev/null; then
  pass "oauth-service login/consent bridge listening on 127.0.0.1:4446"
else
  fail "oauth-service port 4446 is not accepting connections"
fi
if nc -z -w2 127.0.0.1 4447 2>/dev/null; then
  pass "oauth-service internal dev-token endpoint listening on 127.0.0.1:4447"
else
  fail "oauth-service port 4447 is not accepting connections"
fi

# 11 & 13. 4447 must be loopback-only, never 0.0.0.0
PORT_4447_BINDING="$(echo "$COMPOSE_CONFIG" | grep -B2 'target: 4447' | grep host_ip || true)"
if echo "$PORT_4447_BINDING" | grep -q "127.0.0.1"; then
  pass "port 4447 is published as 127.0.0.1-only in effective compose config"
else
  fail "port 4447 does not show a 127.0.0.1 host_ip in effective compose config — check compose.yml"
fi

# 12. Firewall rule for the dev subnet -> host:8080 (best-effort, no sudo prompt)
if sudo -n iptables -C ufw-user-input -p tcp -s "$DEV_SUBNET" --dport 8080 -j ACCEPT 2>/dev/null; then
  pass "firewall rule allowing $DEV_SUBNET -> host:8080 is present"
else
  warn "cannot confirm firewall rule without sudo — verify manually: sudo iptables -C ufw-user-input -p tcp -s $DEV_SUBNET --dport 8080 -j ACCEPT (if missing: sudo ufw allow from $DEV_SUBNET to any port 8080 proto tcp comment 'bytecore-dev: oauth-service -> Spring')"
fi

echo "=============================="
echo "PASS=$PASS WARN=$WARN FAIL=$FAIL"

[ "$FAIL" -eq 0 ]
