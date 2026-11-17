# 0012. jlink runtime images for all three apps

## Status

Accepted. Amended in Iteration 4: `agent-app` moves to the glibc base (see **Amendment: agent-app on amazonlinux:2023**).

## Context

The agent-app and market-data-mcp images ran on `amazoncorretto:25-alpine`, a full JDK of about 400 MB per image. The filings-mcp image ran on `amazoncorretto:25-al2023-headless`, about 640 MB: a 183 MB OS layer, a 301 MB JDK and a 154 MB jar. From Iteration 2b, each deploy pushes the images to ECR, and each Fargate Spot task start pulls them (ADR 0003, ADR 0005). Smaller images make both faster. The apps need only a Java runtime, not the compiler, `jshell` or the other JDK tools.

## Decision

- **Build:** a `jre` stage runs `jlink` against the JDK's `jmods/` (Corretto 25.0.5 still ships them, so no linkable-runtime JDK is needed). For agent-app and market-data-mcp the stage is `amazoncorretto:25-alpine`, so the runtime is musl-linked like their final image. For filings-mcp it is `amazoncorretto:25-al2023`, so the runtime is glibc-linked like its final image (see **glibc base** below). The stage installs `binutils` because, on Linux, `--strip-debug` also strips native debug symbols with `objcopy`. The stage is thrown away, so `binutils` does not reach the final image.
- **Flags:** `--strip-debug --no-man-pages --no-header-files --compress=zip-6`.
- **Final stage:** `alpine:latest` (agent-app, market-data-mcp) or `amazonlinux:2023` (filings-mcp), with the runtime in `/opt/java` and the existing non-root `app` user. Both tags are deliberately unpinned so rebuilds pick up OS security fixes. Images are built with `docker compose build --pull`, so the build does not reuse a stale cached base image. Base Alpine already has `zlib`. The X11 and ALSA libraries that the Corretto image installs are needed only by `libawt_xawt`, which a headless JVM never loads.
- **glibc base for filings-mcp:** ONNX Runtime and the DJL tokenizer, behind its in-process embedding model, ship only glibc native libraries, which do not run on Alpine's musl. `amazonlinux:2023` is the same 183 MB OS layer as the Corretto image it replaces. It has `curl` (the compose healthcheck), `libstdc++`, `libgcc_s` and `zlib`, but no `useradd`, so the `app` user and `/home/app` (where DJL caches its native library) are added by hand. The entrypoint keeps `--enable-native-access=ALL-UNNAMED`, because ONNX Runtime loads its native library through `System.load`. A smaller glibc base (`amazonlinux:2023-minimal`, `debian:trixie-slim`) and removing the non-Linux native libraries from the ONNX Runtime and tokenizers jars are possible follow-ups.
- **Modules:** one fixed list, written inline as `--add-modules` in all three Dockerfiles. There is no shared file and no build arg. The list is the same in every image, so one review covers all three. filings-mcp needed no additional module: ingestion, the three tools through `POST /api/filings/chat` and `jcmd` all ran with no `NoClassDefFoundError`, `ClassNotFoundException` or `UnsatisfiedLinkError` in its logs.

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

- **No CDS archive:** no `--generate-cds-archive`. Measured on market-data-mcp, the full JDK with its default CDS archive started in about 2.7 s at 180 MiB, and the jlink runtime without CDS in about 2.5 s at 186 MiB. The archive would add about 10–15 MB per image for no measurable startup gain.
- **Scope:** Spring Boot layered-jar extraction is a separate change.

### Image sizes

Built with `docker compose build --pull`, linux/arm64, on 2026-11-14. Sizes are as reported by `docker images`. linux/amd64 sizes may differ slightly.

| Image | Before | After |
|---|---|---|
| agent-app | 402 MB (`amazoncorretto:25-alpine`) | 104 MB (`alpine:latest` + jlink) |
| market-data-mcp | 399 MB (`amazoncorretto:25-alpine`) | 101 MB (`alpine:latest` + jlink) |
| filings-mcp | 638 MB (`amazoncorretto:25-al2023-headless`) | 405 MB (`amazonlinux:2023` + jlink) |

## Consequences

- agent-app and market-data-mcp are about 300 MB smaller each, and filings-mcp about 230 MB smaller, which makes ECR pushes and Fargate Spot pulls faster.
- The final images contain no `javac`, `jshell` or other JDK tools. `java` and `jcmd` are on the `PATH`.
- A dependency that needs a module missing from the list fails at runtime with `ClassNotFoundException` or `NoClassDefFoundError`, not at build time. When this happens, add the module to all three Dockerfiles and to the table above. `jdeps --print-module-deps` on the boot jar can suggest the module.
- In filings-mcp, a missing module surfaces as a `LinkageError` (`NoClassDefFoundError`, `UnsatisfiedLinkError`) on the ingestion thread. `FilingIngestion` catches only `RuntimeException`, so this kills the thread with a plain stderr stack trace (`Exception in thread`) and no `filingIngestionFailed` log line. Check the logs for these strings after changing the module list.
- The Iteration 2b image build must also use `--pull`, or it reuses a stale cached `alpine:latest` or `amazonlinux:2023`.
- Unpinned `alpine:latest` and `amazonlinux:2023` can change between builds. A new OS release could break an image without any change in this repository. This is accepted in exchange for automatic security fixes.

## Amendment: agent-app on amazonlinux:2023

From Iteration 4, `agent-app` embeds questions for the semantic cache with an in-process bge-small model, the same ONNX model `filings-mcp` uses. ONNX Runtime and the DJL tokenizer ship only glibc native libraries, so `agent-app` follows the **glibc base for filings-mcp** decision above:

- The `jre` stage is `amazoncorretto:25-al2023`, and the final stage is `amazonlinux:2023`, with the `app` user and `/home/app` added by hand and `--enable-native-access=ALL-UNNAMED` in the entrypoint.
- `market-data-mcp` and `portfolio-mcp` stay on `alpine:latest`; they load no native libraries.
- The module list is unchanged.
- `agent-app`'s image grows beyond the 104 MB in the table above; its size is re-measured when the change lands.

## Alternatives considered

- **Keep the full JDK image:** simplest, but about 300 MB more per image for tools the apps never use.
- **Module list from `jdeps` at build time:** adapts to dependency changes. However, `jdeps` misses modules that are loaded reflectively or only by agents (`java.instrument`, `jdk.attach`, `jdk.jfr`), so a hand-reviewed list is needed anyway.
- **Pinned Alpine version or digest:** reproducible builds, but security fixes need manual bumps.
- **Distroless or glibc base for all three apps:** one base for every image, but a larger final image, and the full musl/Alpine path is already proven for the two apps without native libraries.
- **Keep the full Corretto JDK for filings-mcp:** no module review for the native-library app, but about 230 MB more for JDK tools it never uses.
