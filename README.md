# Stock Research Agent

Multi-agent stock research and portfolio assistant in Java, built with Spring Boot, LangChain4j, MCP tool servers, RAG over SEC filings, semantic caching and Claude.

> Research and monitoring tool only. Not investment advice; no real trading.

Requirements: [docs/requirements/stock-research-agent-spec.md](docs/requirements/stock-research-agent-spec.md)

## Prerequisites

- Java 25
- Docker (for later iterations: Postgres/pgvector)

## Build and run

```bash
./gradlew build      # compile and test
./gradlew bootRun    # start the app
```

## Project layout

```
src/main/java        application code (info.saagar.stockagent)
src/main/resources   configuration, static GUI
src/test/java        tests
gradle/libs.versions.toml   dependency version catalog
```

## Iteration 0: quote agent skeleton

`POST /api/chat` runs a single Quote agent (Claude Haiku 5.5) that calls a local `getQuote` tool backed by fixed fake quotes (AAPL, MSFT, NVDA, TSLA, AMZN, GOOGL, NFLX).

```bash
export ANTHROPIC_API_KEY=...
./gradlew bootRun
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"message":"price of Apple"}'
# {"answer":"...189.42...","toolsCalled":[{"name":"getQuote","arguments":{"symbol":"AAPL"}}]}
```

- Model, max tokens, temperature, timeout and max steps are per agent in `application.yml` (`stock-agent.agents.quote`).
- Compatibility confirmed: Spring Boot 4.1.2 with LangChain4j 1.23.0 (`langchain4j`, `langchain4j-anthropic`), wired manually without a starter. Beta modules `langchain4j-agentic` and `langchain4j-mcp` are pinned at 1.23.0-beta33 in `gradle/libs.versions.toml`.
- Tests use a scripted fake `ChatModel`; no live model call.
- Deviation from spec §14: Docker Compose is deferred to Iteration 1.

See section 14 of the spec for the full build plan.
