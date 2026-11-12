# Stock Research Agent

Multi-agent stock research and portfolio assistant in Java, built with Spring Boot, LangChain4j, MCP tool servers, RAG over SEC filings, semantic caching and Claude.

> Research and monitoring tool only. Not investment advice; no real trading.

Requirements: [docs/requirements/stock-research-agent-spec.md](docs/requirements/stock-research-agent-spec.md)

## Prerequisites

- Java 25
- Docker (for later iterations: Postgres/pgvector)

## Build and run

```bash
./gradlew build                     # compile and test all modules
./gradlew :agent-app:bootRun        # start the agent app (127.0.0.1:8080)
./gradlew :market-data-mcp:bootRun  # start the market-data MCP server (127.0.0.1:8081)
```

## Project layout

```
agent-app/         agent app (info.saagar.stockagent)
market-data-mcp/   market-data MCP server (info.saagar.stockagent.marketdata)
common/            code and config (common.yml) shared by the apps (info.saagar.stockagent.common)
buildSrc/          Gradle convention plugins
gradle/libs.versions.toml   dependency version catalog
```

## Run locally

Two apps run side by side. `agent-app` receives the chat request (`POST /api/chat`) and runs the Quote agent (Claude Haiku 5.5). To get a price, the agent calls the `getQuote` tool on `market-data-mcp` over MCP. `market-data-mcp` fetches the quote from Finnhub and returns it to the agent:

```
curl → agent-app :8080 (/api/chat, Quote agent) → MCP client → market-data-mcp :8081 (/mcp, getQuote) → Finnhub API
```

### 1. Set environment variables

| Variable | Used by | Required |
|---|---|---|
| `FINNHUB_API_KEY` | `market-data-mcp` | Yes; startup fails without it. Get a free key at finnhub.io |
| `ANTHROPIC_API_KEY` | `agent-app` | Yes |
| `MARKET_DATA_MCP_URL` | `agent-app` | No; defaults to `http://localhost:8081/mcp` |

The Finnhub key stays inside `market-data-mcp`; `agent-app` never sees it.

### 2. Start the servers, MCP server first

`agent-app` fails to start when `market-data-mcp` is unreachable.

```bash
# terminal 1
export FINNHUB_API_KEY=...
./gradlew :market-data-mcp:bootRun

# terminal 2 (after market-data-mcp has started)
export ANTHROPIC_API_KEY=...
./gradlew :agent-app:bootRun
```

### 3. Sample prompts

```bash
# Company name resolved to a ticker
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"What is the stock price of Apple?"}'
# {"answer":"...","toolsCalled":[{"name":"getQuote","arguments":{"symbol":"AAPL"}}]}

# Price move for the day
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"How is NVDA trading today? Include the percent change and day range."}'

# Unsupported symbol: the tool returns a model-readable error that the agent explains
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Price of 7203.T on the Tokyo exchange?"}'
```

Prices are live Finnhub quotes, so answers vary. Only US-listed equities are supported.

### Notes

- Model, max tokens, timeout, max steps and the MCP tool allowlist are per agent in `agent-app/src/main/resources/application.yml` (`stock-agent.agents.quote`).
- Compatibility confirmed: Spring Boot 4.1.2 with LangChain4j 1.23.0 (`langchain4j`, `langchain4j-anthropic`), wired manually without a starter. Beta modules `langchain4j-agentic` and `langchain4j-mcp` are pinned at 1.23.0-beta33 in `gradle/libs.versions.toml`.
- Tests use a scripted fake `ChatModel` and a fake Finnhub client; no live model or Finnhub call.
- Deviation from spec §14: Docker Compose is not set up yet.

See section 14 of the spec for the full build plan.
