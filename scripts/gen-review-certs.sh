#!/usr/bin/env bash
# Generates the self-signed TLS material for the review stack:
#   ca.crt          local root CA to trust in the browser/OS (public)
#   server.crt/key  leaf for REVIEW_HOST, served by the Caddy TLS edge
#   postgres.crt/key leaf for the internal `postgres` host (Postgres TLS)
# The CA is name-constrained to REVIEW_HOST and `postgres`, and its private key
# is destroyed after signing, so trusting it cannot vouch for any other site.
# openssl runs inside a pinned container: Docker is the only prerequisite.
#
# Usage: scripts/gen-review-certs.sh [--force]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVIEW_HOST="${REVIEW_HOST:-bytecore.localhost}"
CERT_DIR="${REVIEW_CERT_DIR:-$ROOT/infrastructure/review/certs}"
OPENSSL_IMAGE="alpine/openssl@sha256:e8a2319d15637db5296c0ff540bb028cfabc480b22fcdd4a020582393d32673f"

if ! printf '%s' "$REVIEW_HOST" | grep -Eq '^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$'; then
  echo "REVIEW_HOST must be a lowercase DNS name" >&2
  exit 1
fi

files=(ca.crt server.crt server.key postgres.crt postgres.key)
complete=true
for file in "${files[@]}"; do [ -s "$CERT_DIR/$file" ] || complete=false; done
if [ "$complete" = true ] && [ "${1:-}" != "--force" ]; then
  if grep -qx "$REVIEW_HOST" "$CERT_DIR/host" 2>/dev/null; then
    echo "TLS material already present in $CERT_DIR (use --force to regenerate)"
    exit 0
  fi
  echo "Existing certificates were issued for another host; regenerating for $REVIEW_HOST"
fi

mkdir -p "$CERT_DIR"
chmod 700 "$CERT_DIR"
rm -f "${files[@]/#/$CERT_DIR/}" "$CERT_DIR/host"

docker run --rm --user "$(id -u):$(id -g)" --network none \
  -e REVIEW_HOST="$REVIEW_HOST" -v "$CERT_DIR:/certs" -w /certs \
  --entrypoint /bin/sh "$OPENSSL_IMAGE" -euc '
    umask 077
    work=$(mktemp -d)
    trap "rm -rf \"$work\"" EXIT
    openssl req -x509 -new -newkey rsa:3072 -nodes -sha256 -days 825 \
      -keyout "$work/ca.key" -out ca.crt -subj "/O=ByteCore Review/CN=ByteCore Review Local CA" \
      -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
      -addext "keyUsage=critical,keyCertSign,cRLSign" \
      -addext "subjectKeyIdentifier=hash" \
      -addext "nameConstraints=critical,permitted;DNS:${REVIEW_HOST},permitted;DNS:postgres"
    issue() {
      name=$1; dns=$2
      openssl req -new -newkey rsa:2048 -nodes -keyout "$name.key" -out "$work/$name.csr" \
        -subj "/O=ByteCore Review/CN=$dns"
      printf "%s\n" "basicConstraints=critical,CA:FALSE" "keyUsage=critical,digitalSignature,keyEncipherment" \
        "extendedKeyUsage=serverAuth" "subjectAltName=DNS:$dns" "authorityKeyIdentifier=keyid" \
        "subjectKeyIdentifier=hash" > "$work/$name.ext"
      openssl x509 -req -in "$work/$name.csr" -CA ca.crt -CAkey "$work/ca.key" -set_serial "0x$(openssl rand -hex 16)" \
        -days 397 -sha256 -extfile "$work/$name.ext" -out "$name.crt"
    }
    issue server "$REVIEW_HOST"
    issue postgres postgres
    chmod 644 ca.crt server.crt postgres.crt
    chmod 600 server.key postgres.key
    openssl verify -CAfile ca.crt server.crt postgres.crt
  '
printf '%s\n' "$REVIEW_HOST" > "$CERT_DIR/host"
echo "Generated review TLS material for $REVIEW_HOST in $CERT_DIR"
echo "Trust $CERT_DIR/ca.crt in your browser/OS (see docs/review-setup.md)."
