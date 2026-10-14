#!/bin/sh
# Unlocks the gocryptfs-encrypted Postgres data directory, then execs "$@"
# (the normal Postgres entrypoint). Fails closed: without the right key the
# server never starts and nothing is written in plaintext.
#
#   PGDATA_CIPHER_DIR  encrypted volume (ciphertext only)   default /var/lib/postgresql/cipher
#   PGDATA             decrypted view Postgres uses         default /var/lib/postgresql/data
#   PGDATA_KEY_FILE    passphrase file, outside the volume  default /run/secrets/pgdata_key
#
# Requires /dev/fuse and CAP_SYS_ADMIN (plus apparmor=unconfined on AppArmor hosts).
set -eu

CIPHER_DIR="${PGDATA_CIPHER_DIR:-/var/lib/postgresql/cipher}"
PLAIN_DIR="${PGDATA:-/var/lib/postgresql/data}"
KEY_FILE="${PGDATA_KEY_FILE:-/run/secrets/pgdata_key}"

fail() {
  echo "encrypted-storage: FATAL: $*; Postgres will not start" >&2
  exit 1
}

[ -f "$KEY_FILE" ] || fail "key file $KEY_FILE is missing"
[ -s "$KEY_FILE" ] || fail "key file $KEY_FILE is empty"
[ -c /dev/fuse ] || fail "/dev/fuse is not available (run with --device /dev/fuse --cap-add SYS_ADMIN)"
mkdir -p "$CIPHER_DIR" "$PLAIN_DIR"
# gocryptfs runs as the postgres user (not root): a root daemon with -allow_other
# switches credentials per request, which fails concurrent creates with EPERM.
# Volumes created by the earlier root-mounted image hold root-owned ciphertext.
chown postgres:postgres "$CIPHER_DIR" "$PLAIN_DIR"
find "$CIPHER_DIR" \! -user postgres -exec chown -h postgres:postgres {} +
mountpoint -q "$CIPHER_DIR" || echo "encrypted-storage: warning: $CIPHER_DIR is not a volume; ciphertext lives in the container layer" >&2

if [ ! -f "$CIPHER_DIR/gocryptfs.conf" ]; then
  if [ -n "$(ls -A "$CIPHER_DIR")" ]; then
    fail "$CIPHER_DIR holds data but no gocryptfs.conf; refusing to initialise over it (migrate with pg_dumpall/restore)"
  fi
  if [ -n "$(ls -A "$PLAIN_DIR")" ]; then
    fail "$PLAIN_DIR already holds unencrypted data; refusing to hide it under a new encrypted volume"
  fi
  cat "$KEY_FILE" | gosu postgres gocryptfs -init -q "$CIPHER_DIR" >/dev/null || fail "could not initialise the encrypted volume"
  echo "encrypted-storage: initialised a new encrypted volume in $CIPHER_DIR"
fi

# The key is piped to stdin so it never needs to be readable by the postgres user.
# -allow_other (enabled via user_allow_other in /etc/fuse.conf) lets the root
# part of the Postgres entrypoint prepare the directory before dropping privileges.
status=0
cat "$KEY_FILE" | gosu postgres gocryptfs -q -allow_other "$CIPHER_DIR" "$PLAIN_DIR" || status=$?
case "$status" in
  0) ;;
  12) fail "wrong key for $CIPHER_DIR" ;;
  *) fail "could not mount $CIPHER_DIR (gocryptfs exit $status: check CAP_SYS_ADMIN, /dev/fuse, AppArmor, gocryptfs.conf)" ;;
esac
mountpoint -q "$PLAIN_DIR" || fail "$PLAIN_DIR is not mounted after unlock"
echo "encrypted-storage: unlocked; Postgres data directory and WAL are encrypted at rest"

exec "$@"
