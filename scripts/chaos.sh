#!/usr/bin/env bash
# Failure injection scenarios. Run one, then investigate it in Kibana.
# Usage: ./scripts/chaos.sh <scenario>
set -uo pipefail
BASE="${BASE_URL:-http://localhost:8080}"

usage() {
cat <<'USAGE'
Scenarios:
  latency-spike   30s of 2s responses          -> watch p95 in APM + slow_request tag
  error-burst     60s of 30% 500s              -> fires the 5xx alerting rule
  bad-deploy      100% failure for 45s         -> simulates a broken release
  memory-leak     leak 200MB                   -> watch jvm_memory_used in Metricbeat
  cpu-burn        60s of CPU saturation        -> watch system.cpu + container metrics
  outage          health goes DOWN for 60s     -> watch Uptime + readiness probe
  log-storm       50k log lines                -> watch Logstash queue + ILM rollover
  db-down         stop postgres for 45s        -> dependency failure, readiness DOWN
USAGE
}

case "${1:-}" in
  latency-spike)
    echo ">> 30s of injected latency"
    END=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$END" ]; do curl -s -o /dev/null "$BASE/api/chaos/latency?ms=2000" & sleep 1; done; wait ;;
  error-burst)
    echo ">> 60s of 30% errors"
    END=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$END" ]; do curl -s -o /dev/null "$BASE/api/chaos/error?rate=30"; sleep 0.2; done ;;
  bad-deploy)
    echo ">> total failure for 45s"
    END=$(( $(date +%s) + 45 ))
    while [ "$(date +%s)" -lt "$END" ]; do curl -s -o /dev/null "$BASE/api/chaos/error?rate=100"; sleep 0.2; done ;;
  memory-leak)
    echo ">> leaking 200MB"
    for _ in $(seq 1 20); do curl -s -o /dev/null -X POST "$BASE/api/chaos/memory-leak?mb=10"; sleep 1; done
    echo "release with: curl -X DELETE $BASE/api/chaos/memory-leak" ;;
  cpu-burn)
    echo ">> burning CPU"
    for _ in $(seq 1 4); do curl -s -o /dev/null "$BASE/api/chaos/cpu?seconds=60" & done; wait ;;
  outage)
    echo ">> forcing health DOWN for 60s"
    curl -s -o /dev/null -X POST "$BASE/api/chaos/unhealthy?on=true"; sleep 60
    curl -s -o /dev/null -X POST "$BASE/api/chaos/unhealthy?on=false"; echo "recovered" ;;
  log-storm)
    echo ">> 50k log lines"
    curl -s -o /dev/null -X POST "$BASE/api/chaos/log-storm?lines=50000" ;;
  db-down)
    echo ">> stopping postgres for 45s"
    docker compose stop postgres; sleep 45; docker compose start postgres ;;
  *) usage ;;
esac
