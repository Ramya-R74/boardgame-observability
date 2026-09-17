# Kibana query cheat sheet (KQL)

Paste these into Discover on the **Boardgame app logs** data view.

## Triage

| Goal | Query |
|---|---|
| All server errors | `http.response.status_code >= 500` |
| Errors with a stack trace | `error.stack_trace : *` |
| A specific user's request | `http.request.id : "b3f1c2..."` |
| Everything in one trace | `trace.id : "4bf92f3577b34da6a3ce929d0e0e4736"` |
| Slow requests | `event.duration_ms > 1000` |
| One endpoint only | `http.route : "/api/boardgames/{id}/reviews"` |
| Chaos-injected failures | `message : "CHAOS*"` |
| Exclude synthetic checks | `not user_agent.original : "Elastic-Heartbeat*"` |

## The correlation drill (do this one by hand, it is the whole point)

1. `http.response.status_code >= 500` in Discover.
2. Expand a document, copy `trace.id`.
3. Observability → APM → search the trace id → see the full span tree and which
   DB call was slow.
4. Back in Discover: `trace.id : "<paste>"` → every log line from that request,
   across the whole stack, in order.
5. Copy `http.request.id` → that is what a user can give you from a screenshot,
   and it maps back to the same trace.

## Aggregations worth saving

- Error rate over time: Lens, y-axis `count`, filter `http.response.status_code >= 500`, breakdown by `http.route`.
- p95 latency: Lens, y-axis `95th percentile of event.duration_ms`, breakdown by `http.route`.
- Top failing routes: Lens table, `http.route` terms, ordered by count of `event.outcome : failure`.
- Log volume by service: `service.name` terms — this is your cost dashboard.
