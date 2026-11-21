# 01: Tempo and the Java agent: first end-to-end trace

**What to build:** after `docker compose up`, one `POST /api/chat` (GUI or Bruno) shows up in Grafana as one distributed trace across agent-app and the MCP servers it called, with the spans the OpenTelemetry Java agent creates automatically. Health checks leave no traces.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Decisions

- ADR 0013 holds the full design.
- Tempo runs in Docker Compose with no volume, like Loki. Its ports are not published to the host. Traces are viewed in the existing Grafana, through a file-provisioned Tempo data source.
- The OpenTelemetry Java agent runs in all four app images. Its version is pinned in the version catalog, next to `opentelemetry-api` and `opentelemetry-instrumentation-annotations` (the manual-span tickets need those). A Gradle task in the `stockagent.spring-app` convention plugin copies the agent jar during the Docker build stage. Each image's entrypoint adds `-javaagent`. The jlink module list does not change, because `java.instrument` is already in it.
- One shared declarative agent config file, mounted read-only into the four app containers and passed with `OTEL_EXPERIMENTAL_CONFIG_FILE`:
  - OTLP export straight to `http://tempo:4318`, not through Alloy
  - traces only, with no OTel metrics or log export
  - every request sampled, except SERVER spans on `/actuator/*`, which a `rule_based_routing` sampler drops (check the pattern syntax against the sampler docs)
  - the service name read from an env var that compose sets per app (the compose service name)
- `bootRun` gets no agent, and the Gradle build and tests run without one.

## Acceptance criteria

- [ ] After `docker compose up`, Grafana at `http://localhost:3000` has a Tempo data source next to Loki.
- [ ] "How are my holdings doing today?" on `POST /api/chat` is one trace with spans from agent-app, `portfolio-mcp` and `market-data-mcp`. A filings question adds `filings-mcp`.
- [ ] That trace shows the automatic spans, each with a duration: the incoming `POST /api/chat`, each Anthropic LLM call, each MCP call, the Postgres queries and the Finnhub calls.
- [ ] TraceQL `{ span.http.route = "/actuator/health" }` returns nothing after the stack has been up for a minute.
- [ ] `./gradlew build` passes.
- [ ] `./gradlew :agent-app:bootRun` still starts with no agent and no errors.
