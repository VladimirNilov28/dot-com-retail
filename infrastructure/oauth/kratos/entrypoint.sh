#!/bin/sh
set -eu

case "${AUTH_BRIDGE_SECRET:-}" in
  ""|replace-with*) echo "A private registration hook secret is required." >&2; exit 1 ;;
esac
if [ "${#AUTH_BRIDGE_SECRET}" -lt 32 ]; then
  echo "The registration hook secret must contain at least 32 characters." >&2
  exit 1
fi
umask 077
yq -o=json '.' /etc/kratos/kratos.yml > /tmp/bytecore-base.json
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
' /tmp/bytecore-base.json > /tmp/bytecore-registration.json
rm /tmp/bytecore-base.json
unset AUTH_BRIDGE_SECRET
exec kratos "$@" --config /tmp/bytecore-registration.json
