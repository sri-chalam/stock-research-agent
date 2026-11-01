# Logging Guidelines

## Rules (apply to every log statement)

1. **Always use JSON structured logging** -- never free-format strings, and never bury attributes inside a message string. Every piece of data must be a discrete top-level or `eventData` field so that 

   **Why:** When a Datadog dashboard needs to be built – for example, to track the P90 of message dispatch time or to detect duplicate messages -- log queries must be able to filter and aggregate on fields.
   
   **Avoid:** -- the following patterns apply in any language (Java, Python, Go, etc.); the anti-pattern is the same regardless of syntax:
   ```pseudocode
   // Value embedded inside the message string - not queryable as a field
   LOG.info("Stock quote fetched successfully for ticker <tickerSymbol>")
   PRINT("Stock quote fetched successfully for ticker <tickerSymbol> ")
   ```

2. **Every log message must include `eventName`** at the root level. The value must be unique within the application. Name it after the context and purpose of the log message - what data is being captu...
3. **All event-specific fields go under `eventData`** as name-value pairs. Values may be primitives or nested objects.
4. **Never log PII or sensitive data.** Use presence flags instead (e.g., `addressLineExists: true` not the actual value).
5. **All field names use camelCase** (e.g., `correlationId`, not `correlation_id`).
6. **Do not add a `timestamp` field** - Cloud Services scripts automatically inject it into every log message before it reaches Datadog.
7. **Duration fields must use the `DurationTimeMs` suffix** - the value must be a numeric count in milliseconds (e.g., `completeExecDurationTimeMs` for total wall-clock time, `coreExecDurationTimeMs`...
8. **On every caught exception, log at `error` level** with an `eventName` identifying the failure point. Include in `eventData` the exception message and type. Never swallow exceptions without logging
9. **Log enough context at every operation to answer: was it correct, how long did it take, and what data was involved?**
10. **Use log levels as follows - do not default everything to `info`:**

| Level | When to use |
| :--- | :--- |
| `debug` | Internal variable values and intermediate steps; not shown in production by default |
| `info` | Significant flow events: entry point, key mid-processing steps, exit point |
| `warn` | Recoverable anomalies: unexpected input, retries, fallbacks |
| `error` | Exceptions and failures -- pairs with Rule 8 |

## Root-Level Fields (include in every log message)

| Field Name | Description |
| :--- | :--- |
| `className` | Fully qualified class name |
| `correlationId` | ID to correlate messages across services |
| `eventData` | All structured, queryable details as named key-value pairs. Never put data that needs to be filtered or aggregated in `message`. |
| `eventName` | Unique event identifier within the application |
| `level` | 'debug' \| 'info' \| 'warn' \| 'error' |
| `message` | A concise, human-readable sentence describing what happened. Plain text only; no embedded values. Keep it under 80 characters. |
| `methodName` | Method name and line number |
| `route` | REST endpoint path; include only for log messages tied to a REST endpoint |
| `service` | The name of the application or service |
| `traceId` | Generated ID to trace a request across services |

## Sample Log Message

> **Note for AI agents:** The sample below is a real log message from one of the organization's services. Use it to understand the structure and field placement - do not copy 'className', 'methodName'

```json
{
  "className": "info.saagar.stockagent.service.StockQuoteService",
  "correlationId": "71bbc190-2c6f-4e17-80c7-6eac99e4d64a",
  "eventName": "stockQuoteData",
  "eventData": {
    "tickerSymbol": "AAPL",
    "quoteSource": "finnhub",
    "currentPrice": 189.42,
    "percentChange": -0.37,
    "quoteSizeInKB": 2,
    "quoteFetchStatus": "COMPLETE",
    "quoteAsOfDate": "2026-05-01T14:30:00Z",
    "hasAnyNullPriceData": false,
    "quoteFetchDurationTimeMs": 120
  },
  "level": "info",
  "message": "StockQuoteService: Stock quote data",
  "methodName": "fetchQuote Line Number: 122",
  "route": "/api/chat",
  "service": "stock-research-agent",
  "traceId": "106d9b46-868f-4321-ab73-b629db9940c1"
}
```

## What to Log

> **Critical:** The goal of logging is to make every unit of work fully observable. Log anything that answers: *Was this request or operation handled correctly? How long did it take? What data did it input or output?*

Log at these points in every processing flow:

- **Entry point** – log immediately when a message or API request is received. Use a verb-phrase `eventName` (e.g., `messageProcessingStarted`). Include in `eventData` the parameters that drive processing.
- **Mid-processing: data info** – log the state of data being processed at key steps. Use a noun-phrase `eventName` describing the data (e.g., `activeFileData`, `historicFileData`). Include sizes, counts...
- **Mid-processing: downstream calls** – log parameters sent to and responses received from every external system (SQS, S3, DB, downstream service). Use a noun-phrase `eventName` describing the interaction...
- **Exit point** – log when processing completes. Include in `eventData`: `msgProcessStartTime` (when processing began) and `completeExecDurationTimeMs` (total elapsed time).
- **Important computation steps** – any significant operation within the processing flow (e.g., portfolio risk calculation, model scoring, data enrichment) should have its duration logged. Add a *Durat...
- **Payload sizes and record counts** – whenever a method receives raw bytes as input - regardless of origin (broker, controller, internal call) - log the payload size in KB at the method's entry point.

- **Binary/byte-array inputs** – for any method that accepts `byte[]` or `InputStream` representing a binary format (Arrow IPC, Protobuf, Avro, etc.), always log `payloadSizeKB` at the entry point. Th...

### Debug-level patterns

<!-- Writing precise debug logging instructions is hard - the goal is useful debug logs without noise. This section will grow as more patterns are identified. -->
Use `debug` for intermediate state that is too granular for `info` but essential when tracing a bug. Apply it at these points:

- **Data transformation** – log at the point a message or object is transformed. Include in `eventData` the key fields that changed or the resulting shape. Use a noun-phrase `eventName` describing the data...
- **Configuration and static data loading** – Log when configuration files, lookup tables, or static data are loaded into memory. Include in `eventData` the source (file path, config key, S3 key) and a...
- **Before an external system call** – log immediately before sending a request to any external system (S3, Firehose, SQS, downstream service). Include in `eventData` the exact parameters being sent. Us...

## Logging as Part of Feature Work

Every feature addition or update is an opportunity to improve observability. When implementing or modifying a feature:
- Identify new operations introduced and ensure they have log coverage per the "What to Log" section.
- Review existing log messages in the affected code paths - if the change introduces new data or state, add those fields to the relevant `eventData` blocks.

> **Note for AI agents:** This applies to every code change you make. Do not treat logging as optional or defer it - log coverage is part of the definition of done.

<!--
Writing generic AI instructions to log DEBUG or ERROR messages is difficult.

In many cases logging debug, error messages has to be taken case by case basis.

Logging every method entry/exit - too noisy, adds no diagnostic value.
Logging exception stack traces at DEBUG - that belongs at ERROR/WARN - Many times same exception is caught at multiple levels in the chain of calls.
-->


