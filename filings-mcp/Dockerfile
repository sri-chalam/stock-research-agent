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

FROM amazoncorretto:25-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
