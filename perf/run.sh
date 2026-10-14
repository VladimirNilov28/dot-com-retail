#!/usr/bin/env bash
# Runs one k6 scenario in Docker and samples container CPU/memory alongside it.
# Usage: perf/run.sh <browse|catalog|cart|capacity> [BASE_URL]
# Only Docker is required. Results go to perf/results/<timestamp>-<scenario>/.
set -euo pipefail
cd "$(dirname "$0")/.."
scenario="${1:?scenario required}"
base="${2:-http://127.0.0.1:3000}"
out="perf/results/$(date +%Y%m%d-%H%M%S)-${scenario}"
mkdir -p "$out"
(
  while true; do
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' \
      | sed "s/^/$(date +%s),/" >> "$out/docker-stats.csv"
    sleep 5
  done
) &
sampler=$!
trap 'kill $sampler 2>/dev/null || true' EXIT
docker run --rm --network host -u "$(id -u):$(id -g)" \
  -v "$PWD/perf/k6:/scripts:ro" -v "$PWD/frontend/src/lib/graphql/generated.ts:/work/generated.ts:ro" \
  -v "$PWD/$out:/out" \
  -e SCENARIO="$scenario" -e BASE_URL="$base" -e INSECURE="${INSECURE:-0}" -e DURATION="${DURATION:-3m}" \
  grafana/k6:0.57.0 run --summary-export /out/summary.json /scripts/storefront.js 2>&1 | tee "$out/k6.log" || true
kill $sampler 2>/dev/null || true
echo "Results: $out"
