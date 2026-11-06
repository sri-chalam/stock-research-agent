# Open Decisions

Questions the spec leaves unresolved. When one is settled, record it as an ADR in [adr/](adr/), then change its status here and link the ADR.

| ID | Question | Why it matters | Needed by | Status |
|---|---|---|---|---|
| OD-1 | Is WorkOS sign-up open or invite-only? | Personal-use tool with shared API keys (spec §2); open sign-up lets anyone spend them. Invite-only is the safer default. | Iteration 5 | Open |
| OD-2 | Does the app create a user's rows lazily on first request, keyed by the token's `sub`? | The spec has no user table or registration endpoint. | Iteration 5 | Open |
| OD-3 | How do a user's starting holdings get in? | Holdings change only through approved paper trades, so there is no way to seed a first position or cash balance. | Iteration 7 | Open |
| OD-4 | Does the UI get a watchlist editor? | `PUT /api/watchlist` exists, but UI-3 does not list a watchlist feature. | Iteration 5 | Open |
| OD-5 | Which component handles `PUT /api/watchlist`: the agent app directly, or through `portfolio-mcp`? | `portfolio-mcp` has no watchlist-write tool, and spec §8.11 does not say. | Iteration 5 | Open |
| OD-6 | Is the single-page GUI split into several static `.js` files? | One hand-written `index.html` holding login, chat, trace, actions, brief and watchlist gets hard to maintain. Static files keep the no-npm rule. | Iteration 5 | Open |
| OD-7 | Which OAuth scope does each agent app endpoint other than `/api/chat` need (`/api/portfolio`, `/api/watchlist`, `/api/actions/*`, `/api/briefs/latest`, `/api/feedback`)? | SEC-8 lists six scopes, but the spec does not map them to these endpoints. See [design.md](design.md#oauth-scopes). | Iteration 5 | Open |
