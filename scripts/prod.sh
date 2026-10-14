#!/usr/bin/env bash
# Production-mode Docker stack (infrastructure/compose.prod.yml).
#
#   scripts/prod.sh up       build, validate, start, wait for health, smoke-check  (make prod)
#   scripts/prod.sh down     stop and remove containers; volumes are always kept   (make prod-down)
#   scripts/prod.sh status   container states and public URLs                      (make prod-status)
#   scripts/prod.sh logs [compose logs args...]                                    (make prod-logs)
#   scripts/prod.sh check    validate the private configuration only
#
# Environment overrides (shell wins over .env.prod, as in Compose):
#   PROD_PROJECT       Compose project name             (default bytecore-prod)
#   PROD_ENV_FILE      private configuration file        (default ./.env.prod)
#   PROD_WAIT_TIMEOUT  seconds to wait for healthy services (default 900)
# Requirements: Docker Engine with Compose v2.24.4+, and /dev/fuse on the
# Docker host (gocryptfs-encrypted Postgres storage). See docs/production-setup.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${PROD_PROJECT:-bytecore-prod}"
ENV_FILE="${PROD_ENV_FILE:-$ROOT/.env.prod}"
TEMPLATE="$ROOT/.env.prod.example"
OPENSSL_IMAGE="alpine/openssl@sha256:e8a2319d15637db5296c0ff540bb028cfabc480b22fcdd4a020582393d32673f"
COMMAND="${1:-}"
[ $# -eq 0 ] || shift

info() { printf '\033[0;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[0;33m!!  %s\033[0m\n' "$*" >&2; }
fail() { printf '\033[0;31mxx  %s\033[0m\n' "$*" >&2; exit 1; }

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
case "$COMMAND" in up | down | status | logs | check) ;; *) usage ;; esac

command -v docker >/dev/null || fail "Docker is required"
docker info >/dev/null 2>&1 || fail "The Docker daemon is not reachable"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"

# Effective value of KEY: shell environment first, then the env file (quotes stripped).
value_of() {
  local v
  v="$(printenv "$1" || true)"
  if [ -z "$v" ] && [ -f "$ENV_FILE" ]; then
    v="$(sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | sed "s/^'\(.*\)'\$/\1/; s/^\"\(.*\)\"\$/\1/")"
  fi
  printf '%s' "$v"
}

generate_env_file() {
  info "Creating $ENV_FILE from .env.prod.example with generated internal secrets"
  local pool hex=() b64=() line out i=0 j=0
  pool="$(docker run --rm --network none --entrypoint /bin/sh "$OPENSSL_IMAGE" -c \
    'for n in $(seq 40); do echo "h $(openssl rand -hex 32)"; done
     for n in $(seq 8); do echo "b $(openssl rand -base64 32 | tr "+/" "-_" | tr -d "=\n")"; done')"
  while read -r kind value; do
    if [ "$kind" = h ]; then hex+=("$value"); else b64+=("$value"); fi
  done <<<"$pool"
  [ "${#hex[@]}" -eq 40 ] && [ "${#b64[@]}" -eq 8 ] || fail "Secret generation failed"
  out="$(mktemp "$ENV_FILE.XXXXXX")"
  chmod 600 "$out"
  while IFS= read -r line || [ -n "$line" ]; do
    while [[ "$line" == *@hex32@* ]]; do line="${line/@hex32@/${hex[i]}}"; i=$((i + 1)); done
    while [[ "$line" == *@hex16@* ]]; do line="${line/@hex16@/${hex[i]:0:32}}"; i=$((i + 1)); done
    while [[ "$line" == *@b64url32@* ]]; do line="${line/@b64url32@/${b64[j]}}"; j=$((j + 1)); done
    printf '%s\n' "$line"
  done <"$TEMPLATE" >"$out"
  mv -n "$out" "$ENV_FILE"
  [ ! -e "$out" ] || { rm -f "$out"; fail "$ENV_FILE appeared concurrently; not overwritten"; }
}

origin() { # https origin for PROD_HOST on port $1 (default port omitted)
  if [ "$1" = 443 ]; then printf 'https://%s' "$PROD_HOST"; else printf 'https://%s:%s' "$PROD_HOST" "$1"; fi
}

