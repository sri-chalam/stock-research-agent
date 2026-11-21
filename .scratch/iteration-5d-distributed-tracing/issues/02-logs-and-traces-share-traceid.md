# 02: Logs and traces share a `traceId`

**What to build:** every log line written during a traced request carries the OTel trace ID as `traceId`, so a slow `answerProvenance` line in Loki opens its trace in Tempo with one click, and a trace opens its logs.

**Blocked by:** 01

**Status:** ready-for-agent

## Decisions

- `LoggingContextFilter` sets the MDC `traceId` from the current span's trace ID. With no valid span (for example under `bootRun`, with no agent), it falls back to a random UUID, as it does today.
- `X-Trace-Id` is no longer read or sent on MCP calls: the agent's `traceparent` header carries the trace between apps. `correlationId` and `X-Correlation-Id` are unchanged.
- The GUI gets no new code: agent-app creates both IDs when a request arrives without them.
- Grafana links both ways: a derived field on the Loki data source turns `traceId` into a link to Tempo, and the Tempo data source links each span to Loki logs with the same `traceId`.
- Update the logging docs that describe `X-Trace-Id`.

## Acceptance criteria

- [ ] JUnit: `LoggingContextFilter` uses the current span's trace ID when a span exists, and a UUID when none does.
- [ ] Logs from all four apps for one `POST /api/chat` share the same `traceId`, and it equals the trace ID in Tempo.
- [ ] The `answerProvenance` log line's `traceId` links from Loki to its trace in Tempo, and the trace links back to its logs.
- [ ] MCP calls no longer carry `X-Trace-Id`, and `correlationId` still appears in agent-app and MCP-server logs for one request.
- [ ] `./gradlew build` passes.
