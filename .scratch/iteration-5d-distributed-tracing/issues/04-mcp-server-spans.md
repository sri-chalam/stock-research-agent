# 04: MCP-server spans: tools and the filings embedding

**What to build:** in a chat trace, each tool call has its own span on the MCP-server side, named after the tool, so the time spent inside `market-data-mcp`, `filings-mcp` or `portfolio-mcp` is visible apart from the network. A filings search also shows its query embedding and its vector query.

**Blocked by:** 01

**Status:** ready-for-agent

## Decisions

- `@WithSpan` goes on each `@Tool` method in the three MCP servers. Names and durations only.
- In `searchFilings`, the query embedding is an inline OTel API span named `embedQuery`. The pgvector query is the agent's automatic JDBC span.
- Hybrid search and rerank are not built yet. They get their own spans when they are.
- Startup ingestion in `filings-mcp` is not part of a request and gets no manual spans.

## Acceptance criteria

- [ ] A market-data question's trace shows a `getQuote` (or other tool) span in `market-data-mcp`, with the Finnhub call under it.
- [ ] A portfolio question's trace shows `getHoldings` or `getWatchlist` in `portfolio-mcp`, with its Postgres query under it.
- [ ] A filings question's trace shows `searchFilings` in `filings-mcp`, with `embedQuery` and the pgvector query as its children.
- [ ] `./gradlew build` passes, and the tests still run with no agent attached.
