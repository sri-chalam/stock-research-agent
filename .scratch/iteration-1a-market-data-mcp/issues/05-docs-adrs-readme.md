# 05: ADRs, spec wording and README for Iteration 1a

**What to build:** Record the 1a decisions so a reader can find why things are the way they are.

**Blocked by:** 02 (ADRs can be drafted earlier; README run instructions need 04)

**Status:** ready-for-agent

## Decisions

- **ADR 0009, "Own market-data-mcp instead of Finnhub's hosted MCP server":**
  - Context: Finnhub's hosted server (`https://mcp.finnhub.io/mcp`) documents only interactive, per-user OAuth for the `claude` and `chatgpt` client IDs. It documents no client-credentials flow and gives no guidance for other MCP clients, so agent-app and the M2M morning brief (SEC-7) can't authenticate. It also has no company or market news tool and exposes about 70 tools.
  - Decision: build our own `market-data-mcp` against the Finnhub REST API with the shared key (ADR 0008).
  - Consequence: the hosted server can still be used in Claude Desktop for exploration (MCP-7).
  - Add a link to it from ADR 0002.
- **ADR 0010, "MCP transport: Spring MVC controller around the LangChain4j community McpServer":**
  - `langchain4j-community-mcp-server` 1.23.0-beta33 ships only a stdio transport, so we wrote a `POST /mcp` controller that returns JSON responses. Notifications return 202, `GET` returns 405, and the controller handles `ping`.
  - Library limits: only `initialize`, `tools/list` and `tools/call`; numeric ids only; Jackson 2 internally; no `structuredContent`/`outputSchema`; `isError` only when a tool throws.
  - The client is pinned to protocol `2025-11-25` to skip the `server/discover` probe.
  - Rejected alternative: the official MCP Java SDK `mcp-spring-webmvc` (0.18.x).
  - Revisit when the library ships an HTTP transport.
- **Spec §14, row 1a:** rename `logging-common` to `common`, and stop saying the library provides Streamable HTTP. Name the hand-written controller instead.
- **README:**
  - A ports table: agent-app `8080`, market-data-mcp `8081` (`/mcp`), and reserved ports filings-mcp `8082`, macro-mcp `8083` and portfolio-mcp `8084`. All are bound to `127.0.0.1`.
  - Run instructions for Gradle (start market-data-mcp first) and Compose.
  - A curl example for a direct `tools/call`.
  - An Iteration 1a section.
- **GLOSSARY.md:** add terms only if 1a introduced new domain terms. Check this; it may need nothing.

## Acceptance criteria

- [ ] ADR 0009 and ADR 0010 exist in `docs/adr/` in the existing ADR format, and ADR 0002 links to 0009.
- [ ] Spec §14 row 1a matches what was built.
- [ ] README has the ports table, run instructions for both modes, and a `tools/call` curl example.

## Comments
