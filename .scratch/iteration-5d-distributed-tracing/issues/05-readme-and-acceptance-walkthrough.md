# 05: README and full acceptance walk-through

**What to build:** a developer can follow the README to open a chat request's trace in Grafana and see where the 10 seconds go, and Iteration 5d's acceptance criteria are checked against a running stack.

**Blocked by:** 02, 03, 04

**Status:** ready-for-agent

## Decisions

- The README section covers how to open Grafana Explore with the Tempo data source, find a chat trace with TraceQL (for example `{ span.http.route = "/api/chat" }`), and jump between a Loki log line and its trace.
- The README states the scope: Docker Compose only, no `bootRun` and no AWS tracing (ADR 0013).
- Mark Iteration 5d in the README's status table.

## Acceptance criteria

- [ ] All 9 Iteration 5d acceptance criteria in the spec, Section 14, are checked by hand in a running compose stack.
- [ ] The README section exists and its TraceQL examples return traces.
- [ ] The learning notes record one sample chat trace's breakdown: how much of the time goes to LLM calls, tools, Postgres and embeddings.
- [ ] `./gradlew build` passes.
