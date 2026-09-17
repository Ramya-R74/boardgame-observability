# syntax=docker/dockerfile:1.7
# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Dependency layer cached separately from source: rebuilds take seconds, not minutes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests
RUN java -Djarmode=layertools -jar target/*.jar extract --destination /build/layers

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -g root appuser

WORKDIR /app
# Spring Boot layered jar: dependencies first (rarely change), app classes last.
COPY --from=build /build/layers/dependencies/ ./
COPY --from=build /build/layers/spring-boot-loader/ ./
COPY --from=build /build/layers/snapshot-dependencies/ ./
COPY --from=build /build/layers/application/ ./

USER 10001
EXPOSE 8080

# Container-aware heap sizing + heap dump on OOM (so you can actually debug one).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djava.security.egd=file:/dev/./urandom"

# Uses the readiness group, not plain /health: readiness reflects DB reachability.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
