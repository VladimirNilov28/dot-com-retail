#!/bin/sh
# Review stack only: Postgres refuses a private key that is group/world
# readable or not owned by its own user, so copy the bind-mounted review
# certificate into a postgres-owned location before starting the server.
set -eu
install -d -o postgres -g postgres -m 700 /run/postgres-tls
install -o postgres -g postgres -m 644 /review-certs/postgres.crt /run/postgres-tls/server.crt
install -o postgres -g postgres -m 600 /review-certs/postgres.key /run/postgres-tls/server.key
exec docker-entrypoint.sh postgres \
  -c ssl=on \
  -c ssl_min_protocol_version=TLSv1.2 \
  -c ssl_cert_file=/run/postgres-tls/server.crt \
  -c ssl_key_file=/run/postgres-tls/server.key \
  -c hba_file=/etc/postgresql/review/pg_hba.conf
