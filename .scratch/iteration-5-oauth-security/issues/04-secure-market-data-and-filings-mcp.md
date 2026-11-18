# 04: Secure `market-data-mcp` and `filings-mcp`

**What to build:** the market-data and filings MCP servers accept only valid user tokens with their scope, so every tool call in the system is authenticated, and "How are my holdings doing today?" still works end to end.

**Blocked by:** 03

**Status:** ready-for-agent

## Acceptance criteria

- [ ] `market-data-mcp` is a resource server requiring `marketdata.read`; `filings-mcp` requires `filings.read`. Standard properties only.
- [ ] JUnit (test JWTs) on each: missing, expired or wrong-issuer token → 401; missing scope → 403.
- [ ] "How are my holdings doing today?" runs end to end: `answerProvenance` shows the Portfolio agent, then the Market data agent with `getQuote` per held ticker.
- [ ] No log line in any app contains the token.
- [ ] `docker compose up` runs all four apps with the WorkOS env vars (issuer, demo-user `sub` values).
- [ ] `./gradlew build` passes.
