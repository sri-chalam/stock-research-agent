# 02: `market-data-mcp` serves `getQuote` from Finnhub; the Quote agent calls it over MCP

**What to build:** The Iteration 1a tracer bullet. `POST /api/chat` "price of Apple" returns a live Finnhub price. The Quote agent in agent-app reaches it through the LangChain4j MCP client, and `market-data-mcp` serves it through `getQuote`. agent-app has no local tools and no Finnhub key reference.

**Blocked by:** 01

**Status:** ready-for-agent

## Decisions: market-data-mcp

### Transport (ADR 0010, written in ticket 05)

A hand-written Spring MVC controller wraps `dev.langchain4j.community.mcp.server.McpServer` (`langchain4j-community-mcp-server:1.23.0-beta33`, pinned exactly). The library ships only a stdio transport.

- `POST /mcp`: the request body (`JsonNode`) goes to `McpServer.handle(...)` and the reply is returned as `application/json`. A `null` reply (a notification) returns `202` with no body.
- `GET /mcp` returns `405`.
- The controller answers `ping` itself (`{"jsonrpc":"2.0","id":<id>,"result":{}}`). The library returns "Method not found" for it, and the client's 30 s health check uses it.
- Library limits to pin in tests:
  - It handles only `initialize`, `tools/list` and `tools/call`.
  - JSON-RPC ids must be numeric. A string id is treated as a notification and gets no reply.
  - It uses Jackson 2 internally. The app uses Jackson 3 (`tools.jackson`).
- Log `toolCallReceived` and `toolCallCompleted` (`toolName`, `durationTimeMs`) around `handle(...)` for `tools/call` only. Correlation comes from `LoggingContextFilter` (common), and `route` is `/mcp`.

### Finnhub client (CLAUDE.md §7)

- `info.saagar.stockagent.marketdata.provider`: a `FinnhubClient` interface and a `RestClientFinnhubClient` implementation.
- Spring `RestClient` on `JdkClientHttpRequestFactory`. Base URL and timeouts go in yml under `finnhub.*`: connect 2 s, read 5 s.
- The key comes from the `FINNHUB_API_KEY` env var and is sent as the `X-Finnhub-Token` header, never as a `token=` query param. If the key is missing, startup fails.
- Error mapping to an unchecked `MarketDataException` with fixed messages. A message never includes the key, the URL or the upstream body.
  - 401/403 → "Market data provider rejected the request (authorization)."
  - 429 → "Market data rate limit reached; try again in a minute."
  - 5xx → "Market data provider is unavailable."
  - timeout or I/O error → "Market data provider timed out."
- Log each failure **once**, here, as `finnhubCallFailed` (`httpStatus`, `finnhubEndpoint`, `durationTimeMs`; `warn` for 4xx/429, `error` for 5xx/timeout), then throw. The MCP library swallows the exception, so there is no later place to log it. The tool layer doesn't log it again.

### Tool

- `info.saagar.stockagent.marketdata.tool.MarketDataTools`: a plain LangChain4j `@Tool` class that depends only on `FinnhubClient`.
- `getQuote(symbol)` returns a trimmed `Quote` record, which LangChain4j serializes to compact JSON text. Fields: `symbol, currentPrice, change, percentChange, high, low, open, previousClose, asOfTime` (ISO-8601 UTC from Finnhub `t`). There is no `companyName`, because `/quote` has none and a second call would use rate-limit budget.
- No MCP `structuredContent`/`outputSchema`, because the library doesn't support them. ADR 0010 records the gap.
- The tool validates the ticker format (FR-35). An invalid ticker throws a readable message.
- Unknown symbol: Finnhub returns `c=0, t=0`, and the tool throws "No quote available for symbol X. Only US-listed equities are supported."
- **Errors are thrown, not returned.** A thrown exception becomes `isError: true` with the message as text. The MCP client then raises it, and the AiServices default `EXECUTION_ERROR_TO_LLM` passes it to the model. A returned error string would be reported as a success.

## Decisions: agent-app

- Delete `QuoteTools`, `QuoteProvider`, `FakeQuoteProvider`, `Quote`, `FakeQuoteProviderTest` and the `provider` package. Keep `ToolArguments` (used by `ChatController` and `ToolExecutionLogger`).
- Config:
  - `stock-agent.agents.quote.allowed-tools: [getQuote]` is a new field on `AgentProperties`. An empty list means no tools; there is no "all tools" default.
  - `stock-agent.mcp-servers.market-data.url: http://localhost:8081/mcp` goes in a new `McpServerProperties` map keyed by server name. Compose overrides it with an env var.
- MCP client: `StreamableHttpMcpTransport` → `DefaultMcpClient` → `McpToolProvider.Builder.filterToolNames(allowed-tools)`, wired into `QuoteAgent` through `AiServices.toolProvider(...)`.
  - `protocolVersion: 2025-11-25`, pinned in yml. This skips the `server/discover` probe, which our server answers with "Method not found".
  - `toolExecutionTimeout: 10s`. The default 30 s health check stays.
  - **Fail fast:** the client initializes eagerly, so agent-app won't start without `market-data-mcp`. Locally, start market-data-mcp first. Compose ordering is handled in ticket 04.
- Correlation: `customHeaders(Supplier)` reads `correlationId` and `traceId` from the MDC on each call and sends `X-Correlation-Id` and `X-Trace-Id`. A header is skipped when its value is missing. The supplier runs on the calling thread.
- `toolsCalled` in the chat response and `ToolExecutionLogger` keep working with MCP-provided tools.

## Tests (no live Finnhub or Anthropic; synthetic data only)

- **market-data-mcp:**
  - MockMvc on `/mcp` with a `FakeFinnhubClient`:
    - `initialize`;
    - `tools/list`, which lists `getQuote`;
    - `tools/call` success;
    - `tools/call` unknown symbol → `isError: true` with the readable text;
    - `ping`;
    - notification → `202`;
    - `GET` → `405`;
    - a string id → no reply, pinning the numeric-id behaviour.
  - `MockRestServiceServer` tests for `RestClientFinnhubClient`:
    - the key is sent in the header and not in the URL;
    - fields are trimmed;
    - 401, 429, 5xx and timeout map to the fixed messages.
  - Ticker validation.
- **agent-app:**
  - A scripted fake `ChatModel` and an in-memory fake `ToolProvider` drive `QuoteAgent` through `getQuote` and through an error result. The error text reaches the model.
  - A unit test for the MDC header supplier (both headers present; missing values skipped).
  - An allowlist test: against an in-process fake MCP endpoint that lists several tools, the provider exposes only `getQuote`.

## Acceptance criteria

- [ ] `./gradlew build` passes.
- [ ] With both apps running, a real `FINNHUB_API_KEY` in market-data-mcp's env and `ANTHROPIC_API_KEY` in agent-app's, `curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"message":"price of Apple"}'` returns a live AAPL price with `getQuote` in `toolsCalled` (manual check).
- [ ] agent-app has no `@Tool` classes and no reference to Finnhub or `FINNHUB_API_KEY`.
- [ ] One chat request's log lines share a `correlationId` across both apps (manual check).
- [ ] The Finnhub key appears in no log line or URL.
- [ ] `langchain4j-mcp` and `langchain4j-community-mcp-server` are pinned to exact versions in `libs.versions.toml`.

## Comments
