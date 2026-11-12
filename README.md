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

Two apps run side by side. `agent-app` receives the chat request (`POST /api/chat`) and runs the Market data agent (Claude Haiku 5.5). The agent answers with the tools on `market-data-mcp`, called over MCP: `getQuote`, `getCompanyProfile`, `getCompanyNews`, `getMarketNews`, `getEarningsCalendar` and `getBasicFinancials`. `market-data-mcp` fetches the data from Finnhub and returns trimmed results to the agent:

```
curl → agent-app :8080 (/api/chat, Market data agent) → MCP client → market-data-mcp :8081 (/mcp, market-data tools) → Finnhub API
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

Each prompt is grouped under the tool the agent is expected to call. The model picks the tool and its arguments, so `toolsCalled` in the response shows what it actually did.

**`getQuote`**: latest price, change, day range, open and previous close.

```bash
# Company name resolved to a ticker
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"What is the stock price of Apple?"}'
# {"answer":"...","toolsCalled":[{"name":"getQuote","arguments":{"symbol":"AAPL"}}]}

# Price move for the day
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"How is NVDA trading today? Include the percent change and day range."}'
```

**`getCompanyProfile`**: name, exchange, industry, country, market cap, shares outstanding, IPO date and website.

```bash
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Get Atlassian Company profile info. Stock symbol TEAM"}'
# {"answer":"...","toolsCalled":[{"name":"getCompanyProfile","arguments":{"symbol":"TEAM"}}]}
```

**`getCompanyNews`**: the 10 most recent articles about one company. The range is at most 30 days; with no dates it is the last 7 days.

```bash
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Summarize the last week of news about MSFT."}'
# {"answer":"...","toolsCalled":[{"name":"getCompanyNews","arguments":{"symbol":"MSFT"}}]}
```

**`getMarketNews`**: the 10 most recent market-wide articles. Category is `general` or `merger`.

```bash
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"What are the latest merger and acquisition headlines?"}'
# {"answer":"...","toolsCalled":[{"name":"getMarketNews","arguments":{"category":"merger"}}]}
```

**`getEarningsCalendar`**: earnings dates with EPS and revenue estimates and actuals. Without a symbol, the range is at most 7 days and at most 25 rows are returned.

```bash
# One company
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"When does NVDA report earnings in the next 3 months, and what EPS is expected?"}'

# All companies, one week
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Which companies report earnings in the next 5 days?"}'
```

**`getBasicFinancials`**: 52-week high and low, P/E, price/book, EPS, dividend yield, beta, margins, ROE and debt/equity.

```bash
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"What are the P/E ratio, net margin and dividend yield for Coca-Cola?"}'
# {"answer":"...","toolsCalled":[{"name":"getBasicFinancials","arguments":{"symbol":"KO"}}]}
```

**Several tools in one question**

```bash
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Give me an overview of Atlassian (TEAM): what the company is, its current price and its P/E."}'
# toolsCalled includes getCompanyProfile, getQuote and getBasicFinancials
```

**Errors**: the tool returns a model-readable error that the agent explains.

```bash
# Unsupported (non-US) symbol
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Price of 7203.T on the Tokyo exchange?"}'

# Date range over the 7-day limit for all-company earnings
curl -X POST http://127.0.0.1:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"List every company reporting earnings in the next 30 days."}'
```

Data is live from Finnhub, so answers vary. Only US-listed equities are supported.

### 4. Call the MCP tools directly (optional)

To check a tool's output without the agent or the model, send JSON-RPC to `market-data-mcp`. The server is stateless, so no `initialize` call is needed. JSON-RPC ids must be numbers.

```bash
# List the six tools and their parameter schemas
curl -s -X POST http://127.0.0.1:8081/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# Call one tool
curl -s -X POST http://127.0.0.1:8081/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getCompanyProfile","arguments":{"symbol":"TEAM"}}}'

# A validation error comes back as a result with "isError": true
curl -s -X POST http://127.0.0.1:8081/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getMarketNews","arguments":{"category":"crypto"}}}'
```

### Notes

- Model, max tokens, timeout, max steps and the MCP tool allowlist are per agent in `agent-app/src/main/resources/application.yml` (`stock-agent.agents.market-data`).
- Compatibility confirmed: Spring Boot 4.1.2 with LangChain4j 1.23.0 (`langchain4j`, `langchain4j-anthropic`), wired manually without a starter. Beta modules `langchain4j-agentic` and `langchain4j-mcp` are pinned at 1.23.0-beta33 in `gradle/libs.versions.toml`.
- Tests use a scripted fake `ChatModel` and a fake Finnhub client; no live model or Finnhub call.
- Deviation from spec §14: Docker Compose is not set up yet.

See section 14 of the spec for the full build plan.
