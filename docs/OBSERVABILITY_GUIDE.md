# Observability guide

Why each piece of this project is built the way it is. Read this once, then use
[LABS.md](LABS.md).

---

## 1. Structure logs at the source, not in Logstash

Most ELK tutorials ship plain text and reconstruct it with grok. That is backwards.

| Grok in Logstash | ECS JSON from the app |
|---|---|
| CPU-heavy regex per event | Zero parsing cost |
| Breaks when a developer changes a log message | Format is a contract in code |
| Stack trace = 40 unrelated documents | One document with `error.stack_trace` |
| Field names drift per service | Field names are ECS by construction |
| `_grokparsefailure` is invisible until you look | Type errors surface at compile time |

The `logback-ecs-encoder` dependency plus `logback-spring.xml` is the whole change.
Logstash then does only what it is actually good at: routing, enrichment,
redaction and buffering.

**One grok block remains** in the pipeline, deliberately, to handle legacy
plain-text lines — you will meet this in real migrations and should know how it
looks.

## 2. Correlation is the point

Three ids, three purposes:

| Id | Set by | Answers |
|---|---|---|
| `trace.id` | Micrometer Tracing | "show me everything this request touched, across services" |
| `span.id` | Micrometer Tracing | "which step inside the request was slow" |
| `http.request.id` | `RequestCorrelationFilter` | "a user sent me a screenshot with this id" |

`http.request.id` matters more than people expect. Trace ids are 32 hex characters
and change if sampling drops the trace; a request id is always present, is echoed
in a response header, and survives even when tracing is off.

The MDC keys are **dotted** (`http.route`, `event.duration_ms`) on purpose, so the
ECS encoder writes real ECS fields rather than a flat bag of labels.

## 3. Route, don't URI

`RequestCorrelationFilter` logs `http.route` (`/api/boardgames/{id}`) alongside
`url.path` (`/api/boardgames/8231`).

Aggregating on the raw path gives you one bucket per id — millions of terms, a
useless dashboard and an expensive index. Aggregating on the route gives you a
handful of buckets that mean something. The same rule drives
`MeterFilter.maximumAllowableTags` in `ObservabilityConfig`: if the `uri` tag ever
exceeds 100 values, the meter is denied rather than allowed to poison the backend.

**This is the single most common way people destroy an Elasticsearch cluster with
observability data.**

## 4. Explicit histogram buckets

```yaml
management.metrics.distribution.slo.http.server.requests: 25ms,50ms,100ms,...
```

Without declared buckets, Micrometer publishes a default set that rarely matches
your latency profile, and every percentile you compute is an interpolation over
the wrong boundaries. Pick buckets around the SLO you actually care about.

## 5. Three probes, three meanings

| Probe | Question | Wrong answer costs you |
|---|---|---|
| liveness | "is the process wedged?" | restarts a pod that was merely slow |
| readiness | "can it serve traffic right now?" | sends traffic to a pod with a dead DB |
| startup | "has it finished booting?" | liveness kills it mid-boot, crash loop |

`CatalogueHealthIndicator` actually queries the database and reports round-trip
time, so readiness reflects reality. A health check that returns `{"status":"UP"}`
unconditionally is worse than none: it creates confident false negatives.

## 6. Data streams, ILM and templates — in that order

The bootstrap script installs, in dependency order:

1. **ILM policy** — hot (rollover at 10 GB / 1 day) → warm (forcemerge, shrink) →
   cold → delete at 30 days.
2. **Ingest pipeline** — geoip, user-agent parsing, latency bucketing.
3. **Component templates** — settings and mappings, separately, so you can reuse
   the mappings across datasets without copying settings.
4. **Index template** — composes the two, matches `logs-boardgame.*-*`,
   `"data_stream": {}`.

Key mapping decisions in `component-mappings.json`:

- `dynamic_templates` maps unknown strings to `keyword`, not `text`. Unbounded
  `text` fields on machine data are pure waste — you filter on them, you don't
  do full-text search on them.
- `message` is `match_only_text`: full-text searchable at roughly half the disk
  cost of `text`, because it drops scoring data you never use on logs.
- `error.stack_trace` is `"index": false` — stored and displayable, not searchable.
  Stack traces are enormous and nobody searches them by token.
- `index.mapping.total_fields.limit: 2000` plus `ignore_malformed: true` are
  seatbelts: a rogue field explosion gets rejected instead of taking the cluster down.
