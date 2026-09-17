# Board Game Observability

A Spring Boot board game catalogue wired for ELK-based observability, built as a
hands-on learning project. It is the Java/Elastic counterpart to the KodeKloud
Records Store (Python/Prometheus/Grafana/Loki/Jaeger), using the same teaching
approach: real app, real failure injection, real investigation.

## What you get

| Signal | Produced by | Stored in | Viewed in |
|---|---|---|---|
| Logs | Logback + ECS encoder (structured JSON) | `logs-boardgame.app-*` data stream | Discover / Logs |
| Metrics | Micrometer → `/actuator/prometheus` | `metricbeat-*` | Metrics / Lens |
| Traces | Micrometer Tracing → OTLP → APM Server | `traces-apm-*` | APM |
| Uptime | Heartbeat synthetic checks | `heartbeat-*` | Uptime |
| Infra | Metricbeat docker + system modules | `metricbeat-*` | Infrastructure |

All four are joined by `trace.id`, `service.name` and `http.request.id`, so you
can go from an alert to a log line to a trace to the exact slow SQL statement.

## Architecture

```
                   ┌──────────────┐
  user  ─────────► │  boardgame   │──── OTLP/HTTP ────► apm-server ──┐
                   │   Spring     │                                   │
                   │   Boot app   │──── /actuator/prometheus ◄─ metricbeat ──┤
                   └──────┬───────┘                                   │
                          │ ECS JSON on stdout                        │
                          ▼                                           ▼
                 docker json-file log ──► filebeat ──► logstash ──► elasticsearch
                                            (autodiscover) (enrich,      │
                                                            redact,      │
                                                            route)       ▼
     heartbeat ──── synthetic HTTP/TCP ─────────────────────────────► kibana
```

## Quickstart

```bash
cp .env.example .env
make up            # builds the app and starts 9 containers (~3 min cold)
make traffic       # 2 minutes of generated load
make smoke         # verifies the pipeline end to end
```

Then open <http://localhost:5601> (user `elastic`, password from `.env`) and go to
**Analytics → Discover → Boardgame app logs**.

Requires Docker with **at least 8 GB** of memory allocated.

| Service | URL |
|---|---|
| App | <http://localhost:8080> |
| Kibana | <http://localhost:5601> |
| Elasticsearch | <http://localhost:9200> |
| APM Server | <http://localhost:8200> |
| Logstash monitoring API | <http://localhost:9600> |

## Break it on purpose

```bash
make chaos SCENARIO=error-burst     # 30% 500s for a minute
make chaos SCENARIO=latency-spike   # 2s responses
make chaos SCENARIO=memory-leak     # 200MB of retained heap
make chaos SCENARIO=db-down         # dependency failure
make chaos SCENARIO=log-storm       # 50k lines, watch the queue
./scripts/chaos.sh                  # full list
```

Every scenario has a matching investigation in [docs/LABS.md](docs/LABS.md).

## Documentation

| File | What's in it |
|---|---|
| [docs/OBSERVABILITY_GUIDE.md](docs/OBSERVABILITY_GUIDE.md) | Why each design decision was made |
| [docs/LABS.md](docs/LABS.md) | 10 guided exercises, break → investigate → fix |
| [docs/MIGRATION_FROM_BOARDGAME.md](docs/MIGRATION_FROM_BOARDGAME.md) | Exact changes to retrofit the original `jaiswaladi246/Boardgame` repo |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | "Nothing is showing in Kibana" |
| [docs/HARDENING.md](docs/HARDENING.md) | What to change before this touches a shared environment |
| [observability/kibana/QUERIES.md](observability/kibana/QUERIES.md) | KQL cheat sheet |

## Layout

```
├── src/main/java/com/devopsobs/boardgame/
│   ├── config/RequestCorrelationFilter.java   # request id + ECS access log
│   ├── config/ObservabilityConfig.java        # common tags, cardinality guard
│   ├── metrics/BusinessMetrics.java           # custom business metrics
│   ├── health/CatalogueHealthIndicator.java   # a health check that means something
│   └── web/ChaosController.java               # failure injection
├── src/main/resources/logback-spring.xml      # ECS JSON logging
├── observability/
│   ├── elasticsearch/    # ILM, component templates, ingest pipeline, bootstrap
│   ├── logstash/         # enrichment + redaction + routing pipeline
│   ├── filebeat/         # hint-based autodiscovery
│   ├── metricbeat/       # prometheus scrape + docker + system + stack monitoring
│   ├── heartbeat/        # synthetic checks
│   ├── apm-server/       # OTLP receiver
│   └── kibana/           # data views + alerting rules via API
├── scripts/              # load generator, chaos, smoke test
├── k8s/                  # DaemonSet-based collection variant
└── docs/
```

## License

MIT. Built for learning — the chaos endpoints alone should stop you shipping it.
