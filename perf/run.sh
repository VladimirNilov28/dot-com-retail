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
    # Host-wide CPU (from /proc/stat deltas) and memory, plus a host process (e.g. dev Next server) if HOST_PID is set.
    read -r _ u n sy id io irq sirq st _ < /proc/stat
    total=$((u+n+sy+id+io+irq+sirq+st)); busy=$((total-id-io))
    if [ -n "${prev_total:-}" ]; then
      cpu=$(( 100*(busy-prev_busy)/(total-prev_total) ))
      mem=$(free | awk '/Mem:/{printf "%.1f", 100*$3/$2}')
      echo "$(date +%s),host,${cpu}%,-,${mem}%" >> "$out/docker-stats.csv"
    fi
    prev_total=$total; prev_busy=$busy
    if [ -n "${HOST_PID:-}" ] && [ -r "/proc/$HOST_PID/stat" ]; then
      # utime+stime in clock ticks; percentage of one core over the sample interval.
      ticks=$(awk '{print $14+$15}' "/proc/$HOST_PID/stat"); now=$(date +%s%N)
      if [ -n "${prev_ticks:-}" ]; then
        pcpu=$(awk -v d=$((ticks-prev_ticks)) -v ns=$((now-prev_now)) -v hz="$(getconf CLK_TCK)" 'BEGIN{printf "%.1f", 100*d/hz/(ns/1e9)}')
        rss=$(awk '/VmRSS/{print $2}' "/proc/$HOST_PID/status")
        echo "$(date +%s),host-pid,${pcpu}%,${rss}kB,-" >> "$out/docker-stats.csv"
      fi
      prev_ticks=$ticks; prev_now=$now
    fi
    sleep 5
  done
) &
sampler=$!
trap 'kill $sampler 2>/dev/null || true' EXIT
docker run --rm --network host -u "$(id -u):$(id -g)" \
  -v "$PWD/perf/k6:/scripts:ro" -v "$PWD/frontend/src/lib/graphql/generated.ts:/work/generated.ts:ro" \
  -v "$PWD/$out:/out" \
  -e SCENARIO="$scenario" -e BASE_URL="$base" -e INSECURE="${INSECURE:-0}" -e SINGLE_CLIENT="${SINGLE_CLIENT:-0}" -e DURATION="${DURATION:-3m}" \
  grafana/k6:0.57.0 run --out csv=/out/samples.csv.gz --summary-export /out/summary.json /scripts/storefront.js 2>&1 | tee "$out/k6.log" || true
kill $sampler 2>/dev/null || true
echo "Results: $out"
