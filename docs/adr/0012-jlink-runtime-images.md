# 0012. jlink runtime images for agent-app and market-data-mcp

## Status

Accepted

## Context

The agent-app and market-data-mcp images ran on `amazoncorretto:25-alpine`, a full JDK of about 400 MB per image. From Iteration 2b, each deploy pushes the images to ECR, and each Fargate Spot task start pulls them (ADR 0003, ADR 0005). Smaller images make both faster. The apps need only a Java runtime, not the compiler, `jshell` or the other JDK tools.

## Decision

- **Build:** a `jre` stage on `amazoncorretto:25-alpine` runs `jlink` against the JDK's `jmods/` (Corretto 25.0.5 still ships them, so no linkable-runtime JDK is needed). The runtime it builds is musl-linked, like the final image. The stage installs `binutils` because, on Linux, `--strip-debug` also strips native debug symbols with `objcopy`. The stage is thrown away, so `binutils` does not reach the final image.
- **Flags:** `--strip-debug --no-man-pages --no-header-files --compress=zip-6`.
- **Final stage:** `alpine:latest` with the runtime in `/opt/java` and the existing non-root `app` user. The tag is deliberately unpinned so rebuilds pick up Alpine security fixes. Images are built with `docker compose build --pull`, so the build does not reuse a stale cached `alpine:latest`. Base Alpine already has `zlib`. The X11 and ALSA libraries that the Corretto image installs are needed only by `libawt_xawt`, which a headless JVM never loads.
- **Modules:** one fixed list, written inline as `--add-modules` in both Dockerfiles. There is no shared file and no build arg. The list is the same in both images, so one review covers both:

| Module | Why |
|---|---|
| `java.base` | Core runtime, including TLS and the `cacerts` trust store for Anthropic and Finnhub |
| `java.desktop` | `java.beans`, used by Spring for bean introspection (`java.beans` lives in this module) |
| `java.instrument` | `-javaagent` support for the OpenTelemetry Java agent |
| `java.logging` | `java.util.logging`, bridged to SLF4J by libraries that use it |
| `java.management` | JMX: Spring Boot Actuator and Micrometer JVM metrics |
| `java.naming` | JNDI (`javax.naming`), referenced by embedded Tomcat, Spring context and Logback |
| `java.net.http` | `java.net.http.HttpClient`, the JDK HTTP client used by LangChain4j |
| `java.security.jgss` | Kerberos/GSS-API. No known direct use; kept as a safety net for HTTP and JDBC authentication code paths (small) |
| `java.security.sasl` | SASL. No known direct use; kept as a safety net for JNDI and JMX remote (small) |
| `java.sql` | JDBC and `java.sql` types, referenced by Spring Boot auto-configuration |
| `java.xml` | XML parsing used by Spring, Logback and libraries |
| `jdk.attach` | Attach API, used by `jcmd` and dynamic agent loading |
| `jdk.jcmd` | `jcmd` for thread dumps, heap info and JFR control in a running container |
| `jdk.jfr` | Java Flight Recorder |
| `jdk.management` | HotSpot-specific JMX beans (for example, OS and thread metrics in Micrometer) |
| `jdk.management.jfr` | JFR control over JMX, and JFR event streaming |
| `jdk.unsupported` | `sun.misc.Unsafe`, referenced by `spring-core` |
| `jdk.zipfs` | The zip/jar `FileSystemProvider`. No library in the boot jars references it directly (Spring Boot reads nested jars with its own provider); kept as a safety net for code that opens a jar with `FileSystems.newFileSystem` (small) |

- **Scope:** filings-mcp is left on `amazoncorretto:25-al2023-headless`. ONNX Runtime, used by its in-process embedding model, ships glibc native libraries that do not run on Alpine's musl. A jlink runtime on a glibc base would save less space and needs a separate module review, so it is not planned. Spring Boot layered-jar extraction is a separate change.

### Image sizes

Built with `docker compose build --pull`, linux/arm64, on 2026-11-14. Sizes are as reported by `docker images`. linux/amd64 sizes may differ slightly.

| Image | Before (`amazoncorretto:25-alpine`) | After (`alpine:latest` + jlink) |
|---|---|---|
| agent-app | 402 MB | 104 MB |
| market-data-mcp | 399 MB | 101 MB |

## Consequences

- Each image is about 300 MB smaller, which makes ECR pushes and Fargate Spot pulls faster.
- The final images contain no `javac`, `jshell` or other JDK tools. `java` and `jcmd` are on the `PATH`.
- A dependency that needs a module missing from the list fails at runtime with `ClassNotFoundException` or `NoClassDefFoundError`, not at build time. When this happens, add the module to both Dockerfiles and to the table above. `jdeps --print-module-deps` on the boot jar can suggest the module.
- The Iteration 2b image build must also use `--pull`, or it reuses a stale cached `alpine:latest`.
- An unpinned `alpine:latest` can change between builds. A new Alpine release could break the image without any change in this repository. This is accepted in exchange for automatic security fixes.

## Alternatives considered

- **Keep the full JDK image:** simplest, but about 300 MB more per image for tools the apps never use.
- **Module list from `jdeps` at build time:** adapts to dependency changes. However, `jdeps` misses modules that are loaded reflectively or only by agents (`java.instrument`, `jdk.attach`, `jdk.jfr`), so a hand-reviewed list is needed anyway.
- **Pinned Alpine version or digest:** reproducible builds, but security fixes need manual bumps.
- **Distroless or glibc base for all three apps:** one base for every image, but a larger final image, and the full musl/Alpine path is already proven for the two apps without native libraries.
