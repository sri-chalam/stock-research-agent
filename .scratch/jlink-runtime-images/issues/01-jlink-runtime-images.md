# 01: Run agent-app and market-data-mcp on jlink runtime images

**What to build:** The agent-app and market-data-mcp Docker images run on `alpine:latest` with a custom Java runtime built by jlink, instead of the full Corretto 25 JDK image. The result is smaller images, so ECR pushes and Fargate Spot task starts are faster. The app behaves exactly as before: "price of Apple" through the chat API still returns a live Finnhub price through market-data-mcp. filings-mcp is out of scope and stays on `amazoncorretto:25-al2023-headless`.

Decisions settled in the grilling session:

- **Build stage:** keep `amazoncorretto:25-alpine`. It ships `jmods/` (checked on 25.0.5), so jlink works without a linkable-runtime JDK. Run jlink in the build stage (or in a separate stage based on the same image) so the runtime is musl-linked like the final image.
- **Final stage:** `alpine:latest`, deliberately unpinned to pick up security fixes. Images are rebuilt with `--pull`. Keep the existing non-root `app` user.
- **Module list:** a fixed list, written inline as `--add-modules` in each Dockerfile, with no shared file and no build arg. It is the same list in both images:
  `java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.attach,jdk.jcmd,jdk.jfr,jdk.management,jdk.management.jfr,jdk.unsupported,jdk.zipfs`
  The list includes the observability and debug modules: JMX and Micrometer metrics, the OpenTelemetry agent and OTLP export, JFR, and `jcmd`.
- **jlink flags:** `--strip-debug --no-man-pages --no-header-files --compress=zip-6`.
- **Out of scope:** Spring Boot layered-jar extraction is a separate change. filings-mcp is skipped, and no follow-up ticket is planned.
- **ADR:** add `0012-jlink-runtime-images` covering the approach, the reason for each module, unpinned `alpine:latest` with `--pull`, the before and after image sizes, and why filings-mcp is left unchanged (glibc and ONNX Runtime native libraries). Comments in the Dockerfiles point to the ADR.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `docker compose build --pull` builds both images. Their final stage is `alpine:latest` with the jlinked runtime and no full JDK.
- [ ] With `docker compose up`, `POST /api/chat` "price of Apple" returns a live price. This covers TLS to Anthropic and Finnhub and the MCP call from agent-app to market-data-mcp.
- [ ] Neither app logs a `ClassNotFoundException`, `NoClassDefFoundError` or module-resolution error at startup or during that request.
- [ ] `jcmd 1 VM.version` succeeds inside the running market-data-mcp container and inside the running agent-app container.
- [ ] The before and after image sizes for both apps are recorded in ADR 0012.
- [ ] `./gradlew build` passes.
