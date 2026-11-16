# Learning Notes

Short points worth remembering: how things work and what tripped us up. Design decisions go in [ADRs](adr/); this file is for quick refreshers.

## Gradle

- `buildSrc` holds shared build plugins. Gradle compiles it before the other modules and makes its plugins available to them. See [buildSrc/](../buildSrc/).
  - `stockagent.java-conventions`: Java 25 toolchain, UTF-8, Spotless, JUnit. Used by every module.
  - `stockagent.spring-app`: the above plus Spring Boot and its BOM. Used by `agent-app` and `market-data-mcp`.
  - A module opts in with one line: `id("stockagent.spring-app")`.
- Third-party Gradle plugins (Spring Boot, Spotless) are added as `dependencies` in [buildSrc/build.gradle.kts](../buildSrc/build.gradle.kts). The convention plugins can then apply them by `id(...)` with no version.
- buildSrc does not see the root version catalog automatically. [buildSrc/settings.gradle.kts](../buildSrc/settings.gradle.kts) loads `../gradle/libs.versions.toml` as `libs`.
- App libraries (starter-web, LangChain4j) are declared in each module's own `build.gradle.kts`, not in buildSrc.
- Gradle runs a module's tests from that module's directory, so `Path.of("src/main/java")` in a test means that module's sources.
- Start each app with its own command in its own terminal: `./gradlew :agent-app:bootRun` and `./gradlew :market-data-mcp:bootRun`. A plain `./gradlew bootRun` starts only the first app: Gradle runs one task at a time by default and `bootRun` never finishes. `--parallel` would start both but mix their logs in one terminal.

## Spring config

- Two files named `classpath:application.yml` on one classpath are not merged; one hides the other. Shared settings go in a differently named file, here [common.yml](../common/src/main/resources/common.yml), and each app pulls it in with `spring.config.import: classpath:common.yml`.
- An imported file wins over the file that imports it. A key in `common.yml` overrides the same key in an app's `application.yml`.
- `${spring.application.name}` in `common.yml` resolves per app, so one logging block gives each app its own `service` value.

## Spring Boot apps

- A `@SpringBootApplication` class cannot be `final` because Spring subclasses `@Configuration` classes. The "final + private constructor" rule for entry points doesn't apply to it.
- Component scanning starts at the application class's package. `agent-app` (`info.saagar.stockagent`) finds `info.saagar.stockagent.common` on its own. `market-data-mcp` (`info.saagar.stockagent.marketdata`) needs `scanBasePackages` to find it. See [MarketDataMcpApplication](../market-data-mcp/src/main/java/info/saagar/stockagent/marketdata/MarketDataMcpApplication.java).

- Both apps set `server.address: 127.0.0.1`, so they listen on IPv4 loopback only. Call them at `http://127.0.0.1:<port>`, not `localhost`. Some clients (Postman and other Node-based tools) resolve `localhost` to IPv6 `::1` and fail with `ECONNREFUSED ::1:8080`; curl falls back to IPv4, so it hides the problem.

## MCP

- `langchain4j-community-mcp-server` ships only a stdio transport. `market-data-mcp` serves it over HTTP through its own `POST /mcp` controller. See [ADR 0010](adr/0010-mcp-transport-spring-mvc-controller.md).
- JSON-RPC ids must be numbers. With a string id such as `"id":"1"`, the library treats the message as a notification and sends no reply.
- The server is stateless, so a direct `curl` to `/mcp` can call `tools/list` or `tools/call` without calling `initialize` first.
- The LangChain4j MCP client sends `ping` as its health check, and `McpServer` doesn't answer it, so the controller answers `ping` itself.
- agent-app pins MCP protocol version `2025-11-25`. Without the pin, the client sends a `server/discover` probe, which our servers don't support.
- When a tool throws, the library turns the exception into an `isError: true` result and doesn't log it. Log the failure where it happens (for example `finnhubCallFailed` in the Finnhub client).
- agent-app fails to start when an MCP server is unreachable. Start `market-data-mcp` and `filings-mcp` first.
- Finnhub's hosted MCP server supports only per-user OAuth for Claude and ChatGPT, so agent-app can't use it. It's still useful in Claude Desktop for exploration. See [ADR 0009](adr/0009-own-market-data-mcp-instead-of-finnhub-hosted-mcp.md).

