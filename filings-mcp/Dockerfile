# Build context is the repository root (multi-module build): see docker-compose.yml.

FROM amazoncorretto:25-alpine AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY common common
COPY filings-mcp filings-mcp
# settings.gradle.kts includes every module, and Gradle 9 requires each included project's directory.
COPY agent-app/build.gradle.kts agent-app/
COPY market-data-mcp/build.gradle.kts market-data-mcp/
COPY portfolio-mcp/build.gradle.kts portfolio-mcp/
# The Gradle cache is shared by all images; `sharing=locked` stops their parallel builds from
# contending for Gradle's cache locks. Tests are skipped here only for Docker layer caching
# (CLAUDE.md §9); `./gradlew build` runs them.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew :filings-mcp:build :filings-mcp:copyOtelJavaagent -x test --no-daemon --console=plain \
    && cp filings-mcp/build/libs/filings-mcp-*[!n].jar /workspace/app.jar \
    && cp filings-mcp/build/otel/opentelemetry-javaagent.jar /workspace/

# A minimal Java runtime from the JDK's jmods (ADR 0012). Not Alpine: ONNX Runtime and the DJL
# tokenizer behind the in-process embedding model ship only glibc native libraries, so the runtime
# is built on Amazon Linux 2023 and is glibc-linked like the final image. The module list is the
# same as in the other two images; ADR 0012 gives the reason for each module.
FROM amazoncorretto:25-al2023 AS jre
# On Linux, --strip-debug also strips native debug symbols, which needs objcopy (binutils).
RUN dnf install -y binutils && jlink \
    --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.attach,jdk.jcmd,jdk.jfr,jdk.management,jdk.management.jfr,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
    --output /opt/java

# Unpinned on purpose to pick up security fixes; build with `--pull` (ADR 0012). It has curl for the
# compose healthcheck but no useradd, so the app user is added by hand; DJL caches its native
# library under the user's home.
FROM amazonlinux:2023
ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre /opt/java /opt/java
RUN echo 'app:x:1000:1000::/home/app:/sbin/nologin' >> /etc/passwd \
    && echo 'app:x:1000:' >> /etc/group \
    && mkdir -p /home/app /app && chown 1000:1000 /home/app /app
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
# Tracing (ADR 0013): compose mounts the agent's config file and sets OTEL_CONFIG_FILE.
COPY --from=build /workspace/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8082
# ONNX Runtime loads its native library through System.load.
ENTRYPOINT ["java", "-javaagent:opentelemetry-javaagent.jar", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
