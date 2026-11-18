# Design

Design details that refine the [requirements spec](requirements/stock-research-agent-spec.md).

## Security

### OAuth scopes

Scope names are defined in spec requirement SEC-8. Every token is validated for signature, issuer, expiry and scope (SEC-3). WorkOS tokens have no `aud` claim, so each app's required scope is its boundary.

| Scope | Granted access | Enforced by |
|---|---|---|
| `agent.chat` | Submit prompts: `POST /api/chat` | Agent app |
| `portfolio.read` | Read holdings and watchlist: `getHoldings`, `getWatchlist` | `portfolio-mcp` |
| `portfolio.write` | Propose paper trades: `proposePaperTrade` (creates a `PENDING` action only). Iteration 7; not enforced in Iteration 5 | `portfolio-mcp` |
| `marketdata.read` | All `market-data-mcp` tools | `market-data-mcp` |
| `filings.read` | All `filings-mcp` tools | `filings-mcp` |

Scopes for the other agent app endpoints are undecided: see [OD-7](open-decisions.md).
