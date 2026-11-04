# User Flows

What happens, step by step, for each thing a user does. Spec IDs point to [stock-research-agent-spec.md](requirements/stock-research-agent-spec.md). Where the spec is silent, the step links to [open-decisions.md](open-decisions.md) instead of guessing.

## Sign-in

1. The user opens the single page (`index.html`).
2. The page redirects to WorkOS AuthKit, which handles sign-up and sign-in with Authorization Code + PKCE (SEC-1).
3. WorkOS returns an access token. The page sends it as a Bearer header on every API call.
4. The agent app validates signature, issuer, expiry, audience and scopes (SEC-3). The user is identified from the token, never from prompt text (SEC-4).

Open: who may sign up ([OD-1](open-decisions.md)) and when the app creates the user's rows ([OD-2](open-decisions.md)).

## Watchlist update

1. The user edits the watchlist in the page.
2. The page calls `PUT /api/watchlist` (§8.11).
3. The tickers are stored in Postgres for that user only.
4. Agents read the list through `getWatchlist()` on `portfolio-mcp`. They cannot change it.

Open: whether the page has a watchlist editor ([OD-4](open-decisions.md)) and which component handles the endpoint ([OD-5](open-decisions.md)).

## Quote

1. The user asks for a price, such as "price of Apple".
2. The classifier labels it `QUOTE` with freshness `REAL_TIME` (FR-4).
3. The app calls `getQuote` on `market-data-mcp` directly, with no cache and no multi-agent run (FR-5).
4. The answer returns with its source.

## Research question

See the request-flow diagram in spec §5.2. In short: classify, check the semantic cache for static and daily questions, run the supervisor and specialist agents, have the Critic verify numbers against tool outputs, apply output guardrails, and return the answer with citations and an agent trace.

## Paper trade with approval

1. The user asks for a trade in chat. The classifier labels it `TRADE`.
2. The agent calls `proposePaperTrade(...)`, which creates a `PendingAction` with status `PENDING` and returns its ID (FR-21). Nothing executes yet.
3. The page lists the pending action with Approve and Reject buttons (FR-22).
4. The user approves with `POST /api/actions/{id}/approve`. Deterministic code, not the LLM, updates the holdings.
5. If the user does nothing for the configured time (default 15 minutes), the action becomes `EXPIRED` and cannot be approved (FR-23).

Open: how starting holdings get in ([OD-3](open-decisions.md)).

## Morning brief

1. On weekdays before market open, a scheduled job generates a brief for each user's holdings: overnight news, notable price moves, upcoming earnings, new filings and macro releases (FR-26). It authenticates with a WorkOS M2M token (FR-27).
2. The brief is stored. Each step is idempotent, so a failed run can be retried without duplicates (FR-29).
3. When the user signs in, the page fetches `GET /api/briefs/latest` and shows it in the "Latest morning brief" section (UI-3).
4. The spec has no push channel such as email or a notification. The user sees the brief only once they open the app.
