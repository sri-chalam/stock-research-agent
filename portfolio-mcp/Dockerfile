# Build context is the repository root (multi-module build): see docker-compose.yml.

FROM amazoncorretto:25-alpine AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY common common
COPY portfolio-mcp portfolio-mcp
# settings.gradle.kts includes every module, and Gradle 9 requires each included project's directory.
COPY agent-app/build.gradle.kts agent-app/
COPY filings-mcp/build.gradle.kts filings-mcp/
COPY market-data-mcp/build.gradle.kts market-data-mcp/
# The Gradle cache is shared by all images; `sharing=locked` stops their parallel builds from
# contending for Gradle's cache locks. Tests are skipped here only for Docker layer caching
# (CLAUDE.md §9); `./gradlew build` runs them.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew :portfolio-mcp:build :portfolio-mcp:copyOtelJavaagent -x test --no-daemon --console=plain \
    && cp portfolio-mcp/build/libs/portfolio-mcp-*[!n].jar /workspace/app.jar \
    && cp portfolio-mcp/build/otel/opentelemetry-javaagent.jar /workspace/

# A minimal Java runtime from the JDK's jmods, musl-linked like the final image (ADR 0012).
# The module list includes JMX, JFR, jcmd and the attach API for metrics, the OpenTelemetry agent
# and debugging; ADR 0012 gives the reason for each module. It is the same list in every image.
FROM amazoncorretto:25-alpine AS jre
# On Linux, --strip-debug also strips native debug symbols, which needs objcopy (binutils).
RUN apk add --no-cache binutils && jlink \
    --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.attach,jdk.jcmd,jdk.jfr,jdk.management,jdk.management.jfr,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
    --output /opt/java

# Unpinned on purpose to pick up Alpine security fixes; build with `--pull` (ADR 0012).
FROM alpine:latest
ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre /opt/java /opt/java
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
# Tracing (ADR 0013): compose mounts the agent's config file and sets OTEL_CONFIG_FILE.
COPY --from=build /workspace/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8084
ENTRYPOINT ["java", "-javaagent:opentelemetry-javaagent.jar", "-jar", "app.jar"]
