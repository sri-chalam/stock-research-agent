# 0007. Finnhub as the only market-data provider

## Status

Accepted

## Context

The spec added Alpha Vantage (or Tiingo) for daily price history, because Finnhub's free tier has no candles, and as a quote fallback (MCP-4, FR-40). That means a second API key, a second client, a 25 requests/day limit and fallback logic. None of it teaches anything about MCP, RAG, semantic caching or agents, and Iteration 8 already covers resilience patterns through LLM retries, the circuit breaker and the fallback model.

## Decision

Finnhub is the only market-data provider. There is no Alpha Vantage or Tiingo, no provider fallback, and no `getPriceHistory` tool. Fundamentals come from `getBasicFinancials` and from `filings-mcp` (XBRL).

## Consequences

- One upstream key and one client in `market-data-mcp`.
- The assistant cannot answer price-trend questions beyond what `getBasicFinancials` returns, such as the 52-week high and low.
- If price history is wanted later, add it as a new tool backed by a single provider. The existing tools do not change.