- `index.codec: best_compression` trades a little CPU for roughly 20% less disk.

**Naming:** data streams follow Elastic's `{type}-{dataset}-{namespace}` scheme —
`logs-boardgame.app-default`. Stick to it. It is what makes ILM, permissions and
Kibana integrations line up without custom configuration.

## 7. Control volume at ingest, not at query time

In the Logstash pipeline:

- Successful health-check requests are **dropped**. They are the highest-volume,
  lowest-value events on any service. Failures are kept — that's when they matter.
- `DEBUG` is **sampled at 10%**, not dropped. You keep a representative trickle
  for pattern-spotting without paying for all of it.
- Secrets, emails and card-like number sequences are **redacted with `gsub`**
  before they can be indexed. Once a password is in Elasticsearch it is in
  snapshots, in replicas, and in everyone's Discover history.

Ask of every log line: *would I page someone based on this?* If never, sample it.

## 8. Buffer, and expect to lose Elasticsearch

- `queue.type: persisted` in `logstash.yml` — events survive a Logstash restart.
- `dead_letter_queue.enable: true` — documents Elasticsearch rejects (nearly always
  mapping conflicts) land somewhere inspectable instead of vanishing.
- `max_retries: -1` in Filebeat — retry forever, because the downstream queue is
  sized to absorb it.
- `AsyncAppender` with `discardingThreshold: 0` in Logback — under pressure, keep
  WARN/ERROR. The default discards the lowest 20% of events by level, which is
  exactly the wrong choice.

Outages are when you most need logs. A pipeline that drops events under pressure
fails precisely when it matters.

## 9. Metrics into Elasticsearch without running Prometheus

The Metricbeat `prometheus` module scrapes `/actuator/prometheus` directly. Two
settings do the heavy lifting:

```yaml
use_types: true       # real ES numeric types instead of flat floats
rate_counters: true   # pre-computed rates, so counters are actually plottable
```

`metrics_filters.include` is an allowlist. Spring Boot exposes several hundred
meters by default; indexing all of them at 30s intervals is a lot of documents for
data you will never open.

## 10. Symptom-based alerting

The rules created by `setup-kibana.sh` alert on:

- elevated 5xx (users are seeing errors),
- requests over 1s (users are waiting),
- unhandled exceptions (something broke that nobody predicted).

They do **not** alert on high CPU, high memory or disk usage. Those are causes,
not symptoms. If CPU is at 95% and every user request still succeeds within SLO,
nothing is wrong yet, and paging someone teaches them to ignore the pager.

The alert most teams never write is the **absence** one: "no logs received from
`service.name: boardgame-api` for 10 minutes". Silence looks identical to health
on every dashboard you own. Build that one.

## 11. Trace sampling

Locally: `TRACE_SAMPLE_RATE=1.0`, because you want every request traceable while
learning.

In production: start at `0.1` and enable **tail-based sampling** on APM Server
(stub config included in `apm-server.yml`) so you keep 100% of failed and slow
traces and 10% of the boring ones. Head sampling at 10% means a 1-in-10 chance of
having the trace for the incident you are investigating.

## 12. Filebeat → Logstash, or Filebeat → Elasticsearch?

| Direct to Elasticsearch | Via Logstash |
|---|---|
| Fewer moving parts, lower latency | Persistent queue + DLQ |
| Enrichment only via ingest pipelines | Full conditional routing, drop, sample |
| Cheaper to run | Redaction before data leaves your control |

This project goes through Logstash because redaction and volume control are the
lessons. For a small service with clean logs, direct-to-Elasticsearch with an
ingest pipeline is a perfectly good and cheaper answer — knowing *why* you chose
one is the actual skill.

## 13. Self-monitoring

Metricbeat's `elasticsearch`, `logstash` and `kibana` modules run with
`xpack.enabled: true`, so the stack monitors itself. When log ingestion stops, the
Stack Monitoring page tells you whether Logstash is backed up, Elasticsearch is
rejecting writes, or Filebeat never sent anything. Without it, debugging a broken
pipeline is guesswork.

---

## Things deliberately left as exercises

- Cross-cluster replication and snapshot lifecycle management.
- A second service, so distributed tracing has more than one hop.
- Machine-learning anomaly detection jobs on `event.duration_ms`.
- SLO burn-rate alerting (multi-window, multi-burn-rate) — read the Google SRE
  workbook chapter and implement it with Kibana's SLO feature.
- Tail-based sampling with persistent storage on APM Server.
