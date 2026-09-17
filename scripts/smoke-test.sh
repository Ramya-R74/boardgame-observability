#!/usr/bin/env bash
# Verifies the whole pipeline end to end: app -> Filebeat -> Logstash -> ES.
# This is the script to run when "nothing shows up in Kibana".
set -uo pipefail

ES="${ES_URL:-http://localhost:9200}"
KB="${KIBANA_URL:-http://localhost:5601}"
APP="${BASE_URL:-http://localhost:8080}"
AUTH="elastic:${ELASTIC_PASSWORD:-changeme123}"
FAIL=0

check() {
  printf '%-52s' "$1"
  if eval "$2" >/dev/null 2>&1; then echo "OK"; else echo "FAIL"; FAIL=1; fi
}

echo "=== component reachability ==="
check "app responds"            "curl -fsS $APP/actuator/health"
check "app exposes prometheus"  "curl -fsS $APP/actuator/prometheus | grep -q boardgame_"
check "elasticsearch healthy"   "curl -fsS -u $AUTH $ES/_cluster/health | grep -qE 'green|yellow'"
check "kibana available"        "curl -fsS $KB/api/status | grep -q available"
check "logstash pipeline up"    "curl -fsS http://localhost:9600/_node/pipelines | grep -q boardgame"
check "filebeat alive"          "curl -fsS http://localhost:5066/stats"
check "apm server alive"        "curl -fsS http://localhost:8200/"

echo
echo "=== elasticsearch objects ==="
check "ILM policy installed"    "curl -fsS -u $AUTH $ES/_ilm/policy/boardgame-logs-policy | grep -q phases"
check "index template installed" "curl -fsS -u $AUTH $ES/_index_template/boardgame-logs | grep -q logs-boardgame"
check "ingest pipeline installed" "curl -fsS -u $AUTH $ES/_ingest/pipeline/boardgame-logs-ingest | grep -q processors"

echo
echo "=== data flowing ==="
echo "generating traffic..."
for i in $(seq 1 25); do curl -s -o /dev/null "$APP/api/boardgames"; done
curl -s -o /dev/null "$APP/api/boardgames/999999"
echo "waiting 20s for the pipeline..."
sleep 20

COUNT=$(curl -fsS -u "$AUTH" "$ES/logs-boardgame.app-*/_count" 2>/dev/null | grep -o '"count":[0-9]*' | cut -d: -f2)
printf '%-52s' "documents in logs-boardgame.app-*"
if [ "${COUNT:-0}" -gt 0 ]; then echo "OK ($COUNT)"; else echo "FAIL (0)"; FAIL=1; fi

printf '%-52s' "trace.id present on log documents"
if curl -fsS -u "$AUTH" "$ES/logs-boardgame.app-*/_search?size=1&q=trace.id:*" 2>/dev/null | grep -q '"trace"'; then
  echo "OK"; else echo "WARN (APM may still be starting)"; fi

printf '%-52s' "APM traces indexed"
if curl -fsS -u "$AUTH" "$ES/traces-apm*/_count" 2>/dev/null | grep -qv '"count":0'; then echo "OK"; else echo "WARN"; fi

echo
[ $FAIL -eq 0 ] && echo "ALL CHECKS PASSED" || { echo "SOME CHECKS FAILED - see docs/TROUBLESHOOTING.md"; exit 1; }
