#!/usr/bin/env bash
set -euo pipefail

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=kratos_user="$KRATOS_DB_USER" \
  --set=kratos_password="$KRATOS_DB_PASSWORD" \
  <<'EOSQL'

CREATE ROLE :"kratos_user"
  LOGIN
  PASSWORD :'kratos_password';

SELECT format(
  'CREATE DATABASE %I OWNER %I',
  'kratos',
  :'kratos_user'
)
\gexec

EOSQL
