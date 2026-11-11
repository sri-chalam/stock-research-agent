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

## Git

- Use `git mv` when moving files; `git log --follow <file>` then shows the history from before the move. This works only once the move is committed.
