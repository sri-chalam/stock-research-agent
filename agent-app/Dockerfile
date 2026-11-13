# Build context is the repository root (multi-module build): see docker-compose.yml.

FROM amazoncorretto:25-alpine AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY common common
COPY agent-app agent-app
# settings.gradle.kts includes every module, and Gradle 9 requires each included project's directory.
COPY market-data-mcp/build.gradle.kts market-data-mcp/
COPY filings-mcp/build.gradle.kts filings-mcp/
# The Gradle cache is shared by all images; `sharing=locked` stops their parallel builds from
# contending for Gradle's cache locks. Tests are skipped here only for Docker layer caching
# (CLAUDE.md §9); `./gradlew build` runs them.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew :agent-app:build -x test --no-daemon --console=plain \
    && cp agent-app/build/libs/agent-app-*[!n].jar /workspace/app.jar

FROM amazoncorretto:25-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
