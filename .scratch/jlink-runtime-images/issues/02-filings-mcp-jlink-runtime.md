# 02: Run filings-mcp on a glibc jlink runtime image

**What to build:** The filings-mcp Docker image runs on `amazonlinux:2023` with a custom Java runtime built by jlink, instead of the full `amazoncorretto:25-al2023-headless` JDK image. The image is about 637 MB today: a 183 MB OS layer, a 301 MB JDK and a 154 MB jar. Replacing the JDK layer should bring it to about 400 MB. The OS stays glibc-based, because ONNX Runtime and the DJL tokenizer ship only glibc native libraries. The app behaves exactly as before: all three filings tools answer through `POST /api/filings/chat`.

Decisions settled in the grilling session:

- **Scope:** a jlink runtime only. Switching to a smaller glibc base (`amazonlinux:2023-minimal`, `debian:trixie-slim`) is not part of this ticket, and neither is removing non-Linux native libraries from the ONNX Runtime and tokenizers jars.
- **jlink stage:** a new `jre` stage on `amazoncorretto:25-al2023`. It ships `jmods/` (checked on 25.0.5) and is glibc-linked like the final image. The stage uses the same jlink flags as ticket 01 (`--strip-debug --no-man-pages --no-header-files --compress=zip-6`) and installs `binutils`, because `--strip-debug` needs `objcopy` on Linux. The build stage is unchanged.
- **Final stage:** `amazonlinux:2023`, deliberately unpinned to pick up security fixes. Images are rebuilt with `--pull`. It is the same 183 MB OS layer as today's base, and it has `curl` (the compose healthcheck), `libstdc++`, `libgcc_s` and `zlib`, but no `useradd`. Keep the hand-added `app` user and `/home/app`, where DJL caches its native library, and keep `--enable-native-access=ALL-UNNAMED` in the entrypoint.
- **Module list:** start from the same 18-module list as agent-app and market-data-mcp, written inline as `--add-modules`. Add a module only when a check below shows it is needed, and record each addition and its reason in ADR 0012.
- **No CDS archive:** no `--generate-cds-archive`, the same as ticket 01. Measured on market-data-mcp, the full JDK with CDS started in about 2.7 s at 180 MiB and the jlink runtime without CDS in about 2.5 s at 186 MiB. The archive would add about 10–15 MB per image.
- **`FilingIngestion` is unchanged.** It catches only `RuntimeException`, so a `LinkageError` (`NoClassDefFoundError`, `UnsatisfiedLinkError`) kills the ingestion thread with a plain stderr stack trace and no `filingIngestionFailed` line. The log checks below detect this. Making it a JSON log event is a possible follow-up, not part of this ticket.
- **ADR:** update `0012-jlink-runtime-images` in place (it is not committed yet), so it covers all three images. Add the filings-mcp before and after sizes, the glibc base and its reason, any added modules, and the CDS decision. Update the Container row in `AGENTS.md` and the comments in `filings-mcp/Dockerfile`.

**Blocked by:** 01 (ADR 0012 and the jlink approach)

**Status:** ready-for-agent

- [ ] `docker compose build --pull filings-mcp` builds. The final stage is `amazonlinux:2023` with the jlinked runtime and no full JDK.
- [ ] After `docker compose up`, filings-mcp is healthy and logs `filingIngestionCompleted` with `failedTickerCount` 0.
- [ ] Through `POST /api/filings/chat`, `listFilings` (Microsoft's 10-Ks), `getCompanyFacts` (Apple's revenue) and `searchFilings` (Apple's risk factors) return correct answers. An unconfigured ticker and a missing `formType` each return a model-readable error that the agent relays.
- [ ] The filings-mcp logs contain no `Exception in thread`, `NoClassDefFoundError`, `ClassNotFoundException` or `UnsatisfiedLinkError`.
- [ ] `jcmd 1 VM.version` succeeds inside the running filings-mcp container.
- [ ] ADR 0012 records the filings-mcp before and after image sizes, any added modules and the CDS decision.
- [ ] `./gradlew build` passes.
- [ ] Changes are left uncommitted for review, together with ticket 01.
