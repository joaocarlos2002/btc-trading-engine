# syntax=docker/dockerfile:1.7
# Reproducible image of the engine (issue #105). Build from the reactor root:
#   docker build -t btc-trading-engine btctradingengine
# or through docker-compose.yml (`docker compose up -d app`).

# ---- Build: JDK 25 + the Maven Wrapper (same Maven as CI and every dev machine) ----
FROM eclipse-temurin:25-jdk AS build
# mvnw falls back to the .tar.gz distribution without unzip, and distributionSha256Sum pins the .zip
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src

# Wrapper and poms first: this layer and the Maven cache survive source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY engine-core/pom.xml engine-core/
COPY engine-data/pom.xml engine-data/
COPY engine-trading/pom.xml engine-trading/
COPY engine-backtest/pom.xml engine-backtest/
COPY engine-app/pom.xml engine-app/
# The reactor modules depend on each other, so dependency:go-offline cannot run before their sources exist;
# a BuildKit cache mount keeps ~/.m2 between builds instead.
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -q --version

COPY config/ config/
COPY engine-core/src engine-core/src
COPY engine-data/src engine-data/src
COPY engine-trading/src engine-trading/src
COPY engine-backtest/src engine-backtest/src
COPY engine-app/src engine-app/src
# Tests and quality gates run in CI (`./mvnw verify`); the image only packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl engine-app -am package -DskipTests -Dcheckstyle.skip -Djacoco.skip \
    && cp engine-app/target/engine-app-*.jar /tmp/app.jar \
    && java -Djarmode=tools -jar /tmp/app.jar extract --layers --launcher --destination /tmp/extracted

# ---- Runtime: JRE 25 only, non-root ----
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 engine \
    && useradd --system --uid 10001 --gid engine --home-dir /app --shell /usr/sbin/nologin engine
WORKDIR /app

# Spring Boot layers, least to most frequently changing, so a code change only rebuilds the last ones.
# The engine-* modules are SNAPSHOTs and land in snapshot-dependencies.
COPY --from=build --chown=engine:engine /tmp/extracted/dependencies/ ./
COPY --from=build --chown=engine:engine /tmp/extracted/spring-boot-loader/ ./
COPY --from=build --chown=engine:engine /tmp/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=engine:engine /tmp/extracted/application/ ./

USER engine

# Inside the container the server must listen on every interface so the published port and the compose network
# (Prometheus scrapes app:8080) reach it; docker-compose.yml publishes it on 127.0.0.1 of the host only.
ENV SERVER_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# /actuator/health is public (issue #104). It reports DOWN, and the container unhealthy, while the market data
# feed is stale, which is the point. The JRE image has no curl in every variant, so the check uses bash's /dev/tcp.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "'

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
