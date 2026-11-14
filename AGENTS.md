# Stock Research Agent - AGENTS.md

## Sections

1. Project Overview
2. Tech Stack
3. Project Structure
4. Development / Build Commands
5. Code Style
6. Logging Conventions
7. Architecture
8. Testing Guidelines
9. What Not To Do
10. Performance Requirements
11. Concurrency
12. AI Assistant Guidelines
13. Git Conventions

## 1. Project Overview

Equity Research & Portfolio Watch: a multi-agent AI assistant for individual investors, built in Java with Spring Boot, LangChain4j and MCP tool servers. It answers research questions (market data, news, SEC filings via RAG), analyzes paper-portfolio risk, produces a scheduled morning brief, and proposes paper trades that require explicit user approval. It is a research and monitoring tool only: no real trading, no personalized investment advice.

**Project intent:** This project exists to explore agentic-application concepts (agents, MCP, gateways, RAG, evals, auth). Keep data-provider integrations (Finnhub, SEC EDGAR) as thin as possible: fixed, small tool outputs over flexible parameters, and no provider edge cases unless an eval demands it. When a design choice adds provider complexity without teaching an agentic concept, pick the simpler option.

Full requirements: @docs/requirements/stock-research-agent-spec.md


## 2. Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.2 |
| JSON | Jackson (do not use Gson, Moshi, or other JSON libraries) |
| Build | Gradle 9 (Kotlin DSL), Spotless (google-java-format), dependency versions in `gradle/libs.versions.toml` |
| AI framework | LangChain4j (core + Anthropic provider managed by the BOM; `langchain4j-agentic` and `langchain4j-mcp` are beta, pin exact versions) |
| LLMs | Claude Haiku 5.5 (`claude-haiku-5-5`) by default; Claude Sonnet 5.5 (`claude-sonnet-5-5`) only where evals justify it. Model IDs are configured per agent in `application.yml`, never hard-coded |
| MCP | Model Context Protocol: tools are exposed by our own Java MCP servers (Streamable HTTP, stateless) and consumed by agents only through the LangChain4j MCP client. Agents never call data providers directly; upstream API keys stay inside the MCP servers |
| Database | PostgreSQL + pgvector (portfolios, pending actions, memory, filings embeddings, semantic cache, session state) |
| Logging | SLF4J 2 + Logback |
| Testing | JUnit Jupiter + AssertJ; versions managed by the Spring Boot BOM where available |
| Container | Amazon Corretto 25 Alpine (`filings-mcp`: Corretto 25 on Amazon Linux 2023, because ONNX Runtime needs glibc) |

## 3. Project Structure

Multi-module Gradle build. Shared build logic lives in `buildSrc` convention plugins (`stockagent.java-conventions`, `stockagent.spring-app`), which read `gradle/libs.versions.toml`.

| Module | Role |
|---|---|
| `agent-app` | The agent app (REST API, agents, MCP clients). Port 8080 |
| `market-data-mcp` | Market-data MCP server (Finnhub). Port 8081 |
| `filings-mcp` | SEC filings MCP server (EDGAR). Port 8082. Same package layout as `market-data-mcp`, under `info.saagar.stockagent.filings` |
| `common` | Code shared by more than one module. A plain library, not an app |

Each app's `application.yml` holds only its own settings and imports `classpath:common.yml` (from `common`) for shared settings: structured logging and virtual threads. Do not put one app's settings in another app's file or in `common.yml`.

### `agent-app`

Package by layer under `info.saagar.stockagent`:

| Package | Contents |
|---|---|
| `controller` | REST controllers and their request/response records (e.g. `ChatController`) |
| `agent` | `AiServices` agent interfaces with `@SystemMessage` (e.g. `MarketDataAgent`) |
| `tool` | Tool-call helpers (`ToolArguments`). agent-app has no `@Tool` classes: tools come from MCP servers |
| `config` | `@ConfigurationProperties` records and `@Configuration` bean wiring (e.g. `AgentProperties`, `McpServerProperties`, `MarketDataAgentConfig`, `McpClientConfig`) |
| `logging` | Agent-app-only logging support (`ToolExecutionLogger`, `McpCorrelationHeaders`) |

### `market-data-mcp`

Under `info.saagar.stockagent.marketdata` (application class `MarketDataMcpApplication`, which also scans `info.saagar.stockagent.common`).

| Package | Contents |
|---|---|
| `controller` | `McpController`: the `POST /mcp` Streamable HTTP transport around the LangChain4j `McpServer` |
| `tool` | LangChain4j `@Tool` classes served over MCP and their helpers (`MarketDataTools`, `TickerSymbols`) |
| `provider` | Finnhub client interface, implementation and result records (`FinnhubClient`, `RestClientFinnhubClient`, `Quote`, `MarketDataException`) |
| `config` | `@ConfigurationProperties` records and bean wiring (`FinnhubProperties`, `MarketDataConfig`) |

### `filings-mcp`

Under `info.saagar.stockagent.filings` (application class `FilingsMcpApplication`, which also scans `info.saagar.stockagent.common`).

| Package | Contents |
|---|---|
| `controller` | `McpController`: a copy of the `market-data-mcp` controller (ADR 0010) |
| `tool` | LangChain4j `@Tool` classes served over MCP and their result records (`FilingsTools`, `FilingSummary`, `CompanyFacts`, `MetricValue`, `HeadlineMetric`, `FilingChunk`) |
| `provider` | SEC EDGAR client interface, implementation and result records (`EdgarClient`, `RestClientEdgarClient`, `EdgarFiling`, `XbrlFact`, `FilingsException`) |
| `config` | `@ConfigurationProperties` records and bean wiring (`FilingsProperties` with the ticker→CIK map, `FilingsConfig`; `RagProperties`, `RagConfig` for the embedding model and the pgvector or in-memory chunk store) |
| `rag` | Filings RAG ingestion: startup ingestion on a virtual thread, section extraction and chunk metadata keys (`FilingIngestion`, `FilingSections`, `FilingChunkMetadata`) |

