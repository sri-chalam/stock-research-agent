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

## Supervisor

Built in [SupervisorAgentConfig](../agent-app/src/main/java/info/saagar/stockagent/config/SupervisorAgentConfig.java) with `AgenticServices.supervisorBuilder()` (`langchain4j-agentic` `1.23.0-beta33`). Source of the behaviour below: `SupervisorPlanner`, `PlannerAgent` and `ResponseAgent` in that module.

### The planner loop

- The Supervisor is a loop around one LLM call, the planner (`PlannerAgent.plan`). Each planner call returns one JSON choice: a specialist agent's name and its arguments, or `done`.
- After a specialist answers, the planner is called again with that answer as `lastResponse`, and picks another specialist or `done`. One prompt can reach several specialist agents, one at a time.
- The planner keeps a chat memory for the run (`CHAT_MEMORY`, a 20-message window, the default). Later planner calls see the earlier specialists' answers too, not just the last one.
- `max-steps: 4` (`maxAgentsInvocations`; library default 10) caps the specialist calls per run. When the cap is hit, the run stops without a `done` call and returns the last specialist's answer, whatever the response strategy.
- The planner's system prompt is the library's own. Our [supervisor-v1](../agent-app/src/main/resources/prompts/supervisor-v1.txt) prompt is only appended to it as the "supervisor context". The planner sees each specialist as a card built from its `@Agent` name, description and arguments, so the description decides the routing.

### What a specialist agent receives

- A specialist gets the planner's sub-question, not the user's prompt. The planner writes `request` as an argument, and the library stores it in the agentic scope, where the specialist's `@V("request")` reads it.
- Risk: the planner rewrites the question and can drop details, for example a fiscal year, a form type or "last 3 years". The specialist can't recover them; it never sees the original prompt. No log line records the sub-question; only the tool arguments in `toolExecuted` hint at it.

### How the answers are merged

The response strategy decides what the run returns once the planner says `done`:

| Strategy | Returns | Extra LLM calls |
|---|---|---|
| `LAST` (library default) | The last specialist's answer only. In a combined question, the first specialist's answer is lost | None |
| `SUMMARY` (ours) | The `response` argument of the planner's `done` choice: the planner's recap of every specialist's answer, from its chat memory | None. The recap rides on the `done` planner call, which runs under every strategy |
| `SCORED` | Builds both, then a `ResponseAgent` call scores each against the user's prompt (0.0 to 1.0) and returns the higher one | One scoring call |

- With `SUMMARY`, the final answer is written by the planner, not by a specialist. A number or citation can change in that rewrite. `supervisor-v1` tells it to keep them exactly; nothing checks it (the Critic was dropped).

### Runtime flow of a combined question

"Apple's revenue for the last 3 fiscal years and its current price":

```
ChatController → supervisorAgent.invokeWithAgenticScope(prompt)   (fresh scope, random memoryId)
  → planner call 1: agents list + prompt            → {filings, request: "Apple's revenue, last 3 fiscal years"}
  → FilingsAgent.answer(request)                   (own LLM loop)
      → getCompanyFacts(AAPL) via filings-mcp → answer with citations
  → planner call 2: lastResponse = filings answer  → {market-data, request: "Current price of Apple (AAPL)"}
  → MarketDataAgent.answer(request)                (own LLM loop)
      → getQuote(AAPL) via market-data-mcp → answer
  → planner call 3: lastResponse = price answer    → {done, response: merged recap}
  → SUMMARY returns the recap → ChatController reads AgentProvenanceListener → answerProvenance
```

- A single-specialist question makes two planner calls (pick, then `done`); this one makes three.

### Cost of the Supervisor's extra calls

One run per prompt on Compose, all agents on `claude-haiku-5-5`, from ticket 02 (`answerProvenance`). Tokens are input / output. Single runs, so rough numbers.

| Prompt | `supervisor` | Specialist agents | Total | Duration |
|---|---|---|---|---|
| "List Microsoft's recent 10-K filings" | 2,501 / 600 | 4,084 / 593 | 6,585 / 1,193 | 6,061 ms |
| "Price of Apple" | 2,070 / 337 | 3,661 / 160 | 5,731 / 497 | 5,783 ms |
| Revenue + current price (combined) | 3,886 / 528 | 8,737 / 515 | 12,623 / 1,043 | 9,657 ms |

- The Supervisor is 31–38% of each run's input tokens. Most of it is fixed: the library's planner prompt, our supervisor context and the agent cards are sent on every planner call.
- With `SUMMARY`, the Supervisor writes the final answer, so its output tokens match or beat the specialists'.
- Planner calls run in series with the specialists, so each one adds a full LLM round trip to the run. `answerProvenance` logs only the run's total duration, not time per agent.
- The quote bypass (FR-5, Iteration 4) removes the Supervisor's calls from "Price of Apple"-type prompts.

## SEC EDGAR

- EDGAR APIs are keyed by the 10-digit zero-padded CIK (e.g. `CIK0000320193.json`), not by ticker. `filings-mcp` configures the CIK for each supported ticker (`filings.tickers` in its `application.yml`) instead of downloading `company_tickers.json`.
- SEC rejects a request with 403 unless its `User-Agent` names the app and gives a contact email. `filings-mcp` reads it from `SEC_USER_AGENT` and fails at startup without it.
- XBRL tags vary by company. Revenue, for example, may be `Revenues`, `RevenueFromContractWithCustomerExcludingAssessedTax` or `SalesRevenueNet`. `getCompanyFacts` hides this behind a small fallback list per metric (`HeadlineMetric`).

## Git

- Use `git mv` when moving files; `git log --follow <file>` then shows the history from before the move. This works only once the move is committed.
