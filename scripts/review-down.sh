#!/usr/bin/env bash
# Stops the reviewer stack. Data volumes (Postgres, Kafka, media) are kept
# unless --volumes is passed explicitly.
#
#   scripts/review-down.sh             # or: make review-down
#   scripts/review-down.sh --volumes   # also delete this project's data volumes
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${REVIEW_PROJECT:-bytecore-review}"
ENV_FILE="${REVIEW_ENV_FILE:-$ROOT/.env.review}"
args=(down --remove-orphans)

case "${1:-}" in
  "") ;;
  --volumes) args+=(--volumes) ;;
  *) echo "usage: $0 [--volumes]" >&2; exit 2 ;;
esac
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE not found; nothing to stop" >&2; exit 1; }

docker compose -p "$PROJECT" -f "$ROOT/infrastructure/compose.yml" \
  -f "$ROOT/infrastructure/compose.review.yml" --env-file "$ENV_FILE" "${args[@]}"
if [ "${1:-}" = --volumes ]; then
  echo "Removed project '$PROJECT' and its volumes. $ENV_FILE and the certificates were kept."
else
  echo "Stopped project '$PROJECT'; data volumes kept (use --volumes to delete them)."
fi
