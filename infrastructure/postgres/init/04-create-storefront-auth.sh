#!/usr/bin/env bash
set -euo pipefail

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=migration_user="$AUTH_MIGRATION_USER" --set=migration_password="$AUTH_MIGRATION_PASSWORD" \
  --set=runtime_user="$AUTH_DB_USER" --set=runtime_password="$AUTH_DB_PASSWORD" \
  --set=captcha_user="$CAPTCHA_DB_USER" --set=captcha_password="$CAPTCHA_DB_PASSWORD" <<'EOSQL'
CREATE ROLE :"migration_user" LOGIN PASSWORD :'migration_password';
CREATE ROLE :"runtime_user" LOGIN PASSWORD :'runtime_password';
CREATE ROLE :"captcha_user" LOGIN PASSWORD :'captcha_password';
SELECT format('CREATE DATABASE storefront_auth OWNER %I', :'migration_user') \gexec
EOSQL

psql --username "$POSTGRES_USER" --dbname storefront_auth \
  --set=ON_ERROR_STOP=1 \
  --set=migration_user="$AUTH_MIGRATION_USER" \
  --set=runtime_user="$AUTH_DB_USER" --set=captcha_user="$CAPTCHA_DB_USER" <<'EOSQL'
SET ROLE :"migration_user";
\i /auth-store/001_initial.sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"runtime_user", :"captcha_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON browser_sessions, oauth_transactions, browser_flows TO :"runtime_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON captcha_challenges TO :"captcha_user";
EOSQL