### `common`

Under `info.saagar.stockagent.common`, organised by package:

| Package | Contents |
|---|---|
| `common.logging` | Request-context and structured-logging support (`LoggingContextFilter`; `StockAgentLogFormatter` when written) |
| `common.web` | Web-layer support shared by the apps (`GlobalExceptionHandler`) |

`LogEventNamesTest` runs in each app and scans that app's `src/main/java` plus `common/src/main/java`, so `eventName` values are unique per app.


## 4. Development / Build Commands
```bash
./gradlew build        # preferred over `./gradlew test` alone - runs Spotless formatting + tests + jar
./gradlew test         # run tests only
./gradlew clean build  # full clean build
./gradlew :agent-app:bootRun        # start the agent app (127.0.0.1:8080)
./gradlew :market-data-mcp:bootRun  # start the market-data MCP server (127.0.0.1:8081)
./gradlew :filings-mcp:bootRun      # start the filings MCP server (127.0.0.1:8082)
```

## 5. Code Style

- UTF-8 encoding enforced in compileJava.
- Utility / entry-point classes are final with a private constructor.
- All new or modified public/protected methods must include Java 25 markdown Javadoc (/// syntax).
- Method parameters and local variables must use meaningful, descriptive names (e.g., startDateStr, endDateStr) - never single-letter or generic names like a, b, s.
- Variable names must reflect business purpose, not implementation mechanics. Prefer attributesByInsightMap over grouped, pendingDispatchQueue over queue, insightAttributeMap over map.

## 6. Logging Conventions

- Use SLF4J (LoggerFactory.getLogger(ClassName.class)) - never System.out.
- Only structured logging is allowed. Plain string messages are not permitted.
- Do not call logger.info("some message") or any unstructured log call - always include key/value context fields.
- Every log message must have the fields eventName.
- No `methodName` and no `timestamp` fields (see `docs/logging/java-logging-guidelines.md` for why).
- For detailed logging instructions, see the file @docs/logging/logging-guidelines.md
- For Java-specific logging instructions, see the file @docs/logging/java-logging-guidelines.md

## 7. Architecture

For any new class that calls an external service (AWS SDK, HTTP clients, third-party SDKs):
- The call must go behind an interface.
- The new class containing business logic must depend on the interface, not the SDK directly.
- Do not refactor existing classes to follow this rule unless explicitly asked.
- Library selection must be justified against the service's core goals. Any new library introduced must demonstrably support high throughput and low latency.

## 8. Testing Guidelines

<!-- AssertJ also produces far more useful failure messages out of the box, and handles collections, optionals, and exceptions more fluently than Jupiter.
Jupiter assertions are fine for simple cases, but AssertJ is the de facto standard in modern Java projects. -->
- Use AssertJ for assertions (`assertThat(...).isEqualTo(...)`, `assertThatThrownBy(...)`, etc.).
- Tests must pass with `./gradlew test`
- Use the junit-best-practices skill (auto-triggered on test files)

For new classes:
- Unit tests wire real collaborators; use a fake (in-memory) implementation at the interface boundary.
- Do not use `@Mock` on classes that contain business logic.

Do not modify existing tests to follow this rule unless explicitly asked.

## 9. What Not To Do
- **No `System.out` / `System.err`** - use SLF4J logger.
- **No hardcoded credentials** - use `gradle.properties` (git-ignored) or env vars.
- **Do not run `./gradlew test` as the final verification step** - always use `./gradlew build` so Spotless formatting is applied before committing.
- **Do not skip tests** in local builds (`-x test` is reserved for Docker layer caching only).
- **Do not add JVM args to `tasks.withType<Test>`** to work around local or sandbox environment restrictions (e.g. `-Djava.io.tmpdir`, `-XX:+EnableDynamicAgentLoading`, `-Djdk.attach.allowAttachSelf`).
- **No reactive programming** - do not use `Flux`, `Mono`, Spring WebFlux, or any Project Reactor type.

## 10. Performance Requirements
The following are **critical** performance requirements for this service. Both goals carry equal weight. Only when a specific code block forces a trade-off between the two should low latency be prioritized.

- **High throughput** - the service must sustain a high volume of message dispatches continuously.
- **Low latency** - each message dispatch must complete with minimal delay.

All implementation, bug fixes, and updates must treat both as first-class constraints.

- Minimise object allocations in hot paths to reduce GC pressure.
- Avoid unnecessary synchronisation; prefer fine-grained locking or lock-free structures where contention is a concern.
- Do not introduce blocking calls on threads that handle message dispatch.

## 11. Concurrency

- When using `CompletableFuture`, always pass a custom thread pool - never rely on the common fork-join pool.
- The custom thread pool must use virtual threads (e.g., `Executors.newVirtualThreadPerTaskExecutor()`).


## 12. AI Assistant Guidelines
*Sourced from Andrej Karpathy's suggestions.*

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 12.1 Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 12.2 Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 12.3 Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

### 12.4 Goal-Driven Execution

- Before starting, state what "done" looks like in one verifiable sentence.
- If success criteria are missing or vague, ask - don't invent them.
- For any feature or bug fix, if the Jira issue has no acceptance criteria, ask the developer before coding.

## 13. Git Conventions

- When a git worktree is needed, place it at `../worktree/<feature-name>` relative to the project root. No `.gitignore` entry is required.


## Agent skills

### Issue tracker

Issues are local markdown files under `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.
