# Stock Research Agent

Multi-agent stock research and portfolio assistant in Java, built with Spring Boot, LangChain4j, MCP tool servers, RAG over SEC filings, semantic caching and Claude.

> Research and monitoring tool only. Not investment advice; no real trading.

Requirements: [docs/requirements/stock-research-agent-spec.md](docs/requirements/stock-research-agent-spec.md)

## Prerequisites

- Java 25
- Docker (for later iterations: Postgres/pgvector, Redis)

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

## Status

Iteration 0 (skeleton) in progress. See section 14 of the spec for the build plan.
