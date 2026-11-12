# 0010. MCP transport: Spring MVC controller around the LangChain4j community McpServer

## Status

Accepted

## Context

MCP servers use the Streamable HTTP transport and are stateless (spec section 7.1, ADR 0002). `market-data-mcp` defines its tools as LangChain4j `@Tool` methods and serves them with `McpServer` from `langchain4j-community-mcp-server` 1.23.0-beta33. That library ships only a stdio transport. It has no HTTP transport.

## Decision

`market-data-mcp` implements Streamable HTTP itself, in one Spring MVC controller (`McpController`) that hands each JSON-RPC message to `McpServer`:

- `POST /mcp` takes one JSON-RPC message and returns a plain `application/json` reply. No SSE stream is opened.
- Notifications get `202 Accepted` with no body.
- `GET /mcp` (the optional server-to-client SSE stream) is not mapped, so it returns `405`.
- No session ID is issued. Every request stands alone.
- The controller answers `ping` itself, because the LangChain4j MCP client sends it as a health check and `McpServer` does not handle it.

The library has these limits, and we accept them:

- It handles only `initialize`, `tools/list` and `tools/call`.
- JSON-RPC ids must be numeric. A string id is treated as a notification and gets no reply.
- It uses Jackson 2 internally, so the controller reads and writes JSON-RPC with Jackson 2 rather than the app's Jackson 3.
- Results have no `structuredContent`, and tools have no `outputSchema`. Tools return text.
- `isError: true` is set only when a tool throws.

The agent-app MCP client pins protocol version `2025-11-25` (`stock-agent.mcp-servers.*.protocol-version`). This skips the `server/discover` probe, which our servers do not support.

## Consequences

- The transport is one small controller that is easy to read and test, with no second MCP library.
- `filings-mcp` and `macro-mcp` need the same controller. Iteration 1b moves it into the shared `mcp-common` module.
- The controller is code we own. Delete it when the library ships an HTTP transport.
- Servers cannot stream progress or send server-initiated messages. Stateless tools do not need either.

## Alternatives considered

- **Official MCP Java SDK `mcp-spring-webmvc` (0.18.x):** a full Streamable HTTP transport, but it would add a second MCP library next to LangChain4j and a pre-1.0 API. A single controller around the `McpServer` we already use is simpler.
