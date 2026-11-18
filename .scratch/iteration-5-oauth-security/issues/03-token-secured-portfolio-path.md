# 03: Token-secured portfolio path (tracer bullet)

**What to build:** a signed-in user asks "What stocks do I hold?" with a bearer token and gets their own holdings: agent-app validates the token, forwards it on every MCP call, and `portfolio-mcp` validates it and reads the user from `sub`. `X-User-Id` is gone.

**Blocked by:** 01

**Status:** ready-for-agent

## Notes

- agent-app and `portfolio-mcp` become Spring Security OAuth2 resource servers configured only through standard properties (`issuer-uri`, audiences, authorities claim) (SEC-2).
- The token travels in code, never in prompt text (SEC-4): the per-request MCP header supplier that today adds `X-User-Id` for `portfolio-mcp` is replaced by one that adds `Authorization: Bearer …`, used by all three MCP clients (Option A).
- Demo portfolios are seeded for `sub` values from env vars; restarts do not duplicate seed rows.

## Acceptance criteria

- [ ] JUnit (test JWTs), agent-app: missing, expired or wrong-audience token → 401; token without `agent.chat` → 403.
- [ ] JUnit (test JWTs), `portfolio-mcp`: missing, expired or wrong-audience token → 401; token without `portfolio.read` → 403.
- [ ] JUnit, `portfolio-mcp`: the user comes from `sub`; a token for user 1 never returns user 2's holdings or watchlist.
- [ ] `X-User-Id`, `McpUserHeaders` and the "No user identified" path are removed.
- [ ] No log line contains the token; no prompt text contains the token or `sub` (SEC-4/5).
- [ ] One request's `correlationId` still appears in agent-app and `portfolio-mcp` logs.
- [ ] `./gradlew build` passes.
