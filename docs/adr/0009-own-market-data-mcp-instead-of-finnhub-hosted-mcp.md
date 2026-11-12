# 0009. Own market-data-mcp instead of Finnhub's hosted MCP server

## Status

Accepted

## Context

Finnhub runs a hosted MCP server at `https://mcp.finnhub.io/mcp`. Using it would remove the need to build `market-data-mcp`. Its connection guide ([How to connect](https://finnhub.io/static/finnhub-how-to-connect-mcp-server.html), checked 2026-11-12) shows these limits:

- **Authentication:** the guide documents only interactive, per-user OAuth, with client IDs `claude` and `chatgpt` and an empty client secret. It documents no client-credentials flow, no API-key authentication, and no setup for other MCP clients. agent-app is neither Claude nor ChatGPT, and the morning-brief job needs a machine-to-machine token (SEC-7), so neither can authenticate.
- **Tools:** the server exposes about 70 tools. That is far more than the Market data agent needs, and the tool definitions would fill the prompt (NFR-6, NFR-8).
- **News:** company news is available only through a `newsroom` tool, and there is no general market news tool, so `getMarketNews` (spec section 7.1) could not be served.

## Decision

Build our own `market-data-mcp` (ADR 0002) against the Finnhub REST API, with the shared Finnhub API key (ADR 0008). It exposes only the six tools in spec section 7.1 and trims their results (MCP-2).

## Consequences

- agent-app and the morning-brief job call one server we control. From Iteration 5 that server validates our own OAuth tokens (MCP-6).
- We write and maintain the Finnhub client, the tool descriptions and the result trimming.
- The hosted server can still be used in Claude Desktop to explore Finnhub data and compare it with our tools (MCP-7). It is not part of the application.
- Revisit if Finnhub documents a client-credentials flow and a market news tool.

## Alternatives considered

- **Finnhub's hosted MCP server:** no server to build or run, but agent-app and the M2M job cannot authenticate, and the tool set is too large and lacks market news.
- **A community open-source Finnhub MCP server:** usable for exploration (MCP-7), but its tools, trimming, logging and later OAuth support would not be under our control.
