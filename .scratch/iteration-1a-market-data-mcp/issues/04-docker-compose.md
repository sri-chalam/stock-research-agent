# 04: Docker Compose for agent-app and market-data-mcp

**What to build:** `docker compose up` runs both apps. "price of Apple" works through `localhost:8080`, and `localhost:8081/mcp` can be curled directly. Both are reachable from this machine only.

**Blocked by:** 02

**Status:** done

## Decisions

- One Dockerfile per app (`agent-app/Dockerfile`, `market-data-mcp/Dockerfile`):
  - multi-stage: build with Gradle using `-x test` (allowed for Docker layer caching only, CLAUDE.md §9);
  - run on `amazoncorretto:25-alpine`.
- A root `docker-compose.yml` with the two services:
  - Ports are bound to loopback only: `127.0.0.1:8080:8080` and `127.0.0.1:8081:8081`. Neither app has auth before Iteration 5, and market-data-mcp holds the Finnhub key.
  - Inside containers, the apps bind `0.0.0.0` (override `SERVER_ADDRESS`), because host exposure is limited by the loopback port binding.
  - agent-app gets `STOCK_AGENT_MCP_SERVERS_MARKET_DATA_URL=http://market-data-mcp:8081/mcp`.
  - market-data-mcp adds Spring Boot Actuator and exposes `/actuator/health` only. Its Compose healthcheck uses `wget` (busybox), and agent-app has `depends_on: market-data-mcp: condition: service_healthy`, because agent-app fails fast without it.
- Secrets (ADR 0008): a git-ignored `.env` plus a committed `.env.example`. Each service gets only its own key: `ANTHROPIC_API_KEY` goes to agent-app and `FINNHUB_API_KEY` to market-data-mcp (MCP-1).
- No Postgres yet; nothing uses it in 1a.

## Acceptance criteria

- [ ] `docker compose up --build` starts both services. agent-app starts only after market-data-mcp is healthy.
- [ ] `curl -X POST localhost:8080/api/chat ... "price of Apple"` returns a live price (manual check).
- [ ] `curl localhost:8081/mcp` with a `tools/call` body works from the host (manual check).
- [ ] Neither port is reachable from another machine on the LAN (`docker compose port` shows `127.0.0.1`).
- [ ] `docker compose exec agent-app env` shows no `FINNHUB_API_KEY`, and market-data-mcp shows no `ANTHROPIC_API_KEY`.
- [ ] `.env` is git-ignored and `.env.example` is committed with empty values.
- [ ] One chat request's logs share a `correlationId` across both containers (manual check).

## Comments
