#!/usr/bin/env bash
set -euo pipefail

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=payment_user="$PAYMENT_DB_USER" \
  --set=payment_password="$PAYMENT_DB_PASSWORD" \
  <<'EOSQL'

CREATE ROLE :"payment_user"
  LOGIN
  PASSWORD :'payment_password';

SELECT format(
  'CREATE DATABASE %I OWNER %I',
  'payment_service',
  :'payment_user'
)
\gexec

EOSQL
