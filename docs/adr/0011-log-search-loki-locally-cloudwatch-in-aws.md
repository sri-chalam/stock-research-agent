# 0011. Log search: Grafana and Loki locally, CloudWatch in AWS

## Status

Accepted

## Context

All apps write one JSON log line per event to stdout. With several containers running in Docker Compose, reading `docker logs` per container is slow, and there is no way to filter by service, level, text or `correlationId` in a browser.

The AWS deployment (Iteration 2b, ADR 0005) runs on Fargate Spot and is destroyed after each working session of 5–6 hours. Logs are needed only while working; long retention is not required.

## Decision

- **Locally:** Loki, Grafana Alloy and Grafana run in Docker Compose under an optional `observability` profile.
  - Alloy reads container stdout through the Docker socket. No app code or dependency changes.
  - Loki labels are only `service` (compose service name) and `level`. `correlationId`, `traceId` and `eventName` stay JSON fields and are queried with `| json`, to keep label cardinality low.
  - Loki stores on local disk with 72h retention. The Grafana data source is provisioned from a file.
- **In AWS:** ECS tasks use the `awslogs` driver. Log groups have 1-day retention and `RemovalPolicy.DESTROY`. Logs are searched with CloudWatch Logs Insights.

## Consequences

- Two query languages: LogQL locally, Logs Insights in AWS. Both query the same JSON fields.
- Nothing is added to the ephemeral AWS stack, so teardown (ADR 0005) and cost are unchanged.
- Log groups are deleted with the stack; logs do not survive a teardown.
- Iteration 9 (OpenTelemetry → Grafana) can add Tempo next to Loki locally and link logs to traces through `traceId`.

## Alternatives considered

- **Self-hosted Loki and Grafana on Fargate:** Fargate has no Docker socket, so each app task would need a FireLens (Fluent Bit) sidecar, plus Service Connect to reach Loki, an ALB rule and SSM-stored Grafana password. Spot interruptions lose Loki's data. Too much infrastructure for a stack destroyed every day.
- **Grafana Cloud free tier:** same UI locally and in AWS with nothing to host, but still needs a FireLens sidecar per task and sends logs outside AWS.
- **Promtail:** end of life; Alloy replaces it.
- **Loki Docker logging-driver plugin:** must be installed on the host and can block containers when Loki is down.
- **Dozzle:** simplest live log viewer, but no field queries or history.
- **OTLP log export from Spring Boot (`grafana/otel-lgtm`):** requires app changes now; revisit with tracing in Iteration 9.
