# 03: The remaining five market-data tools

**What to build:** Add `getCompanyProfile`, `getCompanyNews`, `getMarketNews`, `getEarningsCalendar` and `getBasicFinancials` to `market-data-mcp`. Each answers `tools/call` with trimmed output (MCP-2) and readable errors (MCP-5). The Quote agent's allowlist stays `[getQuote]`, so these are checked by direct `tools/call`, not through chat. The Market data agent arrives in Iteration 3.

**Blocked by:** 02

**Status:** ready-for-agent

## Decisions

- Same pattern as `getQuote`:
  - a new method on `FinnhubClient`, implemented in `RestClientFinnhubClient`;
  - a trimmed result record, returned from a `@Tool` method in `MarketDataTools` and serialized by LangChain4j;
  - errors thrown as `MarketDataException` and logged once in the client.
- Trimming rules (MCP-2):
  - `getCompanyProfile(symbol)` returns a compact profile: name, exchange, industry, country, market cap, shares outstanding, IPO date and web URL.
  - `getCompanyNews(symbol, from, to)` returns the 10 most recent articles. Each summary is cut to about 300 characters. The range is at most 30 days, and defaults to the last 7 days when `from`/`to` are blank.
  - `getMarketNews(category)` accepts only `general` and `merger` and returns the 10 most recent articles, trimmed the same way.
  - `getEarningsCalendar(from, to, symbol?)`: without a symbol, the range is capped at 7 days and the result at 25 rows.
  - `getBasicFinancials(symbol)` returns a fixed set of about 12 metrics (e.g. 52-week high/low, P/E, P/B, EPS, dividend yield, beta, gross/operating/net margin, ROE, debt/equity). No time series.
- Validation in the tools (FR-35): ticker format, ISO dates, `from ≤ to`, range limits and the category allowlist. A violation throws a readable message.
- Tool descriptions in `@Tool` state the limits, so the model knows them.

## Tests (synthetic data only)

- MockMvc `tools/list` lists all six tools with their parameter schemas.
- MockMvc `tools/call` per tool with a `FakeFinnhubClient`, covering success and one validation error each.
- `MockRestServiceServer` tests per new client method, checking field trimming, the summary cut, the 10/25 row caps and the ignored extra fields.

## Acceptance criteria

- [ ] `./gradlew build` passes.
- [ ] A curl `tools/call` against `localhost:8081/mcp` returns trimmed output for each of the five tools with a real key (manual check).
- [ ] No tool returns raw Finnhub JSON or fields outside the lists above.
- [ ] Out-of-range dates, an unsupported category and a bad ticker each return `isError: true` with a readable message.

## Comments
