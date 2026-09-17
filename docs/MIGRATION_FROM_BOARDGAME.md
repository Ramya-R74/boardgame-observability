# Retrofitting `jaiswaladi246/Boardgame`

If you'd rather improve the original repo than start from this one, here is the
change list in the order I'd do it. Each step is independently shippable.

## What the original has today

Spring Boot **2.5.6**, Java **11**, H2 in-memory, Thymeleaf, Spring Security,
JDBC + JPA, JUnit, JaCoCo, a Dockerfile, a Jenkinsfile, and
`deployment-service.yaml`.

## What's missing for observability

| Gap | Consequence |
|---|---|
| No Actuator | No health endpoint, no metrics, no readiness probe |
| Default text logging | Every log line needs grok; stack traces fragment into 40 documents |
| No correlation id | You cannot follow one user's request through the logs |
| No tracing | You can see that a request was slow, never *where* |
| H2 in-memory | Restart wipes data; no DB metrics; not representative |
| No custom metrics | You can measure CPU but not "reviews created" |
| Spring Boot 2.5.6 (EOL) | No Micrometer Tracing, no Observation API, unpatched CVEs |
| `Dockerfile` copies a prebuilt jar | No reproducible build; logs to a file inside the container |
| No probes in the k8s manifest | Kubernetes kills slow pods and routes traffic to dead ones |
| Nexus over plain HTTP in `pom.xml` | Credentials on the wire |

---

## Step 1 — Upgrade the platform (do this first, everything depends on it)

`pom.xml`:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.3.4</version>   <!-- was 2.5.6 -->
</parent>

<properties>
  <java.version>17</java.version>   <!-- was 11 -->
</properties>
```

Then the Boot 3 migration work:

- `javax.*` → `jakarta.*` in every import (persistence, validation, servlet).
- `WebSecurityConfigurerAdapter` is gone. Replace with a `SecurityFilterChain` bean:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
            .requestMatchers(HttpMethod.GET, "/", "/boardgames/**").permitAll()
            .anyRequest().authenticated())
        .formLogin(Customizer.withDefaults());
    return http.build();
}
```

- `thymeleaf-extras-springsecurity5` → `thymeleaf-extras-springsecurity6`.
- Remove the `org.jacoco:jacoco-maven-plugin` entry from `<dependencies>` — it is
  a plugin, not a dependency; it is in the wrong block in the original pom.
- Change the `<distributionManagement>` Nexus URLs from `http://` to `https://`.

Run the tests. Fix what breaks. Then continue.

## Step 2 — Add the observability dependencies

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
  <groupId>co.elastic.logging</groupId>
  <artifactId>logback-ecs-encoder</artifactId>
  <version>1.6.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

## Step 3 — Structured logging

Copy `src/main/resources/logback-spring.xml` from this project **verbatim**. It is
framework-agnostic; nothing in it references the board game domain.

Biggest single-file improvement in the whole list.

## Step 4 — Correlation filter

Copy `src/main/java/com/devopsobs/boardgame/config/RequestCorrelationFilter.java`,
change the package declaration, done. It has no dependencies on the rest of this
project.

You now have a request id in every log line and an ECS access log with
`http.route` and `event.duration_ms`.

## Step 5 — Actuator configuration

Add to `application.properties` (or convert to `application.yml`, which I'd
recommend):

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus,loggers
management.endpoint.health.show-details=always
management.endpoint.health.probes.enabled=true
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.metrics.distribution.slo.http.server.requests=25ms,50ms,100ms,250ms,500ms,1s,2s,5s
management.metrics.tags.application=${spring.application.name}
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://apm-server:8200}/v1/traces
server.shutdown=graceful
spring.jpa.open-in-view=false
```

⚠️ Do **not** use `management.endpoints.web.exposure.include=*`. That exposes
`/actuator/heapdump` and `/actuator/env`, and the original app has no
authentication rule protecting the actuator path.

## Step 6 — Business metrics

Copy `metrics/BusinessMetrics.java` and adapt the repository types. In
`BoardGameService` (the original calls it `BoardGameService` too), inject it and
call `metrics.reviewCreated(...)` where a review is saved.

The point: `boardgame_reviews_created_total` tells you the product works.
`jvm_memory_used_bytes` does not.

## Step 7 — Replace H2 with PostgreSQL

In-memory H2 means no persistence, no connection-pool metrics, no realistic DB
latency to observe, and no dependency to fail on purpose.

```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

Keep H2 for tests only (`<scope>test</scope>`). Use this project's
`application-docker.yml` datasource block and the compose `postgres` service.

## Step 8 — Fix the Dockerfile

The original copies a prebuilt jar. Replace with the multi-stage layered build in
this project's `Dockerfile`. Specifically:

- Multi-stage so `mvn package` happens in the image — reproducible builds.
- `layertools extract` so dependency layers cache across commits.
- Non-root user.
- `-XX:MaxRAMPercentage=70` so the JVM respects the container memory limit. Java
  11 without this ignores cgroup limits in some configurations and gets OOM-killed.
- `HEALTHCHECK` against `/actuator/health/readiness`.
- **Log to stdout only.** Never write log files inside a container; the collector
  reads stdout and the file just fills the layer.

## Step 9 — Fix the Kubernetes manifest

`deployment-service.yaml` in the original has no probes and no resource limits.
Use `k8s/app-deployment.yaml` from this project as the template: three distinct
probes, requests/limits, a `preStop` sleep for connection draining, and the
Filebeat annotations.

## Step 10 — Drop in the ELK stack

Copy the whole `observability/` directory, `docker-compose.yml`, `Makefile` and
`scripts/`. Adjust in `docker-compose.yml`:

- the `app.build.context` path,
- `POSTGRES_*` variables,
- the `co.elastic.logs/*` labels (copy them exactly).

Then `make up && make traffic && make smoke`.

## Step 11 — Extend the Jenkinsfile

The original Jenkinsfile covers compile → test → SonarQube → build → push → deploy.
Add two stages:

```groovy
stage('Validate observability config') {
  steps {
    sh '''
      docker run --rm -v "$PWD/observability/logstash/pipeline:/pipeline:ro" \
        docker.elastic.co/logstash/logstash:8.15.3 \
        logstash -t -f /pipeline/boardgame.conf --path.settings /usr/share/logstash/config
      docker run --rm -v "$PWD/observability/filebeat/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro" \
        docker.elastic.co/beats/filebeat:8.15.3 filebeat test config --strict.perms=false
    '''
  }
}

stage('Post-deploy smoke') {
  steps { sh './scripts/smoke-test.sh' }
}
```

Config files are code. A broken Logstash pipeline caught in CI costs minutes;
caught in production it costs you the logs from the incident you were trying to
investigate.

---

## Priority order if you only do some of it

1. **Step 3** (ECS logging) — biggest return, one file.
2. **Step 4** (correlation filter) — one file, makes logs usable.
3. **Step 5** (actuator config) — unlocks metrics and probes.
4. **Step 1** (Boot 3 upgrade) — required for tracing, and 2.5.6 is EOL anyway.
5. Everything else.
