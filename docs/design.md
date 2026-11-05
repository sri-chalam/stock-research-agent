# Design

Design details that refine the [requirements spec](requirements/stock-research-agent-spec.md).

## Security

### OAuth scopes

Scope names are defined in spec requirement SEC-8. Every token is validated for signature, issuer, expiry, audience and scope (SEC-3).

| Scope | Granted access | Enforced by |
|---|---|---|
| `agent.chat` | Submit prompts and read run streams: `POST /api/chat`, `GET /api/chat/{runId}/stream` | Agent app |
| `portfolio.read` | Read holdings, watchlist and sector exposure: `getHoldings`, `getWatchlist`, `getExposureBySector` | `portfolio-mcp` |
| `portfolio.write` | Propose paper trades: `proposePaperTrade` (creates a `PENDING` action only) | `portfolio-mcp` |
| `marketdata.read` | All `market-data-mcp` tools | `market-data-mcp` |
| `filings.read` | All `filings-mcp` tools | `filings-mcp` |
| `macro.read` | All `macro-mcp` tools | `macro-mcp` |

Scopes for the other agent app endpoints are undecided: see [OD-7](open-decisions.md).