# Exports the derived variables compose.prod.yml interpolates.
derive() {
  local key
  for key in PROD_HOST PROD_HTTP_PORT PROD_HTTPS_PORT PROD_KRATOS_PORT PROD_HYDRA_PORT \
    PROD_BRIDGE_PORT PROD_BIND PROD_TLS_MODE PROD_TLS_CERT_FILE PROD_TLS_KEY_FILE \
    PROD_SECRETS_DIR SMTP_HOST SMTP_USERNAME; do
    export "$key=$(value_of "$key")"
  done
  : "${PROD_HTTPS_PORT:=443}" "${PROD_KRATOS_PORT:=9433}" "${PROD_HYDRA_PORT:=9444}" "${PROD_BRIDGE_PORT:=9446}"
  export PROD_HTTPS_PORT PROD_KRATOS_PORT PROD_HYDRA_PORT PROD_BRIDGE_PORT
  PROD_SECRETS_DIR="${PROD_SECRETS_DIR:-infrastructure/prod/secrets}"
  case "$PROD_SECRETS_DIR" in /*) ;; *) PROD_SECRETS_DIR="$ROOT/$PROD_SECRETS_DIR" ;; esac
  export PROD_SECRETS_DIR
  PROD_STOREFRONT_ORIGIN="$(origin "$PROD_HTTPS_PORT")"
  PROD_KRATOS_ORIGIN="$(origin "$PROD_KRATOS_PORT")"
  PROD_HYDRA_ORIGIN="$(origin "$PROD_HYDRA_PORT")"
  PROD_BRIDGE_ORIGIN="$(origin "$PROD_BRIDGE_PORT")"
  export PROD_STOREFRONT_ORIGIN PROD_KRATOS_ORIGIN PROD_HYDRA_ORIGIN PROD_BRIDGE_ORIGIN
  case "$PROD_TLS_MODE" in
    acme) export PROD_CADDY_TLS=acme ;;
    *) export PROD_CADDY_TLS=manual ;;
  esac
  if [ "$PROD_TLS_MODE" = files ]; then
    export PROD_EDGE_CERT_FILE="$PROD_TLS_CERT_FILE" PROD_EDGE_KEY_FILE="$PROD_TLS_KEY_FILE"
  else # selfsigned uses the generated leaf; acme mounts it unused
    export PROD_EDGE_CERT_FILE="$PROD_SECRETS_DIR/tls/server.crt" PROD_EDGE_KEY_FILE="$PROD_SECRETS_DIR/tls/server.key"
  fi
  if [ -n "$SMTP_USERNAME" ]; then export PROD_SMTP_AUTH=true; else export PROD_SMTP_AUTH=false; fi
  # Mailpit capture only when explicitly selected; `down` always includes it.
  local profiles=() all=false
  case "$COMMAND" in down | status | logs) all=true ;; esac
  if [ "$SMTP_HOST" = mailpit ] || [ "$all" = true ]; then profiles+=(sandbox-mail); fi
  if [ "${PROD_STRIPE_LISTENER:-}" = true ] || [ "$all" = true ]; then profiles+=(stripe); fi
  COMPOSE_PROFILES="$(IFS=,; printf '%s' "${profiles[*]}")"
  export COMPOSE_PROFILES
  COMPOSE=(docker compose -p "$PROJECT" -f "$ROOT/infrastructure/compose.yml"
    -f "$ROOT/infrastructure/compose.prod.yml" --env-file "$ENV_FILE")
}

# Reports every invalid setting by name (never by value) and exits non-zero.
validate() {
  local problems=() missing=() key v
  required=(PROD_HOST PROD_TLS_MODE SMTP_HOST SMTP_PORT SMTP_FROM SUPPORT_MAIL_TO ADMIN_USERNAME
    ADMIN_PASSWORD DB_USERNAME DB_PASSWORD HYDRA_DB_USER HYDRA_DB_PASSWORD HYDRA_DSN HYDRA_SYSTEM_SECRET
    HYDRA_ADMIN_URL HYDRA_PUBLIC_URL KRATOS_DB_USER KRATOS_DB_PASSWORD KRATOS_DSN KRATOS_SECRETS_COOKIE
    KRATOS_SECRETS_CIPHER KRATOS_ADMIN_URL KRATOS_PUBLIC_URL PAYMENT_DB_USER PAYMENT_DB_PASSWORD
    PAYMENT_DB_URL PAYMENT_INTERNAL_TOKEN AUTH_MIGRATION_USER AUTH_MIGRATION_PASSWORD AUTH_DB_USER
    AUTH_DB_PASSWORD CAPTCHA_DB_USER CAPTCHA_DB_PASSWORD CAPTCHA_DATABASE_URL KAFKA_CLUSTER_ID
    OAUTH_SERVICE_CLIENT_ID OAUTH_SERVICE_CLIENT_SECRET BFF_CLIENT_ID BFF_CLIENT_SECRET
    AUTH_BRIDGE_SECRET CAPTCHA_SECRET AUTH_SEAL_KEYS APP_DATA_KEYS)
  [ "$(value_of PROD_TLS_MODE)" != acme ] || required+=(PROD_ACME_EMAIL)
  [ "$(value_of PROD_TLS_MODE)" != files ] || required+=(PROD_TLS_CERT_FILE PROD_TLS_KEY_FILE)
  for key in "${required[@]}"; do
    v="$(value_of "$key")"
    case "$v" in "" | *CHANGE_ME* | *@hex* | *@b64url*) missing+=("$key") ;; esac
  done
  [ "${#missing[@]}" -eq 0 ] || problems+=("missing or placeholder: ${missing[*]}")

  printf '%s' "$(value_of PROD_HOST)" | grep -Eq '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$' ||
    problems+=("PROD_HOST must be a lowercase DNS name with a dot (no scheme, port or path)")
  for key in PROD_HTTP_PORT PROD_HTTPS_PORT PROD_KRATOS_PORT PROD_HYDRA_PORT PROD_BRIDGE_PORT SMTP_PORT; do
    v="$(value_of "$key")"
    [ -z "$v" ] || [[ "$v" =~ ^[0-9]{1,5}$ && "$v" -ge 1 && "$v" -le 65535 ]] || problems+=("$key must be a port number")
  done
  case "$(value_of PROD_TLS_MODE)" in
    acme | selfsigned) ;;
    files)
      for key in PROD_TLS_CERT_FILE PROD_TLS_KEY_FILE; do
        v="$(value_of "$key")"
        [ -z "$v" ] || { [[ "$v" == /* ]] && [ -r "$v" ]; } || problems+=("$key must be a readable absolute path")
      done ;;
    "") ;;
    *) problems+=("PROD_TLS_MODE must be acme, files or selfsigned") ;;
  esac
  for key in SMTP_STARTTLS SMTP_IMPLICIT_TLS; do
    case "$(value_of "$key")" in "" | true | false) ;; *) problems+=("$key must be true or false") ;; esac
  done
  if [ "$(value_of SMTP_STARTTLS)" = true ] && [ "$(value_of SMTP_IMPLICIT_TLS)" = true ]; then
    problems+=("set only one of SMTP_STARTTLS / SMTP_IMPLICIT_TLS")
  fi
  if [ "$(value_of SMTP_HOST)" != mailpit ] && [ "$(value_of SMTP_STARTTLS)" != true ] &&
    [ "$(value_of SMTP_IMPLICIT_TLS)" != true ]; then
    problems+=("a remote SMTP_HOST needs SMTP_STARTTLS=true or SMTP_IMPLICIT_TLS=true")
  fi
  if [ -n "$(value_of SMTP_USERNAME)" ] && [ -z "$(value_of SMTP_PASSWORD)" ]; then
    problems+=("SMTP_PASSWORD is required when SMTP_USERNAME is set")
  fi

  for key in HYDRA_SYSTEM_SECRET KRATOS_SECRETS_COOKIE AUTH_BRIDGE_SECRET CAPTCHA_SECRET \
    BFF_CLIENT_SECRET OAUTH_SERVICE_CLIENT_SECRET PAYMENT_INTERNAL_TOKEN; do
    v="$(value_of "$key")"
    [ -z "$v" ] || [ "${#v}" -ge 32 ] || problems+=("$key must have at least 32 characters")
  done
  v="$(value_of KRATOS_SECRETS_CIPHER)"
  [ -z "$v" ] || [ "${#v}" -eq 32 ] || problems+=("KRATOS_SECRETS_CIPHER must have exactly 32 characters")
  v="$(value_of ADMIN_PASSWORD)"
  [ -z "$v" ] || [ "${#v}" -ge 12 ] || problems+=("ADMIN_PASSWORD must have at least 12 characters")
  value_of APP_DATA_KEYS | grep -Eq '^[A-Za-z0-9_-]+:[A-Za-z0-9_-]{43}=?(,[A-Za-z0-9_-]+:[A-Za-z0-9_-]{43}=?)*$' ||
    problems+=("APP_DATA_KEYS must be id:base64url-32-byte-key[,...]")
  value_of AUTH_SEAL_KEYS | grep -Eq '^\{.*"active" *: *"[^"]+".*"keys" *: *\{.*\}.*\}$' ||
    problems+=("AUTH_SEAL_KEYS must be the JSON key ring {\"active\":...,\"keys\":{...}}")

  # Sandbox only: live Stripe keys are refused here and by payment-service.
  v="$(value_of STRIPE_SECRET_KEY)"
  [ -z "$v" ] || [[ "$v" == sk_test_* ]] || problems+=("STRIPE_SECRET_KEY must be a test-mode key (sk_test_); live keys are not supported")
  v="$(value_of STRIPE_PUBLISHABLE_KEY)"
  [ -z "$v" ] || [[ "$v" == pk_test_* ]] || problems+=("STRIPE_PUBLISHABLE_KEY must be a test-mode key (pk_test_)")
  v="$(value_of STRIPE_WEBHOOK_SECRET)"
  [ -z "$v" ] || [[ "$v" == whsec_* ]] || problems+=("STRIPE_WEBHOOK_SECRET must start with whsec_")
  local gid gsecret
  gid="$(value_of GOOGLE_CLIENT_ID)" gsecret="$(value_of GOOGLE_CLIENT_SECRET)"
  if { [ -n "$gid" ] && [ -z "$gsecret" ]; } || { [ -z "$gid" ] && [ -n "$gsecret" ]; }; then
    problems+=("set both GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET, or neither")
  fi

  if [ "${#problems[@]}" -gt 0 ]; then
    printf '\033[0;31mxx  Invalid production configuration in %s:\033[0m\n' "$ENV_FILE" >&2
    printf '      - %s\n' "${problems[@]}" >&2
    exit 1
  fi
  if [ -z "$(value_of STRIPE_SECRET_KEY)" ] || [ -z "$(value_of STRIPE_PUBLISHABLE_KEY)" ] ||
    [ -z "$(value_of STRIPE_WEBHOOK_SECRET)" ]; then
    warn "Stripe sandbox keys incomplete: card checkout is disabled (browsing and orders UI still work)."
  fi
  [ -n "$(value_of GOOGLE_CLIENT_ID)" ] || warn "Google sign-in not configured: the Google button is hidden."
  [ "$(value_of SMTP_HOST)" != mailpit ] ||
    warn "SMTP_HOST=mailpit: emails are captured locally (http://127.0.0.1:$(value_of PROD_MAILPIT_UI_PORT)) and NOT delivered."
  [ "$(value_of PROD_TLS_MODE)" != selfsigned ] ||
    warn "PROD_TLS_MODE=selfsigned: browsers must trust $PROD_SECRETS_DIR/tls/ca.crt; use acme or files for a public site."
  return 0
}

prepare_private_material() {
  # Internal TLS (Postgres, CA for server-side calls) and the self-signed edge leaf.
  (umask 077 && mkdir -p "$PROD_SECRETS_DIR")
  chmod 700 "$PROD_SECRETS_DIR"
  REVIEW_HOST="$PROD_HOST" REVIEW_CERT_DIR="$PROD_SECRETS_DIR/tls" "$ROOT/scripts/gen-review-certs.sh" >/dev/null
  info "Internal TLS material: $PROD_SECRETS_DIR/tls"

  # gocryptfs key: generated only for a brand-new volume, never replaced.
  local key_file="$PROD_SECRETS_DIR/pgdata.key" key
  if [ -d "$key_file" ]; then rmdir "$key_file" 2>/dev/null || fail "$key_file is a directory, not a key file"; fi
  if [ ! -s "$key_file" ]; then
    if docker volume inspect "${PROJECT}_postgres_crypt_volume" >/dev/null 2>&1; then
      fail "Encrypted volume ${PROJECT}_postgres_crypt_volume exists but $key_file is missing. Restore the key from backup; a new key cannot open existing data."
    fi
    info "Generating the Postgres storage encryption key $key_file (back it up separately)"
    key="$(docker run --rm --network none --entrypoint /bin/sh "$OPENSSL_IMAGE" -c 'openssl rand -base64 32')"
    [ "${#key}" -eq 44 ] || fail "Key generation failed"
    (umask 077 && printf '%s\n' "$key" >"$key_file")
    unset key
  fi
  chmod 600 "$key_file"
  if [ "$(uname -s)" = Linux ] && [ -z "${DOCKER_HOST:-}" ] && [ ! -c /dev/fuse ]; then
    fail "/dev/fuse is missing: encrypted Postgres storage cannot start (no plaintext fallback). Load the fuse module (modprobe fuse)."
  fi
}

diagnose() {
  warn "Startup failed. Container states:"
  "${COMPOSE[@]}" ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}' >&2 || true
  local id svc state health code restarts
  while IFS='|' read -r id svc state health code; do
    [ -n "$id" ] && [ "$state" != created ] || continue
    restarts="$(docker inspect --format '{{.RestartCount}}' "$id" 2>/dev/null || echo 0)"
    if [ "$restarts" != 0 ] || [ "$state" = restarting ] || [ "$health" = unhealthy ] ||
      [ "$health" = starting ] || { [ "$state" = exited ] && [ "$code" != 0 ]; }; then
      warn "Last log lines of $svc:"
      "${COMPOSE[@]}" logs --no-color --tail 40 "$svc" >&2 || true
    fi
  done < <("${COMPOSE[@]}" ps -a --format '{{.ID}}|{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}' 2>/dev/null)
  fail "Fix the error above, then rerun 'make prod' (data and secrets are kept)."
}

smoke_check() {
  info "Smoke-checking the public origins through the TLS edge"
  "${COMPOSE[@]}" exec -T -e STORE="$PROD_STOREFRONT_ORIGIN" -e KRATOS="$PROD_KRATOS_ORIGIN" \
    -e HYDRA="$PROD_HYDRA_ORIGIN" frontend node -e '
      const checks = [
        ["Hive Router readiness", "http://hive-router:4000/readiness", (r) => r.ok],
        ["storefront home", process.env.STORE + "/", (r) => r.status === 200],
        ["catalog", process.env.STORE + "/catalog/all-products", (r) => r.status === 200],
        ["Kratos", process.env.KRATOS + "/health/alive", (r) => r.ok],
        ["Hydra discovery", process.env.HYDRA + "/.well-known/openid-configuration",
          async (r) => r.ok && (await r.json()).issuer === process.env.HYDRA],
        ["webhook route", process.env.STORE + "/webhooks/stripe", (r) => r.status === 405],
      ];
      (async () => {
        let failed = 0;
        for (const [name, url, ok] of checks) {
          try {
            const r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(15000) });
            if (await ok(r)) { console.log("    ok   " + name); continue; }
            console.log("    FAIL " + name + " (HTTP " + r.status + ")");
          } catch (e) { console.log("    FAIL " + name + " (" + (e.cause?.code || e.name) + ")"); }
          failed++;
        }
        process.exit(failed ? 1 : 0);
      })();' || diagnose
}

# Optional Stripe test keys from the Git-ignored .env.stripe (shell and .env.prod win).
# Without a configured webhook secret, the account's CLI signing secret is fetched
# and the stripe-listener container forwards events (local runs; no public URL).
STRIPE_FILE="${PROD_STRIPE_FILE:-$ROOT/.env.stripe}"
load_stripe() {
  local key v
  if [ -f "$STRIPE_FILE" ]; then
    for key in STRIPE_SECRET_KEY STRIPE_PUBLISHABLE_KEY STRIPE_WEBHOOK_SECRET; do
      v="$(sed -n "s/^$key=//p" "$STRIPE_FILE" | head -n 1 | sed "s/^['\"]//; s/['\"]\$//")"
      [ -z "$v" ] || [ -n "$(value_of "$key")" ] || export "$key=$v"
    done
  fi
  v="$(value_of STRIPE_SECRET_KEY)"
  [[ "$v" == sk_test_* || "$v" == rk_test_* ]] || return 0 # absent or invalid: validate() reports it
  [ -z "$(value_of STRIPE_WEBHOOK_SECRET)" ] || return 0
  info "Fetching the Stripe test webhook signing secret (Stripe CLI container)"
  STRIPE_WEBHOOK_SECRET="$(docker run --rm -e STRIPE_API_KEY="$v" -e STRIPE_DEVICE_NAME="$PROJECT" \
    stripe/stripe-cli:v1.53.1@sha256:11c46caeead82e329f14823dd622d494b4cc8c1b9766f724d122d2af42c97540 \
    listen --print-secret 2>/dev/null | tr -d '\r\n' || true)"
  [[ "$STRIPE_WEBHOOK_SECRET" == whsec_* ]] ||
    fail "Could not obtain a Stripe webhook signing secret; check STRIPE_SECRET_KEY and network access"
  export STRIPE_WEBHOOK_SECRET PROD_STRIPE_LISTENER=true
}

# A .env.prod written by the earlier template still has PROD_HOST=CHANGE_ME: its
# operator section was never configured, so switch that section to the local
# defaults from the template. Generated secrets are left untouched.
adopt_local_defaults() {
  grep -qx 'PROD_HOST=CHANGE_ME' "$ENV_FILE" || return 0
  local key value tmp
  tmp="$(mktemp "$ENV_FILE.XXXXXX")"
  cp -p "$ENV_FILE" "$tmp"
  for key in PROD_HOST PROD_KRATOS_PORT PROD_HYDRA_PORT PROD_BRIDGE_PORT PROD_BIND \
    PROD_TLS_MODE PROD_ACME_EMAIL SMTP_HOST SMTP_PORT SMTP_STARTTLS SMTP_IMPLICIT_TLS \
    SMTP_FROM SUPPORT_MAIL_TO; do
    value="$(sed -n "s/^$key=//p" "$TEMPLATE" | head -n1)"
    awk -v k="$key" -v v="$value" 'BEGIN { FS = OFS = "=" }
      $1 == k { print k "=" v; done = 1; next } { print }
      END { if (!done) print k "=" v }' "$tmp" >"$tmp.new" && mv "$tmp.new" "$tmp"
  done
  chmod 600 "$tmp" && mv "$tmp" "$ENV_FILE"
  info "Updated unconfigured $ENV_FILE to local defaults (https://bytecore.localhost, Mailpit); secrets kept"
}

case "$COMMAND" in
  up | check)
    [ -f "$TEMPLATE" ] || fail "$TEMPLATE is missing"
    if [ ! -f "$ENV_FILE" ]; then
      generate_env_file
      info "Created $ENV_FILE with local defaults (https://bytecore.localhost, Mailpit, no real keys)"
    else
      chmod 600 "$ENV_FILE"
      adopt_local_defaults
    fi
    load_stripe
    derive
    validate
    [ "$COMMAND" = up ] || { "${COMPOSE[@]}" config --quiet && info "Configuration valid"; exit 0; }
    prepare_private_material
    info "Building and starting project '$PROJECT' (first build takes several minutes)"
    "${COMPOSE[@]}" up -d --build --remove-orphans --wait --wait-timeout "${PROD_WAIT_TIMEOUT:-900}" || diagnose
    smoke_check
    cat <<EOF

ByteCore production-mode stack '$PROJECT' is ready:
  Storefront   $PROD_STOREFRONT_ORIGIN
  Admin        $PROD_STOREFRONT_ORIGIN/admin/security (enroll TOTP first)
  Bootstrap    $(value_of ADMIN_USERNAME)@bytecore.ee, password ADMIN_PASSWORD in $ENV_FILE (not printed)
  Emails       $(if [ "$SMTP_HOST" = mailpit ]; then echo "Mailpit http://127.0.0.1:$(value_of PROD_MAILPIT_UI_PORT) (captured, not delivered)"; else echo "SMTP relay $SMTP_HOST"; fi)
  Payments     $(if [ -z "$(value_of STRIPE_SECRET_KEY)" ]; then echo "disabled (optional: test keys in .env.stripe)"; elif [ "${PROD_STRIPE_LISTENER:-}" = true ]; then echo "Stripe test mode, webhooks via stripe-listener container"; else echo "Stripe test mode, webhook $PROD_STOREFRONT_ORIGIN/webhooks/stripe"; fi)
Stop: make prod-down (keeps all data). Docs: docs/production-setup.md
EOF
    ;;
  down)
    [ $# -eq 0 ] || fail "prod down takes no arguments; volumes are never deleted by this script"
    [ -f "$ENV_FILE" ] || fail "$ENV_FILE not found; nothing to stop"
    derive
    "${COMPOSE[@]}" down --remove-orphans
    info "Stopped project '$PROJECT'; volumes, $ENV_FILE and $PROD_SECRETS_DIR were kept."
    ;;
  status)
    [ -f "$ENV_FILE" ] || fail "$ENV_FILE not found"
    derive
    "${COMPOSE[@]}" ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}\t{{.Ports}}'
    printf '\nStorefront %s\n' "$PROD_STOREFRONT_ORIGIN"
    ;;
  logs)
    [ -f "$ENV_FILE" ] || fail "$ENV_FILE not found"
    derive
    if [ $# -eq 0 ]; then set -- --tail 200; fi
    "${COMPOSE[@]}" logs "$@"
    ;;
esac
