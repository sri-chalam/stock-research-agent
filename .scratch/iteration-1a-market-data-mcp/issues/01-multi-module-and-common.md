# 01: Split into a multi-module Gradle build with a shared `common` module

**What to build:** Turn the single-module project into three Gradle modules (`agent-app`, `market-data-mcp` and `common`) with no change in behaviour. agent-app works exactly as it does today, and all existing tests pass. `market-data-mcp` is an empty Spring Boot app that starts, and ticket 02 fills it in.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Decisions (from the Iteration 1a grilling)

- **Move:** `git mv src/ agent-app/src/` so history follows the files. agent-app keeps its current packages (`controller`, `agent`, `tool`, `provider`, `config`, `logging`) under `info.saagar.stockagent`.
- **Build conventions:** `buildSrc` convention plugins, not root `subprojects {}`:
  - `stockagent.java-conventions`: Java 25 toolchain, UTF-8, Spotless (google-java-format), JUnit Platform.
  - `stockagent.spring-app`: applies the above plus Spring Boot and dependency management. Used by `agent-app` and `market-data-mcp`.
  - `buildSrc` reads the root `gradle/libs.versions.toml`.
- **`common` module** (renamed from the spec's `logging-common`): code shared by more than one module, organised by package:
  - `info.saagar.stockagent.common.logging` holds `LoggingContextFilter`.
  - `info.saagar.stockagent.common.web` holds `GlobalExceptionHandler`.
  - `ToolExecutionLogger` and `ToolArguments` stay in agent-app.
  - `StockAgentLogFormatter` doesn't exist yet. When it is written, it goes in `common.logging`.
- **Config (option B):**
  - Each app has its own `application.yml` with only its own settings, plus `spring.config.import: classpath:common.yml`.
  - `common.yml` lives in `common/src/main/resources` and holds the structured-logging block (`logging.structured.*`, with `service: ${spring.application.name}`) and `spring.threads.virtual.enabled: true`.
  - Reason: two `classpath:application.yml` files are not merged, and one shared file would expose every app's settings and key placeholders to the others (ADR 0002, MCP-1).
- **market-data-mcp skeleton:**
  - `info.saagar.stockagent.marketdata` application class.
  - `spring.application.name: market-data-mcp`, `server.port: 8081`, `server.address: 127.0.0.1`.
  - Depends on `common`.
- **agent-app:** `server.port: 8080`, `server.address: 127.0.0.1`.
- **`LogEventNamesTest`:** event names must be unique per app. Make the test scan the app's own `src/main/java` and `common/src/main/java`, and run it in both apps.
- **CLAUDE.md §3:** rewrite the package table per module.

## Acceptance criteria

- [ ] `./gradlew build` passes from the root (Spotless, tests and jars for all three modules).
- [ ] `git log --follow` on a moved agent-app file shows its pre-move history.
- [ ] All existing agent-app tests pass unchanged, apart from imports and package moves.
- [ ] `./gradlew :agent-app:bootRun` answers "price of Apple" exactly as before (manual check).
- [ ] `./gradlew :market-data-mcp:bootRun` starts on `127.0.0.1:8081` and logs JSON in the shared format with `service: market-data-mcp`.
- [ ] Neither app's `application.yml` contains the other app's settings. The logging block exists only in `common.yml`.
- [ ] CLAUDE.md §3 describes the per-module packages.

## Comments
