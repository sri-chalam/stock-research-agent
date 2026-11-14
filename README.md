# Stock Research Agent

Multi-agent stock research and portfolio assistant in Java, built with Spring Boot, LangChain4j, MCP tool servers, RAG over SEC filings, semantic caching and Claude.

> Research and monitoring tool only. Not investment advice; no real trading.

Requirements: [docs/requirements/stock-research-agent-spec.md](docs/requirements/stock-research-agent-spec.md)

## Purpose

This is a learning project. It explores how to build a production-style AI agent application in Java with [LangChain4j](https://docs.langchain4j.dev/). Spring AI is not used.

> **Work in progress.** Most of the goals below are not implemented yet. The work follows the requirements one iteration at a time (see the build plan in [spec §14](docs/requirements/stock-research-agent-spec.md#14-iterative-build-plan)). The current iteration is 1b. Some goals may change or be dropped.

Status: ✅ done · 🚧 in progress · ⏳ planned · ❓ stretch goal, may not be built

| # | Area | What to explore | Status |
|---|---|---|---|
| 1 | Agents | One agent with `AiServices`, then multi-agent orchestration (Supervisor, specialist agents, Critic) with `langchain4j-agentic` | 🚧 Market data agent and Filings analyst, each standalone |
| 2 | Tool calling | The model picks tools and their arguments | ✅ |
| 3 | MCP clients and servers | Own Java MCP servers (Streamable HTTP, stateless) for market data (Finnhub) and SEC filings (EDGAR). Agents call them through the LangChain4j MCP client, with a tool allowlist per agent | 🚧 `market-data-mcp`; `filings-mcp` (`listFilings`, `getCompanyFacts`) |
| 4 | Memory | Short-term conversation memory and long-term memory per user | ⏳ |
| 5 | RAG | SEC filings in pgvector: hybrid search (vector + keyword), reranking and section citations | ⏳ |
| 6 | Semantic caching | Cache answers by meaning, keyed on ticker, user and how fresh the data must be (real-time, daily, static) | ⏳ |
| 7 | Security | OAuth 2.1 with WorkOS AuthKit (PKCE). The agent app and every MCP server validate tokens, and users can access only their own data | ⏳ |
| 8 | MCP and LLM gateway | Bifrost in front of the MCP servers and the Anthropic API, with one virtual key per agent that sets its tool allowlist and budget | ⏳ |
| 9 | Guardrails | Prompt-injection checks, news and filing text treated as untrusted input, refusal of buy/sell advice | ⏳ |
| 10 | Human-in-the-loop | Paper trades run only after the user approves them | ⏳ |
| 11 | Structured output | Reports and briefs returned as typed Java records | ⏳ |
| 12 | Resilience | Retry per LLM step, circuit breaker and fallback model | ⏳ |
| 13 | Scheduled jobs | Morning brief run by a scheduled job with a machine-to-machine token and the Anthropic Batch API | ⏳ |
| 14 | Cost control | Prompt caching, token and cost per agent, Haiku vs Sonnet chosen per agent | ⏳ |
| 15 | Chat GUI | One `index.html` with streamed answers (SSE) and an agent/tool trace panel | ⏳ |
| 16 | Deployment | AWS ECS on Fargate Spot, defined with AWS CDK and deployed by GitHub Actions (OIDC). The stack is created and destroyed on demand | ⏳ |
| 17 | Observability | Structured JSON logs with correlation IDs across apps, OpenTelemetry traces and Grafana dashboards | 🚧 Structured logs only |
| 18 | Intent and freshness classifier | Route each question to a direct quote lookup, the cache or the multi-agent flow | ❓ |
| 19 | Evals | Golden question set, LLM-as-judge grading and CI that blocks merges below a score threshold | ❓ |

Later, the goal is to use software factories: automated pipelines in which agents make low-complexity changes to this repo on their own.

### How this was built

Built with Claude Opus 5.5 in Claude Code, using Matt Pocock's AI skills and the author's own skills.

## Prerequisites

- Java 25
- Docker with Compose (optional; runs all apps with `docker compose up`)

## Build and run

```bash
./gradlew build                     # compile and test all modules
./gradlew :agent-app:bootRun        # start the agent app (127.0.0.1:8080)
./gradlew :market-data-mcp:bootRun  # start the market-data MCP server (127.0.0.1:8081)
./gradlew :filings-mcp:bootRun      # start the filings MCP server (127.0.0.1:8082)
```

## Project layout

```
agent-app/         agent app (info.saagar.stockagent)
market-data-mcp/   market-data MCP server (info.saagar.stockagent.marketdata)
filings-mcp/       SEC filings MCP server (info.saagar.stockagent.filings)
common/            code and config (common.yml) shared by the apps (info.saagar.stockagent.common)
buildSrc/          Gradle convention plugins
gradle/libs.versions.toml   dependency version catalog
```

## Ports

All apps bind to `127.0.0.1` only. There is no auth before Iteration 5.

| App | Port | Endpoint | Status |
|---|---|---|---|
| `agent-app` | 8080 | `/api/chat`, `/api/filings/chat` | Built |
| `market-data-mcp` | 8081 | `/mcp` | Built |
| `filings-mcp` | 8082 | `/mcp` | Built (Iteration 1b) |
| `portfolio-mcp` | 8084 | `/mcp` | Reserved (Iteration 5) |

## Run locally

Three apps run side by side. `agent-app` receives the chat request (`POST /api/chat`) and runs the Market data agent (Claude Haiku 5.5). The agent answers with the tools on `market-data-mcp`, called over MCP: `getQuote`, `getCompanyProfile`, `getCompanyNews`, `getMarketNews`, `getEarningsCalendar` and `getBasicFinancials`. `market-data-mcp` fetches the data from Finnhub and returns trimmed results to the agent:

```
curl → agent-app :8080 (/api/chat, Market data agent) → MCP client → market-data-mcp :8081 (/mcp, market-data tools) → Finnhub API
```

The Filings analyst (`POST /api/filings/chat`) works the same way with `filings-mcp`, which reads SEC EDGAR. It has three tools: `listFilings` returns the 10 most recent 10-K, 10-Q or 8-K filings, `getCompanyFacts` returns six headline XBRL metrics for the last 3 fiscal years, and `searchFilings` returns the 5 filing chunks most similar to a query (RAG). On startup, `filings-mcp` ingests the latest 10-K Item 1A (Risk Factors) of the tickers in `filings.rag.ingest-tickers` into Postgres + pgvector, in the background, with an in-process embedding model. `searchFilings` reads only that store and never calls EDGAR. All tools support only 10 configured large US companies (`filings.tickers` in `filings-mcp/src/main/resources/application.yml`). Iteration 3 replaces this endpoint with the Supervisor:

```
curl → agent-app :8080 (/api/filings/chat, Filings analyst) → MCP client → filings-mcp :8082 (/mcp, filings tools) → SEC EDGAR
                                                                                                  └─ searchFilings → Postgres + pgvector (filings schema)
```

### 1. Set environment variables

| Variable | Used by | Required |
|---|---|---|
| `FINNHUB_API_KEY` | `market-data-mcp` | Yes; startup fails without it. Get a free key at finnhub.io |
| `SEC_USER_AGENT` | `filings-mcp` | Yes; startup fails without it. SEC EDGAR requires contact details, e.g. `stock-research-agent you@example.com` |
| `ANTHROPIC_API_KEY` | `agent-app` | Yes |
| `MARKET_DATA_MCP_URL` | `agent-app` | No; defaults to `http://localhost:8081/mcp` |
| `FILINGS_MCP_URL` | `agent-app` | No; defaults to `http://localhost:8082/mcp` |
| `FILINGS_DB_PASSWORD` | `filings-mcp`, `postgres` | With Gradle, yes: the password of the `filings_mcp` database user. Compose defaults it to `filings-local-dev` |
| `POSTGRES_PASSWORD` | `postgres` | No; Compose defaults it to `postgres-local-dev` |

The Finnhub key stays inside `market-data-mcp`; `agent-app` never sees it.

### 2. Start the servers

Use either Gradle or Docker Compose. Both bind ports 8080, 8081 and 8082 on `127.0.0.1`, so run only one of them at a time.

#### Option A: Gradle, MCP servers first

`agent-app` fails to start when `market-data-mcp` or `filings-mcp` is unreachable.

```bash
# terminal 1
export FINNHUB_API_KEY=...
./gradlew :market-data-mcp:bootRun

# terminal 2: filings-mcp needs Postgres + pgvector; run only that container from Compose
docker compose up -d postgres
export SEC_USER_AGENT="stock-research-agent you@example.com"
export FILINGS_DB_PASSWORD=filings-local-dev
./gradlew :filings-mcp:bootRun

# terminal 3 (after both MCP servers have started)
export ANTHROPIC_API_KEY=...
./gradlew :agent-app:bootRun
```

#### Option B: Docker Compose

Compose reads the keys from a git-ignored `.env` file in the project root. Each container receives only its own key. Compose starts `filings-mcp` after Postgres is healthy, and `agent-app` after `market-data-mcp` and `filings-mcp` pass their health checks (`/actuator/health`). The database is disposable: `docker compose down` deletes it, and `filings-mcp` re-ingests on its next start.

```bash
cp .env.example .env            # then fill in FINNHUB_API_KEY, SEC_USER_AGENT and ANTHROPIC_API_KEY

docker compose up --build -d    # build the images and start all three apps in the background
docker compose ps               # all three services Up; both MCP servers show (healthy)
docker compose logs -f          # follow all apps' logs (Ctrl+C stops following, not the apps)

docker compose down             # stop and remove the containers and the network
```

Leave out `-d` to run in the foreground; Ctrl+C then stops all three apps. Inside Compose, `agent-app` reaches the MCP servers at `http://market-data-mcp:8081/mcp` and `http://filings-mcp:8082/mcp`. The sample prompts and `curl` calls below work unchanged.

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

**`listFilings`** (Filings analyst, `/api/filings/chat`): the 10 most recent filings of one form type, with sec.gov links.

```bash
curl -X POST http://127.0.0.1:8080/api/filings/chat -H 'Content-Type: application/json' \
  -d '{"message":"List Microsoft'\''s recent 10-K filings"}'
# {"answer":"...","toolsCalled":[{"name":"listFilings","arguments":{"ticker":"MSFT","formType":"10-K"}}]}

# Unconfigured ticker: the error lists the supported tickers
curl -X POST http://127.0.0.1:8080/api/filings/chat -H 'Content-Type: application/json' \
  -d '{"message":"List IBM'\''s recent 10-Q filings"}'
```

**`getCompanyFacts`** (Filings analyst): revenue, net income, gross profit, diluted EPS, total assets and cash for the last 3 fiscal years, from XBRL. Each value cites its form, period and accession number.

```bash
curl -X POST http://127.0.0.1:8080/api/filings/chat -H 'Content-Type: application/json' \
  -d '{"message":"What was Apple'\''s revenue for the last 3 fiscal years?"}'
# {"answer":"...","toolsCalled":[{"name":"getCompanyFacts","arguments":{"ticker":"AAPL"}}]}
```

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

- Model, max tokens, timeout, max steps and the MCP tool allowlist are per agent in `agent-app/src/main/resources/application.yml` (`stock-agent.agents.market-data`, `stock-agent.agents.filings`).
- Compatibility confirmed: Spring Boot 4.1.2 with LangChain4j 1.23.0 (`langchain4j`, `langchain4j-anthropic`), wired manually without a starter. Beta modules `langchain4j-agentic` and `langchain4j-mcp` are pinned at 1.23.0-beta33 in `gradle/libs.versions.toml`.
- Tests use a scripted fake `ChatModel` and fake Finnhub and EDGAR clients; no live model, Finnhub or EDGAR call.

See section 14 of the spec for the full build plan.
