# 02: Structured logging and answer provenance for chat calls

**What to build:** Every `/api/chat` call produces structured JSON log lines that follow `docs/logging/`: a correlation ID on every line, `toolExecuted` for each tool call, and an `answerProvenance` line at the end listing the prompt ID, tools used and token counts. Errors are logged once, in one place, with the right fields. This makes a chat call fully traceable from its logs (NFR-13a groundwork for US-8).

**Blocked by:** 01: Iteration 0 skeleton

**Status:** ready-for-agent

## Decisions (from the Iteration 0 grilling)

- Use Spring Boot's built-in structured logging (JSON console output) with the SLF4J 2 fluent API and `eventName` / `eventData` key-values.
- Build `CorrelationContextFilter` (reads `X-Correlation-Id` and `X-Trace-Id`, validates against `^[A-Za-z0-9-]{1,64}$`, generates a UUID otherwise, clears the MDC in `finally`) and `GlobalExceptionHandler` (logs each handled exception once at `error` with `errorCode`, `httpStatus` and `exceptionType`; attaches the throwable only for 5xx). Both live in the `logging` package (table already in AGENTS.md).
- Log events: `toolExecuted` (tool name, arguments, duration in `DurationTimeMs`) and `answerProvenance` (prompt ID from the prompt filename, tools used, token counts).
- Deferred to a later iteration: `RequestLoggingInterceptor` and the custom `StockAgentLogFormatter`.
- No `methodName`, no `timestamp`, no values inside message strings.

## Acceptance criteria

- [ ] A chat call logs JSON lines carrying `correlationId` and `traceId`, with a provided header value echoed and an unsafe or missing one replaced by a generated UUID.
- [ ] A test (using the fake model) asserts that `toolExecuted` and `answerProvenance` events are logged with the expected `eventData` fields.
- [ ] A forced failure (e.g. the model throws) is logged exactly once by `GlobalExceptionHandler` with `errorCode`, `httpStatus` and `exceptionType`, and the client gets a clean error response.
- [ ] No log call lacks `eventName`; `eventName` values are unique across the application.
- [ ] `./gradlew build` passes.
