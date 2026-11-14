# 0011. Log search: Grafana and Loki locally, CloudWatch in AWS

## Status

Accepted

## Context

All apps write one JSON log line per event to stdout. With several containers running in Docker Compose, reading `docker logs` per container is slow, and there is no way to filter by service, level, text or `correlationId` in a browser.

The AWS deployment (Iteration 2b, ADR 0005) runs on Fargate Spot and is destroyed after each working session of 5–6 hours. Logs are needed only while working; long retention is not required.

## Decision

- **Locally:** Loki, Grafana Alloy and Grafana start with plain `docker compose up`, alongside the apps. Logs are needed in every session, so they are not behind a profile.
  - Alloy reads every container's stdout through the Docker socket, including Postgres (whose plain-text logs are searchable by text only). No app code or dependency changes.
  - Loki labels are only `service` and `level`. `service` is the compose service name; agent-app's `spring.application.name` is renamed from `stock-research-agent` to `agent-app` so each app has one name in Gradle, Compose, the JSON `service` field, Loki and CloudWatch. `level` is set for JSON logs only and kept as emitted (`INFO`, the logstash layout's casing). `correlationId`, `traceId` and `eventName` stay JSON fields and are queried with `| json`, to keep label cardinality low.
  - Loki has no volume: `docker compose down` wipes logs, like the local Postgres. No retention setting is needed.
  - Grafana allows anonymous Admin access with the login form off, bound to `127.0.0.1:3000` like the other services. The Loki data source is provisioned from a file.
- **In AWS:** ECS tasks use the `awslogs` driver, one log group per service (`/stock-agent/<service>`), with 1-day retention and `RemovalPolicy.DESTROY`. Logs are searched with CloudWatch Logs Insights; a cross-service `correlationId` search selects all groups.

## Consequences

- Two query languages: LogQL locally, Logs Insights in AWS. Both query the same JSON fields.
- Nothing is added to the ephemeral AWS stack, so teardown (ADR 0005) and cost are unchanged.
- Logs do not survive `docker compose down` locally or a teardown in AWS.
- If `StockAgentLogFormatter` later emits lowercase levels, the example queries change with it.
- Iteration 9 (OpenTelemetry → Grafana) can add Tempo next to Loki locally and link logs to traces through `traceId`.

## Alternatives considered

- **Self-hosted Loki and Grafana on Fargate:** Fargate has no Docker socket, so each app task would need a FireLens (Fluent Bit) sidecar, plus Service Connect to reach Loki, an ALB rule and SSM-stored Grafana password. Spot interruptions lose Loki's data. Too much infrastructure for a stack destroyed every day.
- **Grafana Cloud free tier:** same UI locally and in AWS with nothing to host, but still needs a FireLens sidecar per task and sends logs outside AWS.
- **Promtail:** end of life; Alloy replaces it.
- **Loki Docker logging-driver plugin:** must be installed on the host and can block containers when Loki is down.
- **Dozzle:** simplest live log viewer, but no field queries or history.
- **OTLP log export from Spring Boot (`grafana/otel-lgtm`):** requires app changes now; revisit with tracing in Iteration 9.
