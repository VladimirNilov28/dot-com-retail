#!/usr/bin/env bash
# One-command reviewer stack: HTTPS storefront + every backing service in Docker.
# Prerequisite: Docker with Compose v2.24.4+ on a host that provides /dev/fuse
# (Postgres storage is gocryptfs-encrypted), plus the Stripe CLI if you test webhooks.
#
#   scripts/review-up.sh            # or: make review
#
# Environment overrides:
#   REVIEW_PROJECT        Compose project name            (default bytecore-review)
#   REVIEW_ENV_FILE       generated secrets/config file   (default ./.env.review)
#   REVIEW_PGDATA_KEY_FILE Postgres storage key (generated if absent;
#                         default ./infrastructure/review/secrets/pgdata.key)
#   REVIEW_HOST, REVIEW_*_PORT, REVIEW_BIND  public origin (see .env.review.example)
#   REVIEW_SEED=0         skip the demo catalog seed on an empty database
#   REVIEW_WAIT_TIMEOUT   seconds to wait for healthy services (default 900)
#   REVIEW_STRIPE_FILE    optional Stripe test keys       (default ./.env.stripe)
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

# Key for the gocryptfs-encrypted Postgres data directory. Kept outside the
# encrypted volume and never committed; losing it makes the volume unreadable.
if [ -z "$(printenv REVIEW_PGDATA_KEY_FILE || true)" ]; then
  value="$(env_value REVIEW_PGDATA_KEY_FILE)"
  export REVIEW_PGDATA_KEY_FILE="${value:-$ROOT/infrastructure/review/secrets/pgdata.key}"
