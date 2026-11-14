# 03: Docker Compose runs filings-mcp

**What to build:** `docker compose up` runs agent-app, market-data-mcp and filings-mcp together.

**Blocked by:** 01

**Status:** done

## Decisions

- `filings-mcp/Dockerfile` is a copy of `market-data-mcp/Dockerfile` with the module name and port changed (Corretto 25 Alpine).
- New `filings-mcp` service: port `127.0.0.1:8082:8082`, `SEC_USER_AGENT: ${SEC_USER_AGENT:?set SEC_USER_AGENT in .env}`, and an `/actuator/health` healthcheck like market-data-mcp's. `server.address` is overridden as it is for market-data-mcp.
- agent-app: `STOCK_AGENT_MCP_SERVERS_FILINGS_URL: http://filings-mcp:8082/mcp`, plus `depends_on: filings-mcp: condition: service_healthy`.
- `.env.example` (if present) gets `SEC_USER_AGENT=`.

## Acceptance criteria

- [x] `docker compose up --build` starts all three apps, and agent-app starts only after both MCP servers are healthy.
- [x] The filings question from ticket 01 works through Compose (manual check).

## Comments

- 2026-11-13: All Decisions were already implemented in a48feb1 (Dockerfile, compose service, agent-app URL + depends_on, `.env.example`). Verified: `docker compose up --build --wait` brought agent-app up only after market-data-mcp and filings-mcp were healthy. The ticket 01 question through Compose returned six MSFT 10-K rows with sec.gov links, and `toolsCalled` was `listFilings(MSFT, 10-K)`.
