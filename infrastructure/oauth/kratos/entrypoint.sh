#!/bin/sh
set -eu
# Fail the whole config pipeline (yq | jq | jq) on any stage error.
set -o pipefail

case "${AUTH_BRIDGE_SECRET:-}" in
  ""|replace-with*) echo "A private registration hook secret is required." >&2; exit 1 ;;
esac
if [ "${#AUTH_BRIDGE_SECRET}" -lt 32 ]; then
  echo "The registration hook secret must contain at least 32 characters." >&2
  exit 1
fi
# Browser-facing origins (the review stack sets HTTPS origins); defaults are
# the local HTTP development origins already written in kratos.yml.
store_origin=${PUBLIC_STOREFRONT_ORIGIN:-http://127.0.0.1:3000}
kratos_origin=${PUBLIC_KRATOS_ORIGIN:-http://127.0.0.1:4433}
bridge_origin=${PUBLIC_BRIDGE_ORIGIN:-http://127.0.0.1:4446}
for value in "$store_origin" "$kratos_origin" "$bridge_origin"; do
  if ! printf '%s' "$value" | grep -Eq '^https?://[A-Za-z0-9.-]+(:[0-9]+)?$'; then
    echo "Public origins must be exact http(s) origins without a path." >&2
    exit 1
  fi
done
umask 077
runtime=/home/ory/bytecore-runtime
mkdir -p "$runtime"
yq -o=json '.' /etc/kratos/kratos.yml |
  jq --arg store "$store_origin" --arg kratos "$kratos_origin" --arg bridge "$bridge_origin" '
    def swap($from; $to): if startswith($from) then $to + .[($from | length):] else . end;
    walk(if type == "string" then
      swap("http://127.0.0.1:3000"; $store) | swap("http://127.0.0.1:4433"; $kratos) | swap("http://127.0.0.1:4446"; $bridge)
    else . end)
  ' > "$runtime/bytecore-base.json"
jq '
  .selfservice.flows.registration.after.password.hooks = [
    {hook: "web_hook", config: {
      url: "http://oauth-service:4446/internal/registration/pre-persist",
      method: "POST", body: "file:///etc/kratos/registration-hook.jsonnet",
      can_interrupt: true, response: {parse: true},
      auth: {type: "api_key", config: {
        name: "Authorization", value: ("Bearer " + env.AUTH_BRIDGE_SECRET), in: "header"
      }}
    }},
    {hook: "web_hook", config: {
      url: "http://oauth-service:4446/internal/registration/bind",
      method: "POST", body: "file:///etc/kratos/registration-bind.jsonnet",
      auth: {type: "api_key", config: {
        name: "Authorization", value: ("Bearer " + env.AUTH_BRIDGE_SECRET), in: "header"
      }}
    }},
    {hook: "show_verification_ui"}
  ]
' "$runtime/bytecore-base.json" |
  jq -f /etc/kratos/google-config.jq > "$runtime/bytecore-registration.json"
rm "$runtime/bytecore-base.json"
# Production SMTP (compose.prod.yml): when SMTP_HOST is given, the courier URI
# is built from SMTP_* instead of kratos.yml's dev Mailpit URI. Credentials are
# URL-encoded; plain SMTP is used only when neither TLS mode is requested.
if [ -n "${SMTP_HOST:-}" ]; then
  case "${SMTP_IMPLICIT_TLS:-false}" in true) scheme=smtps ;; *) scheme=smtp ;; esac
  query=""
  if [ "$scheme" = smtp ] && [ "${SMTP_STARTTLS:-false}" != true ]; then
    query="?disable_starttls=true"
  fi
  userinfo=""
  if [ -n "${SMTP_USERNAME:-}" ]; then
    userinfo="$(jq -rn --arg u "$SMTP_USERNAME" --arg p "${SMTP_PASSWORD:-}" '($u|@uri) + ":" + ($p|@uri) + "@"')"
  fi
  COURIER_SMTP_CONNECTION_URI="${scheme}://${userinfo}${SMTP_HOST}:${SMTP_PORT:-587}/${query}"
  export COURIER_SMTP_CONNECTION_URI
  [ -z "${SMTP_FROM:-}" ] || export COURIER_SMTP_FROM_ADDRESS="$SMTP_FROM"
  unset userinfo SMTP_PASSWORD
fi
unset AUTH_BRIDGE_SECRET GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET
exec kratos "$@" --config "$runtime/bytecore-registration.json"
