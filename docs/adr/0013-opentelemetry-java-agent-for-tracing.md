# 0013. Distributed tracing: OpenTelemetry Java agent to Tempo, Docker Compose only

## Status

Accepted

## Context

A `POST /api/chat` takes about 10 seconds, and the logs do not show where the time goes. One request crosses agent-app and up to three MCP servers, makes several LLM calls, and runs Postgres queries and in-process embeddings. Before fixing the latency, each step's duration must be visible in one distributed trace in Grafana.

LangChain4j's Anthropic client and its MCP client both use the JDK `HttpClient`. Spring Boot's Micrometer Observation instrumentation covers incoming HTTP and `RestClient`, but not the JDK `HttpClient`. With it, LLM and MCP calls would be invisible, and the trace would break between apps.

## Decision

- **Instrumentation:** the OpenTelemetry Java agent (`-javaagent`) in the four app images. Its version is pinned in `gradle/libs.versions.toml`, next to `opentelemetry-api` and `opentelemetry-instrumentation-annotations`. A Gradle task in `stockagent.spring-app` copies the agent jar during the Docker build stage. The jlink runtime already includes `java.instrument` (ADR 0012).
- **Automatic spans:** incoming HTTP, the JDK `HttpClient` (LLM and MCP calls, with `traceparent` propagated so one trace covers every app), JDBC, and `RestClient` (Finnhub, EDGAR).
- **Manual spans** for in-process steps, with names and durations only:
  - `@WithSpan` on the classifier, the semantic-cache lookup and store, and each MCP `@Tool` method.
  - The agentic listener: one span per Supervisor and specialist run.
  - Agent-app's tool-execution hook: one span per tool call, named after the tool.
  - The inline OTel API: the two embedding calls (filings query, semantic-cache question).
- **Backend:** Grafana Tempo in Docker Compose with no volume, like Loki (ADR 0011). The apps export OTLP straight to `http://tempo:4318`, not through Alloy. Traces are viewed in the existing Grafana, which links Loki and Tempo both ways.
- **Configuration:** one shared declarative config file (`docker/otel/otel-config.yaml`, passed with `OTEL_CONFIG_FILE`; agent 2.33.1 silently ignores the older `OTEL_EXPERIMENTAL_CONFIG_FILE`):
  - traces only, with no OTel metrics or log export (logs keep going through Alloy to Loki)
  - every request sampled, except SERVER spans on `/actuator/*`, which the SDK's `composite/development` `rule_based` sampler drops so compose health checks leave no traces (the agent's own `rule_based_routing` sampler is deprecated and logs a warning at startup). Child spans follow their parent, so a dropped health check's JDBC spans are dropped too
- **Log correlation:** the log field `traceId` is the OTel trace ID, with a UUID fallback when there is no span. `X-Trace-Id` is no longer sent on MCP calls. `correlationId` is unchanged.
- **Scope:** Docker Compose only. `bootRun` runs without the agent, and there is no AWS tracing.

## Consequences

- The agent adds startup time and some memory to every image. This does not matter for a local diagnostic setup.
- The declarative config format is experimental in the agent and can change between agent versions. The version is pinned, so an upgrade is a deliberate change.
- Specialist spans contain their LLM calls only if the listener's start and end events run on the same thread, so the span can be made current. Otherwise the LLM spans appear as siblings, with the same durations.
- Spans carry no token, cost or prompt-version attributes yet. That part of NFR-11 is deferred.
- Traces do not survive `docker compose down`.

## Alternatives considered

- **Spring Boot 4 OpenTelemetry starter (Micrometer Observation):** matches the spec's "Micrometer" wording, but misses the JDK `HttpClient` calls, which are the LLM and MCP calls, and needs another library for JDBC spans.
- **Export through Alloy:** another receiver and exporter in `config.alloy`, with nothing gained locally.
- **Keeping the health-check traces:** about 48 junk traces a minute, filtered with TraceQL. Dropping them takes one sampler rule in a file that also holds every other agent setting.
- **Downloading the agent in each Dockerfile (`ADD --checksum`):** repeats the version in four places and keeps it apart from the API version.