### Runtime flow of a chat request

```
ChatController → agent.chat(prompt)                        (AiServices proxy)
  → McpToolProvider → McpClient → POST /mcp  tools/list   (first request only, then cached; allowlist filter applied)
  → Claude gets prompt + tool specs, replies "call getQuote(AAPL)"
  → McpClient → POST /mcp  tools/call
      → McpController → McpServer.handle → MarketDataTools.getQuote → Finnhub
  → tool result back to Claude → final answer → ChatController
```

- No project code calls `/mcp`. LangChain4j's `StreamableHttpMcpTransport` (inside `DefaultMcpClient`, built in [McpClientConfig](../agent-app/src/main/java/info/saagar/stockagent/config/McpClientConfig.java)) sends every request.
- The agent interface has no tool code. Claude picks the tool; the `AiServices` proxy runs it through the MCP client and loops until Claude returns text.
- `DefaultMcpClient` caches the tool list (`cacheToolList` defaults to `true`). `McpToolProvider` asks for it on every request, but only the first one reaches the server. A restart of agent-app is the only way to pick up tool changes, since our stateless servers can't send `notifications/tools/list_changed`.
- Outside chat requests, the client sends `initialize` at agent-app startup and `ping` as a health check.
- `McpController` routing: it answers `ping` itself, wraps `tools/call` in `handleToolCall` (logging), and passes everything else to `McpServer.handle`.
- `McpServer.handle` switches on `method`: `initialize` → `handleInitialize`, `tools/list` → `handleListTools`, `tools/call` → `handleCallTool` (invokes the `@Tool` method), anything else → "Method not found" error. A message with no numeric `id` returns `null`, so the controller replies `202 Accepted`.

## Agents (langchain4j-agentic)

- `AgenticServices.agentBuilder` has no `afterToolExecution` hook, which `AiServices` has. To observe tool calls, implement `AgentListener` (`afterAgentToolExecution`) instead.
- Attach each listener once, on the Supervisor. If its `inheritedBySubagents()` returns `true`, it also gets every specialist's agent and tool events, so you don't register it on each specialist. `ToolExecutionLogger` (logs `toolExecuted`) and `AgentProvenanceListener` (records the agents and tools for `answerProvenance`) work this way.
- In `beforeAgentInvocation`, `agent.parent() == null` means the Supervisor itself. Any other agent is a specialist.
- Each run gets a fresh agentic scope with a random `memoryId`. `AgentProvenanceListener` keys each run's records by it.
- The Supervisor calls specialists one at a time. Its planner picks one agent, reads that agent's answer, then picks the next one or finishes. A filings question and a quote question don't depend on each other, so they could run in parallel. That would need a parallel agent, which always calls every agent it holds.
- `supervisorAgent.invokeWithAgenticScope(...)` runs the whole flow: planning, specialist calls and tool calls. It returns a `ResultWithAgenticScope`:
  - `result()`: the final answer.
  - `agenticScope()`: the run's shared context. It holds the run's `memoryId` and the values agents read and write (for example, the `request` the planner hands a specialist).
- In `ChatController`, the code after `invokeWithAgenticScope` calls no agents. It only reads what the listener recorded and builds the `toolsCalled` list for the response.

## SEC EDGAR

- EDGAR APIs are keyed by the 10-digit zero-padded CIK (e.g. `CIK0000320193.json`), not by ticker. `filings-mcp` configures the CIK for each supported ticker (`filings.tickers` in its `application.yml`) instead of downloading `company_tickers.json`.
- SEC rejects a request with 403 unless its `User-Agent` names the app and gives a contact email. `filings-mcp` reads it from `SEC_USER_AGENT` and fails at startup without it.
- XBRL tags vary by company. Revenue, for example, may be `Revenues`, `RevenueFromContractWithCustomerExcludingAssessedTax` or `SalesRevenueNet`. `getCompanyFacts` hides this behind a small fallback list per metric (`HeadlineMetric`).

## Git

- Use `git mv` when moving files; `git log --follow <file>` then shows the history from before the move. This works only once the move is committed.
