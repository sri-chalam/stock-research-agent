# 03: Portfolio agent joins the Supervisor, with the user ID forwarded from `/api/chat`

**What to build:** a signed-in-style user (identified by `X-User-Id` on `POST /api/chat`) can ask about their holdings and watchlist. The Supervisor routes those questions to a new Portfolio agent, and for "how are my holdings doing" it chains the Portfolio agent's tickers into the Market data agent's quotes.

**Blocked by:** 02

**Status:** done

## Decisions

- **Header:** `X-User-Id` is optional on `POST /api/chat`, so market and filings prompts work without it. It is validated with `^[A-Za-z0-9-]{1,64}$`; an invalid value returns 400.
- **Propagation:** agent-app puts the user ID in the MDC and forwards it as `X-User-Id` **only** on MCP calls to `portfolio-mcp`, not to `market-data-mcp` or `filings-mcp`. Never in prompt text (SEC-4).
- **Portfolio agent:** `AiServices` agent named `portfolio`, prompt `portfolio-agent-v1`, Haiku 5.5, settings in `application.yml` like the other specialists, allowlist `getHoldings` and `getWatchlist`.
- **Supervisor:** prompt moves to `supervisor-v2`. It routes holdings and watchlist questions to the Portfolio agent, and for questions about how holdings are doing, calls the Portfolio agent first and then the Market data agent with those tickers. `max-steps` is raised only if the chained question needs it.
- **Provenance:** the Portfolio agent appears in `answerProvenance` with its `promptId`, tools and tokens, like the other specialists.

## Acceptance criteria

- [ ] `X-User-Id: alice`, "What stocks do I hold?": only alice's holdings, via Portfolio agent → `getHoldings`. The same prompt as `bob` returns only bob's.
- [ ] `alice`, "What's on my watchlist?": answered via `getWatchlist`.
- [ ] `alice`, "How are my holdings doing today?": `answerProvenance` shows the Portfolio agent called first, then the Market data agent with `getQuote` per held ticker.
- [ ] No `X-User-Id`, "What do I hold?": the answer relays the "No user identified" error. An invalid header value returns 400.
- [ ] Only the Portfolio agent sees `getHoldings` and `getWatchlist`; it sees no other tools.
- [ ] One request's `correlationId` appears in agent-app and `portfolio-mcp` logs; the user ID does not reach `market-data-mcp` or `filings-mcp`.
- [ ] `docker compose up` runs all four apps and the curls above work against it.
- [ ] `./gradlew build` passes.
