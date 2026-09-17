#!/usr/bin/env bash
# Realistic-ish traffic so your dashboards are not empty.
# Usage: ./scripts/load-generator.sh [duration_seconds] [requests_per_second]
set -uo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
DURATION="${1:-120}"
RPS="${2:-5}"
END=$(( $(date +%s) + DURATION ))

echo "Generating ~${RPS} rps against $BASE for ${DURATION}s"

while [ "$(date +%s)" -lt "$END" ]; do
  for _ in $(seq 1 "$RPS"); do
    R=$((RANDOM % 100))
    if   [ $R -lt 45 ]; then
      curl -s -o /dev/null "$BASE/api/boardgames"
    elif [ $R -lt 60 ]; then
      curl -s -o /dev/null "$BASE/api/boardgames/$((RANDOM % 6 + 1))"
    elif [ $R -lt 72 ]; then
      curl -s -o /dev/null "$BASE/api/boardgames/$((RANDOM % 6 + 1))/reviews"
    elif [ $R -lt 82 ]; then
      curl -s -o /dev/null -X POST "$BASE/api/boardgames/$((RANDOM % 6 + 1))/reviews" \
        -H 'Content-Type: application/json' \
        -d "{\"rating\":$((RANDOM % 5 + 1)),\"comment\":\"generated review $RANDOM\"}"
    elif [ $R -lt 88 ]; then
      curl -s -o /dev/null -X POST "$BASE/api/boardgames" \
        -H 'Content-Type: application/json' \
        -d "{\"name\":\"Generated Game $RANDOM\",\"category\":\"strategy\",\"difficulty\":$((RANDOM % 10 + 1)),\"maxPlayers\":$((RANDOM % 8 + 2))}"
    elif [ $R -lt 94 ]; then
      curl -s -o /dev/null "$BASE/api/boardgames/999999"          # 404s
    else
      curl -s -o /dev/null -X POST "$BASE/api/boardgames" \
        -H 'Content-Type: application/json' -d '{"name":"","category":""}'   # 400s
    fi
  done
  sleep 1
done
echo "Done."