fi
case "$REVIEW_PGDATA_KEY_FILE" in
  /*) ;;
  *) REVIEW_PGDATA_KEY_FILE="$ROOT/infrastructure/$REVIEW_PGDATA_KEY_FILE" ;; # Compose resolves it there
esac
if [ -d "$REVIEW_PGDATA_KEY_FILE" ]; then # Docker creates a directory for a missing bind source
  rmdir "$REVIEW_PGDATA_KEY_FILE" 2>/dev/null ||
    { echo "$REVIEW_PGDATA_KEY_FILE is a non-empty directory, not a key file" >&2; exit 1; }
fi
if [ ! -s "$REVIEW_PGDATA_KEY_FILE" ]; then
  if docker volume inspect "${PROJECT}_postgres_crypt_volume" >/dev/null 2>&1; then
    echo "Encrypted Postgres volume ${PROJECT}_postgres_crypt_volume exists but its key file" \
      "$REVIEW_PGDATA_KEY_FILE is missing. Restore the key from your backup (a new key cannot" \
      "open the existing data), or delete the data with scripts/review-down.sh --volumes." >&2
    exit 1
  fi
  info "Generating the Postgres storage encryption key $REVIEW_PGDATA_KEY_FILE"
  (umask 077 && mkdir -p "$(dirname "$REVIEW_PGDATA_KEY_FILE")")
  key="$(docker run --rm --network none --entrypoint /bin/sh "$OPENSSL_IMAGE" -c 'openssl rand -base64 32')"
  [ "${#key}" -eq 44 ] || { echo "Key generation failed" >&2; exit 1; }
  (umask 077 && printf '%s\n' "$key" >"$REVIEW_PGDATA_KEY_FILE")
  unset key
fi
chmod 600 "$REVIEW_PGDATA_KEY_FILE"
[ "$(uname -s)" != Linux ] || [ -c /dev/fuse ] || [ -n "${DOCKER_HOST:-}" ] ||
  warn "/dev/fuse not found on this host; the encrypted Postgres needs it (see docs/review-setup.md)"

COMPOSE=(docker compose -p "$PROJECT" -f "$ROOT/infrastructure/compose.yml"
  -f "$ROOT/infrastructure/compose.review.yml" --env-file "$ENV_FILE")

# Stripe test keys (optional): shell environment > ./.env.stripe (Git-ignored) > env file.
# They are stored in the private env file; the webhook signing secret is fetched
# automatically and the stripe-listener container forwards events.
set_env_value() { # KEY; value taken from $NEW_VALUE to avoid quoting issues
  local out
  out="$(mktemp "$ENV_FILE.XXXXXX")"
  chmod 600 "$out"
  NEW_VALUE="$NEW_VALUE" awk -v key="$1" 'BEGIN { done = 0 }
    index($0, key "=") == 1 { print key "=" ENVIRON["NEW_VALUE"]; done = 1; next } { print }
    END { if (!done) print key "=" ENVIRON["NEW_VALUE"] }' "$ENV_FILE" >"$out"
  mv "$out" "$ENV_FILE"
}
STRIPE_FILE="${REVIEW_STRIPE_FILE:-$ROOT/.env.stripe}"
stripe_value() { # shell > Stripe file > env file
  local value
  value="$(printenv "$1" || true)"
  if [ -z "$value" ] && [ -f "$STRIPE_FILE" ]; then
    value="$(sed -n "s/^$1=//p" "$STRIPE_FILE" | head -n 1 | sed "s/^['\"]//; s/['\"]\$//")"
  fi
  printf '%s' "${value:-$(env_value "$1")}"
}
stripe_secret="$(stripe_value STRIPE_SECRET_KEY)"
stripe_public="$(stripe_value STRIPE_PUBLISHABLE_KEY)"
stripe_webhook="$(stripe_value STRIPE_WEBHOOK_SECRET)"
if [ -n "$stripe_secret$stripe_public" ]; then
  case "$stripe_secret" in sk_test_* | rk_test_*) ;; *)
    echo "STRIPE_SECRET_KEY must be a test-mode key (sk_test_/rk_test_); live keys are not supported" >&2; exit 1 ;;
  esac
  case "$stripe_public" in pk_test_*) ;; *)
    echo "STRIPE_PUBLISHABLE_KEY must be a test-mode key (pk_test_)" >&2; exit 1 ;;
  esac
  case "$stripe_webhook" in "" | whsec_*) ;; *)
    echo "STRIPE_WEBHOOK_SECRET must be empty (fetched automatically) or a whsec_ value" >&2; exit 1 ;;
  esac
  if [ "$stripe_secret" != "$(env_value STRIPE_SECRET_KEY)" ] && [ "$stripe_webhook" = "$(env_value STRIPE_WEBHOOK_SECRET)" ]; then
    stripe_webhook="" # a different account needs its own signing secret
  fi
  if [ -z "$stripe_webhook" ]; then
    info "Fetching the Stripe test webhook signing secret (Stripe CLI container)"
    stripe_webhook="$(docker run --rm -e STRIPE_API_KEY="$stripe_secret" -e STRIPE_DEVICE_NAME=bytecore-review \
      stripe/stripe-cli:v1.53.1@sha256:11c46caeead82e329f14823dd622d494b4cc8c1b9766f724d122d2af42c97540 \
      listen --print-secret 2>/dev/null | tr -d '\r\n' || true)"
    case "$stripe_webhook" in whsec_*) ;; *)
      echo "Could not obtain a webhook signing secret; check STRIPE_SECRET_KEY and network access" >&2; exit 1 ;;
    esac
  fi
  for key in STRIPE_SECRET_KEY STRIPE_PUBLISHABLE_KEY STRIPE_WEBHOOK_SECRET; do
    case "$key" in
      STRIPE_SECRET_KEY) NEW_VALUE="$stripe_secret" ;;
      STRIPE_PUBLISHABLE_KEY) NEW_VALUE="$stripe_public" ;;
      *) NEW_VALUE="$stripe_webhook" ;;
    esac
    if [ "$NEW_VALUE" != "$(env_value "$key")" ]; then
      set_env_value "$key"
      info "Stored $key in $ENV_FILE"
    fi
  done
  unset NEW_VALUE
  # Compose gives the shell environment precedence over the env file.
  export STRIPE_SECRET_KEY="$stripe_secret" STRIPE_PUBLISHABLE_KEY="$stripe_public" STRIPE_WEBHOOK_SECRET="$stripe_webhook"
  COMPOSE+=(--profile stripe)
  STRIPE_STATUS="enabled (test mode; webhooks forwarded by the stripe-listener container)"
else
  STRIPE_STATUS="disabled (no test keys; see README 'Quick start for reviewers')"
fi
unset stripe_secret stripe_public stripe_webhook

if docker volume inspect "${PROJECT}_postgres_volume" >/dev/null 2>&1 &&
  ! docker volume inspect "${PROJECT}_postgres_crypt_volume" >/dev/null 2>&1; then
  warn "Found the unencrypted volume ${PROJECT}_postgres_volume from an older review stack. It is left"
  warn "untouched; this run starts a new encrypted database (migrate: docs/security/encryption-at-rest.md)."
fi

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

Stripe card payments: $STRIPE_STATUS
  test card 4242 4242 4242 4242, any future expiry, any CVC
Postgres storage is encrypted at rest; key: $REVIEW_PGDATA_KEY_FILE (back it up, never commit it)
Stop: scripts/review-down.sh   (data volumes are kept unless --volumes is given)
Docs: docs/review-setup.md
EOF
