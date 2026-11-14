# 0002. Three Spring Boot MCP servers, one per domain

## Status

Accepted

## Context

Tools are exposed to agents only through MCP servers (spec section 7.1). The tools fall into three groups with different upstream sources, API keys and access rules. The project is implemented in Java, and every server must validate OAuth tokens and expose health and metrics.

## Decision

Three MCP servers are built, one per domain, each as a separate Spring Boot application using Streamable HTTP (stateless):

- `market-data-mcp`: quotes, profile, news, earnings, financials (Finnhub only, see ADR 0007; built instead of using Finnhub's hosted MCP server, see ADR 0009).
- `filings-mcp`: SEC EDGAR filings, XBRL facts, filings search.
- `portfolio-mcp`: holdings, watchlist, sector exposure, paper-trade proposals (application Postgres).

Servers are not split further (for example, one per tool).

## Consequences

- Upstream API keys are isolated per server (MCP-1).
- OAuth scopes and Bifrost tool allowlists map directly to servers (`marketdata.read`, `filings.read`, `portfolio.read`, `portfolio.write`).
- `portfolio-mcp` alone carries the user-scoped ownership checks (SEC-6).
- Spring Security, Actuator and Micrometer give identical token validation, health and metrics in every server. A shared module for configuration, security and logging is recommended to avoid duplication.
- Three JVMs are heavy on a small deployment.
- A fourth server, `macro-mcp` (FRED macro series), was planned and dropped: it would repeat the patterns of the other servers without teaching anything new (AGENTS.md §1, Project intent).
- Servers are added one at a time, starting with `market-data-mcp` (Iteration 1).

## Alternatives considered

- **One combined MCP server:** fewer processes, but upstream keys and access rules would be mixed in one service, and per-domain allowlists would be harder to express.
- **One server per tool:** maximum isolation, but operational cost grows with no matching benefit.
- **Plain MCP Java SDK without Spring Boot:** lighter, but token validation, health and metrics would have to be rebuilt by hand.
