# Java Logging Guidelines

Java-specific rules that implement [logging-guidelines.md](logging-guidelines.md). Read that file first.

## How a log line is produced

Call sites use the SLF4J 2 fluent API and put every queryable value into two key-values:
`eventName` (a string) and `eventData` (a `Map<String, Object>`).

```java
private static final Logger log = LoggerFactory.getLogger(StockQuoteService.class);

log.atInfo()
        .addKeyValue("eventName", "stockQuoteFetched")
        .addKeyValue("eventData", Map.of(
                "tickerSymbol", quote.tickerSymbol(),
                "quoteFetchDurationTimeMs", fetchDurationMs))
        .log("Stock quote fetched");
```

`StockAgentLogFormatter` (Spring Boot structured logging, `logging.structured.format.console`) turns the event into one JSON line. It adds `className`, `level`, `service` and, from the MDC, `correlationId`, `traceId`, and `route`. A log call with no `eventName` is written with
`frameworkLogEvent` so the schema never has a gap.

## Rules

- **Never** put values in the message. The message is a fixed sentence under 80 characters.
- **Never** log through `System.out` or a parameterised message such as `log.info("x {}", value)`.
- **Never** add `timestamp` or `methodName`. The timestamp is injected downstream; `methodName` was
  dropped because capturing a line number forces a stack walk on every log call.
- `eventName` is camelCase and unique in the application. Use a verb phrase for entry points
  (`apiRequestReceived`) and a noun phrase for data (`recentQuoteCount`).
- Durations use the `DurationTimeMs` suffix and are numeric milliseconds.
- Use `debug` for intermediate state, `info` for flow events, `warn` for recoverable anomalies and
  `error` for caught exceptions.

## Exceptions

- Log an exception **once**, where it is handled. In this service that is `GlobalExceptionHandler`.
  Do not log and rethrow in lower layers.
- Every handled exception is logged at `error` with `errorCode`, `httpStatus` and `exceptionType` in
  `eventData`.
- Attach the throwable (`setCause`) only for 5xx failures, so expected 4xx failures do not produce
  stack traces.
- Exceptions from persistence or the web framework can embed SQL, bound values or request
  fragments. Log `exceptionMessageSuppressed: true` instead of the message for these.

## Request context

`LoggingContextFilter` reads `X-Correlation-Id`, validates it against `^[A-Za-z0-9-]{1,64}$`, and generates a UUID for a missing or unsafe value. It sets `traceId` to the current OpenTelemetry span's trace ID, so a log line links to its trace in Tempo; with no valid span (for example under `bootRun`, without the Java agent) `traceId` is a random UUID. No `X-Trace-Id` header is read or sent: the Java agent carries the trace between apps in `traceparent` (ADR 0013). The filter clears the MDC in `finally`. `RequestLoggingInterceptor` sets `route` to the URL template, for example `/api/v1/quotes/{tickerSymbol}`, and logs `apiRequestReceived` and `apiRequestCompleted`.

If you start threads that outlive a request, copy the MDC into them explicitly. Virtual threads do not inherit it automatically.
