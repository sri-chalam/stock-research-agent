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
# The Gradle cache is shared by all images; `sharing=locked` stops their parallel builds from
# contending for Gradle's cache locks. Tests are skipped here only for Docker layer caching
# (CLAUDE.md §9); `./gradlew build` runs them.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew :filings-mcp:build -x test --no-daemon --console=plain \
    && cp filings-mcp/build/libs/filings-mcp-*[!n].jar /workspace/app.jar

# Not Alpine: ONNX Runtime and the DJL tokenizer behind the in-process embedding model ship only
# glibc native libraries. The headless image has no useradd, so the app user is added by hand;
# DJL caches its native library under the user's home.
FROM amazoncorretto:25-al2023-headless
RUN echo 'app:x:1000:1000::/home/app:/sbin/nologin' >> /etc/passwd \
    && echo 'app:x:1000:' >> /etc/group \
    && mkdir -p /home/app /app && chown 1000:1000 /home/app /app
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8082
# ONNX Runtime loads its native library through System.load.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
