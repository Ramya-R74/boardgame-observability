# Hardening

This project is a **learning lab**. Several deliberate shortcuts make it start
quickly and stay readable. Fix all of these before it goes anywhere shared.

## 1. Chaos endpoints

`ChaosController` is guarded by `chaos.enabled`, which the compose file sets to
`true`. Anyone who can reach the app can leak your heap or hang your threads.

Set `CHAOS_ENABLED=false` outside local development, and delete the class entirely
before a production build.

## 2. Secrets in `.env`

Passwords are in a plaintext file and passed as environment variables, which means
they appear in `docker inspect` and in any process listing.

Use Docker secrets, Kubernetes secrets backed by an external store, or the
Elasticsearch keystore:

```bash
docker compose exec logstash bin/logstash-keystore create
docker compose exec logstash bin/logstash-keystore add ES_PASSWORD
```

## 3. TLS

`xpack.security.http.ssl.enabled: false`. All traffic between the app, Beats,
Logstash, Elasticsearch and Kibana is plaintext, including the credentials.

Generate certs with `elasticsearch-certutil`, enable HTTP SSL, and set
`ssl.certificate_authorities` in every Beat and in Logstash. This is the single
biggest gap between this lab and a real deployment.

## 4. The shippers use `elastic`

Every Beat and Logstash authenticate as the superuser. `bootstrap.sh` already
creates a least-privilege role — `boardgame_obs_shipper` — but nothing uses it yet.

Adopt it:

```bash
# create a user bound to the role
curl -u elastic:$ELASTIC_PASSWORD -X POST \
  http://localhost:9200/_security/user/obs_shipper \
  -H 'Content-Type: application/json' \
  -d '{"password":"<strong>","roles":["boardgame_obs_shipper"]}'
```

Then change `ELASTIC_USER` for the `filebeat`, `logstash`, `metricbeat` and
`heartbeat` services. Better still, issue a separate **API key** per shipper — they
can be revoked individually and carry no password to rotate.

Metricbeat needs `monitor` on the stack-monitoring modules; APM Server needs its
own privileges. Split the role per component rather than sharing one.

## 5. Actuator exposure

`health,info,metrics,prometheus,loggers,threaddump` are exposed with
`show-details: always`.

- `/actuator/loggers` lets anyone change your log levels at runtime.
- `/actuator/threaddump` leaks internal structure.
- `show-details: always` reveals database URLs and disk paths.

In production: put actuator on a separate management port
(`management.server.port: 8081`) that only the cluster network can reach, and set
`show-details: when-authorized`.

## 6. Replicas and snapshots

`number_of_replicas: 0` means a single node failure loses data. Set it to at least
1 on a multi-node cluster, and configure snapshot lifecycle management to a
repository outside the cluster. ILM deletes data at 30 days — with no snapshots,
that deletion is permanent.

## 7. Resource limits

No compose service has a memory limit. One runaway container can take the host
down. Add `deploy.resources.limits` everywhere, and match `ES_JAVA_OPTS` /
`LS_JAVA_OPTS` to roughly half the limit.

## 8. Retention and privacy

The Logstash `gsub` redaction is a regex, and regexes miss things. If the app
handles personal data, the real controls are: don't log it in the first place,
review what reaches the index periodically, and make retention short enough that
mistakes expire.

Also confirm your ILM delete phase actually matches your legal retention
obligation — in both directions.
