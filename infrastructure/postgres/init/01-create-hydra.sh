#!/usr/bin/env bash
set -euo pipefail

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=hydra_user="$HYDRA_DB_USER" \
  --set=hydra_password="$HYDRA_DB_PASSWORD" \
  <<'EOSQL'

CREATE ROLE :"hydra_user"
  LOGIN
  PASSWORD :'hydra_password';

SELECT format(
  'CREATE DATABASE %I OWNER %I',
  'hydra',
  :'hydra_user'
)
\gexec

EOSQL