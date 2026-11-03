# Glossary

**Finnhub**: Third-party market data API. It supplies real-time US stock quotes, company news, company profiles and the earnings calendar to `market-data-mcp`. The free tier is US equities only (about 60 requests/min) and for personal use, with no redistribution. Its API key stays inside the MCP server and never reaches the agent or the LLM.

**Macro data**: Economy-wide indicators rather than company-specific figures, such as CPI (consumer price index, a measure of inflation), the federal funds rate and the 10Y yield (10-year US Treasury yield). It comes from FRED through `macro-mcp` and is used mainly by the Portfolio risk agent for what-if scenarios, such as "what happens if rates rise 1%?".

**MCP server**: A Java service that exposes tools to agents over MCP; agents never call data providers directly. There are four, one per domain:
- `market-data-mcp`: quotes, news, profiles, earnings, financials (Finnhub).
- `filings-mcp`: SEC filings, XBRL facts, filings search (SEC EDGAR).
- `macro-mcp`: macro data series (FRED).
- `portfolio-mcp`: holdings, watchlist, sector exposure, paper-trade proposals (application Postgres).

**Paper trading**: Simulated buying and selling of US equities against a virtual portfolio, priced at live market quotes. No brokerage integration and no real money. The agent can only *propose* a paper trade; it executes after explicit user approval (see PendingAction).
_Avoid_: "real trading", "order execution"

**PendingAction**: A stored proposal, such as a paper trade or an alert-rule change, that waits for user approval. It has a status (starting at `PENDING`), expires after a configurable time (default 15 minutes), and is executed by deterministic code, never by the LLM. Its final status (`APPROVED`, `REJECTED` or `EXPIRED`) is the only record of the outcome; there is no separate audit log.

**SEC filing**: An official report a US public company files with the SEC, retrieved from SEC EDGAR. Answers cite the filing, section and period. The project uses three forms:
- **10-K**: annual report, including Risk Factors and MD&A.
- **10-Q**: quarterly report.
- **8-K**: current report of a significant event, such as an earnings release, acquisition or executive change.

**XBRL**: Structured financial data tagged inside SEC filings, read through the EDGAR `companyfacts` API. Numbers in answers prefer XBRL over text extracted from filings. It holds exact reported values, such as `Revenues`, `NetIncomeLoss` and `GrossProfit`, each with its period and source filing. It does not hold narrative text like Risk Factors or MD&A, which come from RAG.
