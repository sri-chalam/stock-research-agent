# Glossary

**Finnhub**: Third-party market data API. It supplies real-time US stock quotes, company news, company profiles and the earnings calendar to `market-data-mcp`. The free tier is US equities only (about 60 requests/min) and for personal use, with no redistribution. Its API key stays inside the MCP server and never reaches the agent or the LLM.

**Freshness class**: How long an answer stays valid, which decides whether the semantic cache may store it. The intent classifier, a single Haiku call with structured output, assigns one of three values to each prompt:
- `REAL_TIME`: prices and intraday moves. Never cached.
- `DAILY`: news summaries and briefs. Cached until the end of the trading day.
- `STATIC`: filing content and company descriptions. Cached for days or weeks, and cleared when the ticker has a new filing.

**Holdings**: The stocks a user holds in their paper portfolio, as a ticker and the number of shares held (e.g., 50 shares of AAPL). Stored in Postgres and read through `getHoldings()` on `portfolio-mcp`. The Portfolio agent reads them. Not the same as the watchlist, which follows tickers without owning them.

**MCP server**: A Java service that exposes tools to agents over MCP; agents never call data providers directly. There are three, one per domain:
- `market-data-mcp`: quotes, news, profiles, earnings, financials (Finnhub).
- `filings-mcp`: SEC filings, XBRL facts, filings search (SEC EDGAR).
- `portfolio-mcp`: holdings, watchlist, paper-trade proposals (application Postgres).

**Paper portfolio**: A user's simulated portfolio: their holdings, changed only by approved paper trades. Each user has their own, stored in Postgres. `portfolio-mcp` enforces ownership in code, so a user can read and modify only their own, and portfolio answers are never shared across users.
_Avoid_: "account", "brokerage account"

**Paper trading**: Simulated buying and selling of US equities against a virtual portfolio, priced at live market quotes. No brokerage integration and no real money. The agent can only *propose* a paper trade; it executes after explicit user approval (see PendingAction).
_Avoid_: "real trading", "order execution"

**PendingAction**: A stored proposal, such as a paper trade or an alert-rule change, that waits for user approval. It has a status (starting at `PENDING`), expires after a configurable time (default 15 minutes), and is executed by deterministic code, never by the LLM. Its final status (`APPROVED`, `REJECTED` or `EXPIRED`) is the only record of the outcome; there is no separate audit log.

**Portfolio agent**: The specialist agent that reads the user's holdings and watchlist through `getHoldings()` and `getWatchlist()` on `portfolio-mcp`, and sees no other tools. It never takes the user from prompt text: the app passes the user's identity to `portfolio-mcp` in code. For a question such as "how are my holdings doing", the Supervisor calls it first, then passes its tickers to the Market data agent for quotes.
_Avoid_: "Portfolio risk agent"

**Quote**: The latest price of one US stock, with its percent change and the time the price was current (e.g., AAPL at 189.42, down 0.37%). It is real-time data, so an answer containing a quote is never served from the semantic cache. Quotes come from Finnhub through `market-data-mcp`.

**SEC filing**: An official report a US public company files with the SEC, retrieved from SEC EDGAR. Answers cite the filing, section and period. The project uses three forms:
- **10-K**: annual report, including Risk Factors and MD&A.
- **10-Q**: quarterly report.
- **8-K**: current report of a significant event, such as an earnings release, acquisition or executive change.

**Semantic cache**: A store of past answers to research questions, kept in Postgres + pgvector. It returns a stored answer when a new question is similar enough (cosine similarity, default threshold 0.92), so no agent or LLM runs. Matches are filtered by ticker, freshness class and, for portfolio questions, user, so one user never sees another user's answer. Cached answers are marked "cached". `REAL_TIME` answers and trade requests are never cached.

**Specialist agent**: An agent that owns one domain and answers only with that domain's MCP tools, such as the Filings analyst or the Market data agent. It receives a sub-question from the Supervisor, not the user's original prompt.
_Avoid_: "sub-agent", "worker"

**Supervisor**: The agent that receives a research prompt, splits it into sub-questions, sends each to a specialist agent, and merges their answers into one. It calls no tools itself. A question that needs two domains, such as a company's revenue trend and its current price, goes to two specialist agents.

**Watchlist**: A per-user list of tickers to follow. It holds no share quantities, unlike holdings. Stored in Postgres, read through `getWatchlist()` on `portfolio-mcp`, and updated by the user through `PUT /api/watchlist`. The user is identified from the access token, never from prompt text. Independent of holdings: a ticker can be on the watchlist without being held, and a held ticker need not be on the watchlist.

**XBRL**: Structured financial data tagged inside SEC filings, read through the EDGAR `companyfacts` API. Numbers in answers prefer XBRL over text extracted from filings. It holds exact reported values, such as `Revenues`, `NetIncomeLoss` and `GrossProfit`, each with its period and source filing. It does not hold narrative text like Risk Factors or MD&A, which come from RAG.
