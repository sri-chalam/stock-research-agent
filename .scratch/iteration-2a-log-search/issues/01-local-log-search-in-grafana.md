# 01: Local log search: every container's logs in Grafana Explore

**What to build:** Plain `docker compose up` also starts Loki, Grafana Alloy and Grafana, so a developer can open Grafana in a browser and search every container's logs by service, level, text and JSON field. This is Iteration 2a from spec §14, local half only; the CloudWatch half is part of Iteration 2b. Design and rejected alternatives: ADR 0011.

**Blocked by:** None (can start immediately)

**Status:** done

## Decisions (from the Iteration 2a grilling)

- **Service names:** agent-app's `spring.application.name` is renamed from `stock-research-agent` to `agent-app`, so each app has one name in Gradle, Compose, the JSON `service` field and Loki. The repo name and the SEC `User-Agent` example stay `stock-research-agent`.
- **Labels:** only `service` (compose service name) and `level`. `level` is set for JSON-logging containers only and kept as emitted (`INFO`). `correlationId`, `traceId` and `eventName` stay JSON fields, queried with `| json`.
- **Which containers:** all of them, including Postgres (plain text, `service` label only, searchable by text).
- **Collection:** Alloy reads container stdout through the Docker socket. No app code or dependency changes beyond the rename.
- **Storage:** Loki has no volume; `docker compose down` wipes logs. No retention setting.
- **Grafana:** anonymous access with Admin role, login form off, port bound to `127.0.0.1:3000`. Loki data source provisioned from a file, so Explore works on first open.
- **Startup:** always on with plain `docker compose up`; no compose profile.
- **Images:** pin exact image versions.
- **Out of scope:** CloudWatch, CDK, traces/Tempo, dashboards, alerts, OTLP export from the apps.

## Acceptance criteria

- [ ] `docker compose up --build` starts the apps, Postgres, Loki, Alloy and Grafana; Grafana opens at `http://127.0.0.1:3000` without a login, with Loki as a ready data source.
- [ ] In Explore, `{service="filings-mcp"} | json | level="INFO" |= "<text from a known log line>"` returns only matching filings-mcp lines.
- [ ] After one `POST /api/chat` that calls a filings tool, filtering on that request's `correlationId` returns lines from `agent-app` and `filings-mcp`; a market-data question does the same for `market-data-mcp`.
- [ ] `{service="postgres"}` returns Postgres log lines.
- [ ] agent-app's JSON log lines have `"service":"agent-app"`.
- [ ] `docker compose down` followed by `up` starts with an empty Loki.
- [ ] `./gradlew build` passes.
- [ ] README has a short Iteration 2a section: how to open Grafana and two or three example queries (service + level + text, `correlationId`).
