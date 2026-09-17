#!/bin/bash
# Idempotent Elasticsearch bootstrap. Runs once at stack start.
#   1. sets the kibana_system password
#   2. installs ILM policies
#   3. installs component templates + index template + ingest pipeline
#   4. creates a least-privilege shipper role (available, not yet used - see HARDENING.md)
#
# Everything here is PUT, so re-running is safe.
set -euo pipefail

ES="${ELASTIC_HOSTS:-http://elasticsearch:9200}"
AUTH="${ELASTIC_USER:-elastic}:${ELASTIC_PASSWORD:-changeme123}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { echo "[bootstrap] $*"; }

es_put() {
  local path="$1" file="$2"
  local code
  code=$(curl -s -o /tmp/resp.json -w '%{http_code}' -u "$AUTH" \
    -X PUT "$ES$path" -H 'Content-Type: application/json' --data-binary "@$file")
  if [[ "$code" =~ ^2 ]]; then
    log "OK   PUT $path"
  else
    log "FAIL PUT $path -> HTTP $code"; cat /tmp/resp.json; echo; exit 1
  fi
}

log "waiting for Elasticsearch at $ES"
until curl -s -u "$AUTH" "$ES/_cluster/health?wait_for_status=yellow&timeout=60s" >/dev/null 2>&1; do
  sleep 3
done
log "Elasticsearch is reachable"

log "setting kibana_system password"
curl -s -u "$AUTH" -X POST "$ES/_security/user/kibana_system/_password" \
  -H 'Content-Type: application/json' \
  -d "{\"password\":\"${KIBANA_PASSWORD:-kibanapass123}\"}" >/dev/null
log "kibana_system password set"

# --- 1. ILM -----------------------------------------------------------------
es_put "/_ilm/policy/boardgame-logs-policy"    "$DIR/ilm/boardgame-logs-policy.json"
es_put "/_ilm/policy/boardgame-metrics-policy" "$DIR/ilm/boardgame-metrics-policy.json"

# --- 2. ingest pipeline (referenced as index.default_pipeline) ---------------
es_put "/_ingest/pipeline/boardgame-logs-ingest" "$DIR/templates/ingest-pipeline.json"

# --- 3. component + index templates -----------------------------------------
es_put "/_component_template/boardgame-logs-settings" "$DIR/templates/component-settings.json"
es_put "/_component_template/boardgame-logs-mappings" "$DIR/templates/component-mappings.json"
es_put "/_index_template/boardgame-logs"              "$DIR/templates/index-template-logs.json"

# --- 4. least privilege role (defined now, adopted in HARDENING.md) ----------
es_put "/_security/role/boardgame_obs_shipper" "$DIR/roles/obs-shipper-role.json"

log "bootstrap complete"
