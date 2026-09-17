# Troubleshooting

Work top to bottom; the pipeline fails in order.

## Nothing appears in Kibana

Run `make smoke` first — it checks every hop and tells you which one broke.

### 1. Is the app producing JSON?

```bash
docker compose logs --tail 5 app
```

You want `{"@timestamp":"...","log.level":"INFO",...}`. If you see the pretty
Spring Boot pattern instead, `SPRING_PROFILES_ACTIVE` is not `docker` — the
JSON appender only binds to the `docker,staging,prod` profiles.

### 2. Is Filebeat collecting?

```bash
curl -s http://localhost:5066/stats | jq '.filebeat.harvester, .libbeat.output'
```

`harvester.running: 0` means autodiscovery matched nothing. Check the container
label:

```bash
docker inspect boardgame-app --format '{{json .Config.Labels}}' | jq
```

`co.elastic.logs/enabled` must be the string `"true"`. Hints are opt-in — that is
by design, but it does mean a missing label produces total silence.

On macOS/Windows, also confirm `/var/lib/docker/containers` is actually readable
from the Filebeat container:

```bash
docker compose exec filebeat ls /var/lib/docker/containers | head
```

### 3. Is Logstash receiving?

```bash
curl -s http://localhost:9600/_node/stats/pipelines | jq '.pipelines.boardgame.events'
```

- `in: 0` → the problem is upstream (Filebeat).
- `in > 0, out: 0` → the filter block is dropping everything. The usual culprit is
  the health-check `drop {}` being too broad, or the `drop { percentage => 90 }`
  matching more than DEBUG.
- `filtered < in` → drops are working as intended.

### 4. Is Elasticsearch accepting?

```bash
curl -s -u elastic:$ELASTIC_PASSWORD 'http://localhost:9200/_cat/indices/*boardgame*?v'
docker compose exec logstash ls /usr/share/logstash/data/dead_letter_queue/boardgame/
```

Files in the DLQ mean documents were rejected — almost always a mapping conflict.
Inspect one:

```bash
docker compose exec logstash \
  /usr/share/logstash/bin/logstash -e 'input { dead_letter_queue { path => "/usr/share/logstash/data/dead_letter_queue" commit_offsets => false } } output { stdout { codec => rubydebug } }'
```

### 5. Does the data view exist?

`make kibana-setup`. A data view whose pattern matches nothing shows "no results"
with no error, which looks identical to "no data".

---

## Elasticsearch won't start

```bash
docker compose logs elasticsearch | tail -40
```

| Message | Fix |
|---|---|
| `max virtual memory areas vm.max_map_count [65530] is too low` | `sudo sysctl -w vm.max_map_count=262144` (Linux); on Docker Desktop restart Docker |
| `bootstrap check failure ... memory locking` | remove `bootstrap.memory_lock: "true"` or raise the memlock ulimit |
| Container exits 137 | OOM — raise Docker's memory to 8 GB, or lower `ES_JAVA_OPTS` |

## Kibana stuck on "Kibana server is not ready yet"

Normal for 2–3 minutes on first start. If it persists, `es-setup` probably failed
to set the `kibana_system` password:

```bash
docker compose logs es-setup
```

## No traces in APM

1. `curl http://localhost:8200/` — APM Server should respond.
2. Confirm the app is exporting:
   `docker compose exec app env | grep OTEL`
3. `APM_SECRET_TOKEN` must match between the `app` and `apm-server` services.
4. Check the sample rate: `TRACE_SAMPLE_RATE=1.0` locally.

## Logs have no `trace.id`

The Logstash rename only fires when the field is present. Confirm the app is
emitting it:

```bash
docker compose logs --tail 20 app | grep -o '"traceId":"[^"]*"'
```

Empty means tracing is disabled — check `management.tracing.enabled` isn't set to
false by a profile (it is, in `local`).

## Everything is slow

Nine containers plus a JVM is a lot. `docker stats` will show you which one. In
practice: give Docker 8 GB, or drop `metricbeat` and `heartbeat` while you work on
the log pipeline.
