#!/usr/bin/env bash
# Creates Kibana data views and alerting rules over the API.
# Idempotent: data views use override=true; rules are skipped if the name exists.
set -uo pipefail

KIBANA="${KIBANA_HOST:-http://localhost:5601}"
AUTH="${ELASTIC_USER:-elastic}:${ELASTIC_PASSWORD:-changeme123}"
HDR=(-H "kbn-xsrf: true" -H "Content-Type: application/json")

log() { echo "[kibana] $*"; }

log "waiting for Kibana at $KIBANA"
until curl -s -u "$AUTH" "$KIBANA/api/status" | grep -q '"level":"available"'; do sleep 5; done
log "Kibana is available"

# ---------------------------------------------------------------- data views
create_data_view() {
  local id="$1" title="$2" name="$3" body
  body=$(cat <<JSON
{"data_view":{"id":"$id","title":"$title","name":"$name","timeFieldName":"@timestamp"},"override":true}
JSON
)
  if curl -s -u "$AUTH" "${HDR[@]}" -X POST "$KIBANA/api/data_views/data_view" -d "$body" | grep -q '"id"'; then
    log "data view: $name  ($title)"
  else
    log "WARN could not create data view: $name"
  fi
}

create_data_view "boardgame-logs"    "logs-boardgame.app-*"   "Boardgame app logs"
create_data_view "boardgame-infra"   "logs-boardgame.infra-*" "Boardgame infra logs"
create_data_view "boardgame-metrics" "metricbeat-*"           "Boardgame metrics"
create_data_view "boardgame-uptime"  "heartbeat-*"            "Boardgame uptime"

# ------------------------------------------------------------ alerting rules
# Every rule below is SYMPTOM-based: it describes something a user would feel.
# Cause-based alerts ("CPU is high") are how on-call rotations burn out.

rule_exists() {
  curl -s -u "$AUTH" "$KIBANA/api/alerting/rules/_find?per_page=100" \
    | grep -qF "\"name\":\"$1\""
}

create_rule() {
  local name="$1" body="$2"
  if rule_exists "$name"; then
    log "rule already exists: $name"
    return
  fi
  if curl -s -u "$AUTH" "${HDR[@]}" -X POST "$KIBANA/api/alerting/rule" -d "$body" | grep -q '"id"'; then
    log "rule: $name"
  else
    log "WARN could not create rule: $name"
  fi
}

# 1. Error-rate symptom - users are getting 500s.
create_rule "Boardgame - 5xx responses elevated" "$(cat <<'JSON'
{
  "name": "Boardgame - 5xx responses elevated",
  "rule_type_id": ".es-query",
  "consumer": "alerts",
  "schedule": { "interval": "1m" },
  "params": {
    "searchType": "esQuery",
    "index": ["logs-boardgame.app-*"],
    "timeField": "@timestamp",
    "esQuery": "{\"query\":{\"bool\":{\"filter\":[{\"range\":{\"http.response.status_code\":{\"gte\":500}}}]}}}",
    "size": 100,
    "thresholdComparator": ">",
    "threshold": [10],
    "timeWindowSize": 5,
    "timeWindowUnit": "m",
    "excludeHitsFromPreviousRun": true
  },
  "actions": []
}
JSON
)"

# 2. Latency symptom - users are waiting.
create_rule "Boardgame - slow requests over 1s" "$(cat <<'JSON'
{
  "name": "Boardgame - slow requests over 1s",
  "rule_type_id": ".es-query",
  "consumer": "alerts",
  "schedule": { "interval": "1m" },
  "params": {
    "searchType": "esQuery",
    "index": ["logs-boardgame.app-*"],
    "timeField": "@timestamp",
    "esQuery": "{\"query\":{\"bool\":{\"filter\":[{\"range\":{\"event.duration_ms\":{\"gte\":1000}}}]}}}",
    "size": 100,
    "thresholdComparator": ">",
    "threshold": [20],
    "timeWindowSize": 5,
    "timeWindowUnit": "m",
    "excludeHitsFromPreviousRun": true
  },
  "actions": []
}
JSON
)"

# 3. Something broke that nobody predicted.
create_rule "Boardgame - unhandled exceptions" "$(cat <<'JSON'
{
  "name": "Boardgame - unhandled exceptions",
  "rule_type_id": ".es-query",
  "consumer": "alerts",
  "schedule": { "interval": "1m" },
  "params": {
    "searchType": "esQuery",
    "index": ["logs-boardgame.app-*"],
    "timeField": "@timestamp",
    "esQuery": "{\"query\":{\"bool\":{\"filter\":[{\"exists\":{\"field\":\"error.stack_trace\"}}]}}}",
    "size": 50,
    "thresholdComparator": ">",
    "threshold": [0],
    "timeWindowSize": 5,
    "timeWindowUnit": "m",
    "excludeHitsFromPreviousRun": true
  },
  "actions": []
}
JSON
)"

# 4. ABSENCE. The alert almost nobody writes, and the one that catches a broken
#    pipeline. Silence looks identical to health on every dashboard you own.
create_rule "Boardgame - no logs received (pipeline down)" "$(cat <<'JSON'
{
  "name": "Boardgame - no logs received (pipeline down)",
  "rule_type_id": ".es-query",
  "consumer": "alerts",
  "schedule": { "interval": "5m" },
  "params": {
    "searchType": "esQuery",
    "index": ["logs-boardgame.app-*"],
    "timeField": "@timestamp",
    "esQuery": "{\"query\":{\"match_all\":{}}}",
    "size": 1,
    "thresholdComparator": "<",
    "threshold": [1],
    "timeWindowSize": 10,
    "timeWindowUnit": "m"
  },
  "actions": []
}
JSON
)"

log "setup complete"
log "Open $KIBANA -> Analytics > Discover > 'Boardgame app logs'"
log "Rules are under Observability > Alerts > Manage Rules (no actions attached - add a connector)"
