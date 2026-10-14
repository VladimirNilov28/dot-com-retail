#!/usr/bin/env bash
# One-command reviewer stack: HTTPS storefront + every backing service in Docker.
# Prerequisite: Docker with Compose v2.24+ (plus the Stripe CLI if you test webhooks).
#
#   scripts/review-up.sh            # or: make review
#
# Environment overrides:
#   REVIEW_PROJECT        Compose project name            (default bytecore-review)
#   REVIEW_ENV_FILE       generated secrets/config file   (default ./.env.review)
#   REVIEW_HOST, REVIEW_*_PORT, REVIEW_BIND  public origin (see .env.review.example)
#   REVIEW_SEED=0         skip the demo catalog seed on an empty database
#   REVIEW_WAIT_TIMEOUT   seconds to wait for healthy services (default 900)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${REVIEW_PROJECT:-bytecore-review}"
ENV_FILE="${REVIEW_ENV_FILE:-$ROOT/.env.review}"
TEMPLATE="$ROOT/.env.review.example"
SEED_FILE="$ROOT/backend/src/main/resources/db/testdata/test-data.sql"
OPENSSL_IMAGE="alpine/openssl@sha256:e8a2319d15637db5296c0ff540bb028cfabc480b22fcdd4a020582393d32673f"

info() { printf '\033[0;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[0;33m!!  %s\033[0m\n' "$*" >&2; }

command -v docker >/dev/null || { echo "Docker is required" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "The Docker daemon is not reachable" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required" >&2; exit 1; }

env_value() { # first value of KEY in the env file, quotes stripped
  sed -n "s/^$1=//p" "$ENV_FILE" | head -n 1 | sed "s/^'\(.*\)'\$/\1/; s/^\"\(.*\)\"\$/\1/"
}

generate_env_file() {
  info "Creating $ENV_FILE with freshly generated secrets"
  local pool hex=() b64=() line out i=0 j=0
  pool="$(docker run --rm --network none --entrypoint /bin/sh "$OPENSSL_IMAGE" -c \
    'for n in $(seq 40); do echo "h $(openssl rand -hex 32)"; done
     for n in $(seq 8); do echo "b $(openssl rand -base64 32 | tr "+/" "-_" | tr -d "=\n")"; done')"
  while read -r kind value; do
    if [ "$kind" = h ]; then hex+=("$value"); else b64+=("$value"); fi
  done <<<"$pool"
  [ "${#hex[@]}" -eq 40 ] && [ "${#b64[@]}" -eq 8 ] || { echo "Secret generation failed" >&2; exit 1; }
  out="$(mktemp "$ENV_FILE.XXXXXX")"
  chmod 600 "$out"
  while IFS= read -r line || [ -n "$line" ]; do
    while [[ "$line" == *@hex32@* ]]; do line="${line/@hex32@/${hex[i]}}"; i=$((i + 1)); done
    while [[ "$line" == *@hex16@* ]]; do line="${line/@hex16@/${hex[i]:0:32}}"; i=$((i + 1)); done
    while [[ "$line" == *@b64url32@* ]]; do line="${line/@b64url32@/${b64[j]}}"; j=$((j + 1)); done
    printf '%s\n' "$line"
  done <"$TEMPLATE" >"$out"
  mv "$out" "$ENV_FILE"
}

if [ ! -f "$ENV_FILE" ]; then
  generate_env_file
else
  info "Using existing $ENV_FILE (secrets preserved)"
  chmod 600 "$ENV_FILE"
fi
if grep -q '@hex\|@b64url' "$ENV_FILE"; then
  echo "$ENV_FILE still contains unreplaced @...@ markers" >&2
  exit 1
fi

# Shell environment wins over the env file (same precedence as Compose).
for key in REVIEW_HOST REVIEW_STOREFRONT_PORT REVIEW_KRATOS_PORT REVIEW_HYDRA_PORT \
  REVIEW_BRIDGE_PORT REVIEW_MAILPIT_PORT REVIEW_BIND; do
  if [ -z "$(printenv "$key" || true)" ]; then
    value="$(env_value "$key")"
    [ -n "$value" ] && export "$key=$value"
  fi
done
export REVIEW_HOST="${REVIEW_HOST:-bytecore.localhost}"
STORE_URL="https://$REVIEW_HOST:${REVIEW_STOREFRONT_PORT:-8443}"

REVIEW_HOST="$REVIEW_HOST" "$ROOT/scripts/gen-review-certs.sh"

COMPOSE=(docker compose -p "$PROJECT" -f "$ROOT/infrastructure/compose.yml"
  -f "$ROOT/infrastructure/compose.review.yml" --env-file "$ENV_FILE")

info "Building and starting project '$PROJECT' (first build takes several minutes)"
"${COMPOSE[@]}" up -d --build --wait --wait-timeout "${REVIEW_WAIT_TIMEOUT:-900}"

if [ "${REVIEW_SEED:-1}" != "0" ]; then
  db_user="$(env_value DB_USERNAME)"
  psql=("${COMPOSE[@]}" exec -T -e PGOPTIONS=-cclient_min_messages=warning postgres psql -U "$db_user" -d retail -v ON_ERROR_STOP=1 -qtA)
  counts="$("${psql[@]}" -c "SELECT (SELECT count(*) FROM products) || ' ' || (SELECT count(*) FROM users)")"
  read -r products users <<<"$counts"
  if [ "$products" = 0 ] && [ "$users" -le 1 ]; then
    info "Empty catalog: loading the demo dataset (one transaction)"
    "${psql[@]}" -1 -f - <"$SEED_FILE" >/dev/null
    # The seed inserts plaintext personal data; the backend's startup backfill encrypts it at rest.
    info "Encrypting seeded personal data at rest (backend restart)"
    "${COMPOSE[@]}" restart backend >/dev/null
    "${COMPOSE[@]}" up -d --wait --wait-timeout "${REVIEW_WAIT_TIMEOUT:-900}" backend >/dev/null
  else
    info "Catalog already has data ($products products); seed skipped"
  fi
fi

admin_user="$(env_value ADMIN_USERNAME)"
cat <<EOF

ByteCore review stack '$PROJECT' is ready (trust infrastructure/review/certs/ca.crt first):
  Storefront   $STORE_URL
  Admin        $STORE_URL/admin/security  (enroll TOTP first, then /admin/products)
  Mailpit      https://$REVIEW_HOST:${REVIEW_MAILPIT_PORT:-8425}  (verification, recovery, order emails)
  OIDC issuer  https://$REVIEW_HOST:${REVIEW_HYDRA_PORT:-8444}/.well-known/openid-configuration

Bootstrap administrator: ${admin_user}@bytecore.ee
  password: ADMIN_PASSWORD in $ENV_FILE (not printed)

Stripe webhooks (optional):
  stripe listen --forward-to $STORE_URL/webhooks/stripe --skip-verify
Stop: scripts/review-down.sh   (data volumes are kept unless --volumes is given)
Docs: docs/review-setup.md
EOF
