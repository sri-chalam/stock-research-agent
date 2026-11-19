# 02: `portfolio-mcp` serves `getHoldings` and `getWatchlist` scoped by `X-User-Id`

**What to build:** a new `portfolio-mcp` server that returns a user's holdings and watchlist over MCP. The user comes from the `X-User-Id` request header, never from a tool argument. Two seeded users, `alice` and `bob`, see only their own data.

**Blocked by:** None (can start immediately)

**Status:** done

## Decisions

- **Module:** `portfolio-mcp` on port 8084, same package layout as `filings-mcp`, under `info.saagar.stockagent.portfolio`. `McpController` is copied, not shared (ADR 0010).
- **Tools:** `getHoldings()` returns ticker and shares; `getWatchlist()` returns tickers. No arguments; both read-only. No `getExposureBySector`, no `proposePaperTrade`.
- **Storage:** Postgres (the Compose container), tables `holding(user_id, ticker, shares)` and `watchlist_item(user_id, ticker)`, created and seeded by Spring's `schema.sql` / `data.sql` on startup, read with `JdbcClient`. No JPA, no Flyway. Own DB role, following the `filings-mcp` pattern.
- **Seed:** `alice` and `bob` with different holdings (at most 3 tickers each, so the Market data agent can quote all of them within `max-steps: 4`) and different watchlists. Tickers are US large caps; no recorded licensed data.
- **Identity:** every query is filtered by the `X-User-Id` header value. A missing header returns a model-readable tool error: "No user identified; send X-User-Id". No auth until Iteration 5, where the token's `sub` replaces the header.
- **Logging:** structured, with `correlationId` from the existing headers and `userId` in the MDC (synthetic ID, not PII).

## Acceptance criteria

- [ ] `tools/list` returns exactly `getHoldings` and `getWatchlist`.
- [ ] `tools/call getHoldings` with `X-User-Id: alice` returns only alice's rows; with `bob`, only bob's.
- [ ] `tools/call getWatchlist` behaves the same way.
- [ ] Without `X-User-Id`, both tools return the "No user identified" error.
- [ ] Restarting `portfolio-mcp` does not duplicate seed rows.
- [ ] `docker compose up` runs `portfolio-mcp` alongside the other apps.
- [ ] Tests cover user scoping and the missing-header error, using a real or in-memory store at the repository boundary (no `@Mock` on business logic).
- [ ] `./gradlew build` passes.
