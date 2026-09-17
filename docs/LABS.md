# Labs

Each lab: break something, investigate it using only the observability stack, then
verify the fix. Do not read the "what you should find" section until after you've
looked.

Prerequisite: `make up && make traffic` (leave traffic running in a second terminal).

---

## Lab 1 — Follow one request end to end

**Do:** `curl -i http://localhost:8080/api/boardgames/1/reviews` and copy the
`X-Request-Id` response header.

**Find:** In Discover, `http.request.id : "<paste>"`. You should see the access log
line plus any application logs from the same request. Open the document, copy
`trace.id`, and search that in APM.

**Point:** Three ids, three lookup paths, one request. If this doesn't work,
nothing else in the stack will.

---

## Lab 2 — Error burst

**Do:** `make chaos SCENARIO=error-burst`

**Investigate:** Discover → `http.response.status_code >= 500`. Break it down by
`http.route`. Then find one document with `error.stack_trace : *`.

**What you should find:** all failures on `/api/chaos/error`, an
`IllegalStateException`, and a stack trace in a single document rather than
scattered across 40 lines. Check that the "5xx responses elevated" rule fired in
**Observability → Alerts**.

---

## Lab 3 — Latency spike

**Do:** `make chaos SCENARIO=latency-spike`

**Investigate:** Lens on the logs data view: y-axis = 95th percentile of
`event.duration_ms`, breakdown by `http.route`. Then APM → transaction duration
distribution.

**What you should find:** p95 climbs for one route only, while the others stay
flat. The `slow_request` tag (added by Logstash) is on every affected document.

**Follow-up:** Why does the *average* barely move while p95 doubles? That answer
is why you never alert on averages.

---

## Lab 4 — Memory leak

**Do:** `make chaos SCENARIO=memory-leak`

**Investigate:** Metrics → `prometheus.metrics.jvm_memory_used_bytes` filtered to
`area: heap`. Compare against `docker.memory.usage.pct` for the same container.

**What you should find:** heap climbs and never returns to baseline after GC. The
container memory follows. Release it with
`curl -X DELETE http://localhost:8080/api/chaos/memory-leak` and watch it recover.

**Point:** The shape is what matters — a sawtooth that resets is healthy GC, a
staircase that never resets is a leak.

---

## Lab 5 — Dependency failure

**Do:** `make chaos SCENARIO=db-down`

**Investigate:** Uptime → the readiness check goes down. Discover → look for
connection exceptions. Check the `postgres-port` TCP monitor.

**What you should find:** readiness fails while liveness stays up — which is
exactly right. Kubernetes would stop routing traffic but not restart the pod,
because restarting doesn't fix a dead database.

**Follow-up:** Change the readiness group in `application.yml` to exclude `db` and
repeat. Notice that the app now claims to be ready while every request fails. That
is the bug this design prevents.

---

## Lab 6 — Log storm and ILM

**Do:** `make chaos SCENARIO=log-storm`

**Investigate:**
```bash
curl -s http://localhost:9600/_node/stats/pipelines | jq '.pipelines.boardgame.events'
curl -s -u elastic:$ELASTIC_PASSWORD 'http://localhost:9200/_data_stream/logs-boardgame.app-default?pretty'
curl -s -u elastic:$ELASTIC_PASSWORD 'http://localhost:9200/logs-boardgame.app-*/_ilm/explain?pretty'
```

**What you should find:** the Logstash queue grows and drains; ILM reports which
phase the backing index is in and how far it is from rollover.

**Follow-up:** Lower `max_primary_shard_size` to `50mb` in
`boardgame-logs-policy.json`, re-run `bootstrap.sh`, storm again, and watch an
actual rollover happen.

---

## Lab 7 — The mapping conflict

**Do:** Send a document where a numeric field arrives as a string:
```bash
curl -X POST http://localhost:8080/api/boardgames \
  -H 'Content-Type: application/json' \
  -d '{"name":"Mapping Test","category":"test","difficulty":3,"maxPlayers":4}'
```
Then comment out the `mutate { convert => ... }` block in
`observability/logstash/pipeline/boardgame.conf`, restart Logstash
(`docker compose restart logstash`), and generate traffic.

**Investigate:** Check the dead letter queue:
```bash
docker compose exec logstash ls -la /usr/share/logstash/data/dead_letter_queue/boardgame/
```

**What you should find:** rejected documents, because `event.duration_ms` arrives
as a string into a `long` field. Without the DLQ these events would be gone with
only a line in Logstash's own log. Put the `convert` block back.

**Point:** This is the single most common ELK production failure, and it fails
*silently* unless you configured the DLQ in advance.

---

## Lab 8 — Write an absence alert

Nothing to break. In Kibana → Observability → Alerts, create an Elasticsearch
query rule on `logs-boardgame.app-*` with `thresholdComparator: "<"`, threshold 1,
window 10 minutes.

Then `docker compose stop filebeat` and wait.

**Point:** Every dashboard you own looks perfect when data stops arriving. Absence
alerts are the only thing that catches a broken pipeline.

---

## Lab 9 — Cardinality explosion

**Do:** In `RequestCorrelationFilter`, change the `http.route` MDC value to use
`request.getRequestURI()` instead of the matched pattern. Rebuild
(`make rebuild-app`), run `make traffic`, then:

```bash
curl -s -u elastic:$ELASTIC_PASSWORD \
  'http://localhost:9200/logs-boardgame.app-*/_search?size=0' -H 'Content-Type: application/json' -d '
  {"aggs":{"routes":{"cardinality":{"field":"http.route"}}}}' | jq
```

**What you should find:** the cardinality climbs without bound as ids vary. Now
imagine that field on a metric with 50 million users. Revert the change.

---

## Lab 10 — Build the dashboard

No breakage. Build a single dashboard with four panels:

1. **Traffic** — request count over time, broken down by `http.route`.
2. **Errors** — error rate: count where `http.response.status_code >= 500`, as a
   percentage of total.
3. **Latency** — p50 / p95 / p99 of `event.duration_ms`.
4. **Saturation** — `jvm_memory_used_bytes` (heap) and Hikari active connections.

Those are the four golden signals. Save it, then run each chaos scenario and check
that you'd have spotted the problem from this one screen. If a scenario is
invisible here, the dashboard is missing something.
