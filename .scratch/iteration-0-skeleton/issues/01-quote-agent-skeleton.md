# 01: Iteration 0 skeleton: quote agent answers "price of Apple" via a local tool

**What to build:** A running Spring Boot app where `POST /api/chat` takes `{"message": "price of Apple"}` and returns `{"answer": "...", "toolsCalled": [{"name": "getQuote", "arguments": {...}}]}`. A single Quote agent (Claude Haiku 5.5, configured per agent in `application.yml`) calls a local `getQuote` tool backed by fixed fake Quote data. This confirms LangChain4j and Spring Boot 4.1.2 work together and pins the versions. It is the Iteration 0 tracer bullet from spec §14. Structured logging infrastructure is ticket 02.

**Blocked by:** None (can start immediately)

**Status:** done

## Decisions (from the Iteration 0 grilling)

- **Quote source:** a `QuoteProvider` interface with a hard-coded `FakeQuoteProvider`. It knows AAPL, MSFT, NVDA, TSLA, AMZN, GOOGL and NFLX, each with price, percent change and as-of time. An unknown symbol returns a model-readable message (e.g. "No quote available for symbol XYZ. Only US equities are supported.") and does not throw.
- **API:** synchronous. No run ID, no SSE, no GUI (spec UI-4). Test with curl. `toolsCalled` is built from the `Result<String>` tool executions of the `AiServices` call.
- **Wiring:** manual bean configuration with `langchain4j` and `langchain4j-anthropic`. No Spring Boot starter.
- **Config:** typed `@ConfigurationProperties` record with a map keyed by agent name. Each block holds `model`, `max-tokens`, `temperature`, `timeout` and `max-steps` (AG-3 cheap limits only; max cost and loop detection deferred). The API key comes from the `ANTHROPIC_API_KEY` env var, never from YAML. Model IDs are never hard-coded.
- **System prompt:** classpath resource `prompts/quote-agent-v1.txt`, loaded with `@SystemMessage(fromResource=...)`. The filename is the prompt ID (AG-1).
- **Packages** (under `info.saagar.stockagent`; table already in AGENTS.md): `controller`, `agent`, `tool`, `provider`, `config`.
- **Tests:** a scripted fake `ChatModel` (first reply requests `getQuote("AAPL")`, second returns text built from the tool result) wired into the real `AiServices` agent, real tool and real provider. No Mockito on business logic, no live-model test.
- **Out of scope:** Docker Compose and Dockerfile, Postgres, MCP, Finnhub, GUI, run ID/SSE, security, logging infrastructure (ticket 02).

## Acceptance criteria

- [ ] `./gradlew build` passes (Spotless, tests, jar).
- [ ] A test posts "price of Apple" to `/api/chat` with the scripted fake model; the response contains AAPL's fake price and `getQuote` in `toolsCalled`.
- [ ] A test covers an unknown symbol: the tool returns the readable message and the answer relays it.
- [ ] With a real `ANTHROPIC_API_KEY`, `curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"message":"price of Apple"}'` returns AAPL's fake price with `getQuote` in `toolsCalled` (manual check).
- [ ] Exact LangChain4j (including beta modules if added) and Spring Boot 4.1.2 versions are pinned in `gradle/libs.versions.toml`; Boot 4 compatibility is confirmed in the README.
- [ ] Model ID, max tokens, temperature, timeout and max steps are read from `application.yml` per agent.
- [ ] No `System.out`/`System.err`; any log call uses the structured `eventName`/`eventData` form.
- [ ] README has a short Iteration 0 section, noting the deviation: Docker Compose deferred to Iteration 1.
