# 01: `filings-mcp` serves `listFilings` from SEC EDGAR; the Filings analyst calls it over MCP

**What to build:** The Iteration 1b tracer bullet. `POST /api/filings/chat` "List Microsoft's recent 10-K filings" returns rows from `filings-mcp` `listFilings`, with sec.gov links. It adds a new `filings-mcp` module (port 8082), the EDGAR client, a configured ticker→CIK map, and a Filings analyst in agent-app that mirrors the Market data agent.

**Blocked by:** none

**Status:** ready-for-agent

## Guiding rule

AGENTS.md §1 "Project intent": keep the EDGAR side thin. When in doubt, pick the simpler option. Duplicating code between MCP servers is acceptable. There is no shared `mcp-common` module.

## Decisions: filings-mcp

### Module

- New Gradle module `filings-mcp`, package `info.saagar.stockagent.filings`, application class `FilingsMcpApplication` (with `scanBasePackages` for `info.saagar.stockagent.common`, as in `MarketDataMcpApplication`).
- `build.gradle.kts` uses the same dependencies as `market-data-mcp`. Add the module to `settings.gradle.kts`.
- `application.yml`: `spring.application.name: filings-mcp`, `server.port: 8082`, `server.address: 127.0.0.1`, imports `classpath:common.yml`, and exposes only `/actuator/health`.
- Packages mirror market-data-mcp: `controller`, `tool`, `provider`, `config`.

### Transport

- **Copy** `McpController` from market-data-mcp into `info.saagar.stockagent.filings.controller`, unchanged apart from the package. Don't extract it.
- The `McpServer` bean is built the same way as in `MarketDataConfig`, over `FilingsTools`.

### Ticker → CIK map

- EDGAR APIs are keyed by 10-digit zero-padded CIK, not ticker. Nothing is downloaded at runtime.
- `filings.tickers` in `application.yml` is a map of about 20 popular US companies (e.g., AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, JPM, V, MA, JNJ, UNH, WMT, COST, HD, PG, KO, PEP, XOM, NFLX). Look up the exact CIKs once from `https://www.sec.gov/files/company_tickers.json` while implementing, and don't type them from memory.
- Bind it with a `@ConfigurationProperties` record (`FilingsProperties`: `baseUrl`, `connectTimeout`, `readTimeout`, `tickers`).
- Ticker lookup is case-insensitive. An unknown ticker throws: "Filings are available only for: AAPL, MSFT, … ." (list the configured tickers).

### EDGAR client (AGENTS.md §7)

- `provider` package: an `EdgarClient` interface and a `RestClientEdgarClient` implementation, built on `RestClient` + `JdkClientHttpRequestFactory` like the Finnhub client. Base URL `https://data.sec.gov`, connect 2 s, read 5 s.
- `User-Agent` comes from the `SEC_USER_AGENT` env var (e.g., `stock-research-agent you@example.com`). It has no default, and startup fails when it's missing or blank (mirror the `FINNHUB_API_KEY` check). It's sent on every request and never logged.
- Endpoint for this ticket: `GET /submissions/CIK{cik10}.json`. Read `filings.recent` (parallel arrays: `form`, `filingDate`, `reportDate`, `accessionNumber`, `primaryDocument`).
- Errors map to an unchecked `FilingsException` with fixed messages: 403 → "SEC EDGAR rejected the request (check SEC_USER_AGENT)."; 429 → "SEC EDGAR rate limit reached; try again shortly."; 5xx → "SEC EDGAR is unavailable."; timeout/I/O → "SEC EDGAR timed out." Log each failure once, as `edgarCallFailed`, the way `finnhubCallFailed` works.

### Tool `listFilings(ticker, formType)`

- `formType` is **required**, one of `10-K`, `10-Q`, `8-K`. A missing or other value throws: "formType is required and must be one of: 10-K, 10-Q, 8-K."
- Returns the 10 most recent filings of that form. Each row is a `FilingSummary` record: `form`, `filingDate`, `reportPeriod` (from `reportDate`), `accessionNumber`, `url`.
- `url` = `https://www.sec.gov/Archives/edgar/data/{cik without leading zeros}/{accessionNumber without dashes}/{primaryDocument}`.
- The `@Tool` description says that only the configured tickers are supported and that `formType` is required.
- Errors are thrown, not returned (same reasoning as 1a ticket 02).

## Decisions: agent-app (mirror the Market data agent)

- `agent/FilingsAgent`: an `AiServices` interface whose `@SystemMessage` tells it to answer only from `filings-mcp` tool output and to cite form, period and accession number (with the link when present) for every fact.
- Config: `stock-agent.agents.filings` (model `claude-haiku-5-5`, same limits as `market-data`, `allowed-tools: [listFilings, getCompanyFacts]`), and `stock-agent.mcp-servers.filings.url: ${FILINGS_MCP_URL:http://localhost:8082/mcp}` with the same protocol version and timeout.
- `FilingsAgentConfig` mirrors `MarketDataAgentConfig`, including correlation headers through `McpCorrelationHeaders`.
- `POST /api/filings/chat` uses the same request and response records as `/api/chat` and calls `FilingsAgent`. Iteration 3 removes it when the Supervisor arrives.
- agent-app now fails to start unless both MCP servers are up. Update the yml comment.
- The Market data agent's allowlist doesn't change.

## Tests (minimal; synthetic data only, no live EDGAR or Anthropic)

- **filings-mcp:**
  - One MockMvc controller test: `tools/list` lists `listFilings`, plus one `tools/call` round trip with a fake `EdgarClient`.
  - `FilingsTools` unit tests against a fake `EdgarClient`: missing `formType` → error; invalid `formType` → error; unknown ticker → error listing the configured tickers; at most 10 rows; `url` built correctly.
  - One or two `MockRestServiceServer` tests for `RestClientEdgarClient`: the `User-Agent` header is sent, and the submissions JSON maps to rows.
  - Copies of `LogEventNamesTest` and `ActuatorEndpointsTest`.
- **agent-app:** one allowlist test showing the Filings analyst sees only filings tools and the Market data agent sees none of them, using the existing fake MCP pattern.

## Acceptance criteria

- [ ] `./gradlew build` passes.
- [ ] With all three apps running (`SEC_USER_AGENT` set for filings-mcp), `curl -X POST http://127.0.0.1:8080/api/filings/chat -H 'Content-Type: application/json' -d '{"message":"List Microsoft'\''s recent 10-K filings"}'` returns rows with sec.gov links and `listFilings` in `toolsCalled` (manual check).
- [ ] An unconfigured ticker or a missing `formType` gets a model-readable error that the agent relays.
- [ ] `/api/chat` (Market data agent) can't see filings tools, and the Filings analyst can't see market-data tools.
- [ ] One request's logs share a `correlationId` across agent-app and filings-mcp (manual check).
- [ ] The `SEC_USER_AGENT` value appears in no log line.

## Comments
