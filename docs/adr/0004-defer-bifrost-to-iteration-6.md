# 0004. Defer the Bifrost gateway to Iteration 6

## Status

Accepted

## Context

The project is built iteratively: a small agent first, then one MCP server at a time. Bifrost provides per-agent virtual keys, tool allowlists, budgets and gateway authentication. These matter only once several agents and servers exist. Introducing the gateway early adds a separate process and configuration while the agent and MCP servers are still being shaped.

## Decision

Bifrost is introduced in Iteration 6, as the spec already plans. Until then:

- The agent app connects to each MCP server directly through the LangChain4j MCP client.
- Tool allowlists per agent are enforced in code (FR-3).
- Every MCP server URL is set in `application.yml`, so switching to the gateway is a configuration change.
- Token validation stays in the MCP servers, since it remains needed behind the gateway (ADR 0001).

## Consequences

- Early iterations have fewer moving parts.
- The README can show a before-and-after comparison once Bifrost is added.
- Gateway-level behaviors (virtual keys, budgets, Code Mode measurements, the single retry layer in FR-38) are decided and verified in Iteration 6.
