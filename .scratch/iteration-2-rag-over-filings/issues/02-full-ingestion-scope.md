# 02: Ingest four sections from the latest 10-K and 10-Q for five tickers

**What to build:** Widen ingestion from ticket 01's single section to the full Iteration 2 scope. The Filings analyst can then answer narrative questions about risk factors and MD&A, annual and quarterly, for five companies. Restarts skip filings already in the store. A configured but un-ingested ticker gets a clear "not indexed" message instead of empty results.

**Blocked by:** 01

**Status:** ready-for-agent

## Decisions

### Scope

- Tickers come from a configured list (`filings.rag.tickers`), a subset of the existing ticker→CIK map: **AAPL, MSFT, NVDA, AMZN, GOOGL**.
- Each ticker gets the **latest 10-K and the latest 10-Q**, two documents. Older filings are out of scope. When a newer filing appears, the next startup ingests it next to the old one. Nothing deletes superseded chunks.
- Sections:
  - **10-K:** Item 1A (Risk Factors) and Item 7 (MD&A).
  - **10-Q:** Part I Item 2 (MD&A) and Part II Item 1A (Risk Factors).
  - The `section` metadata names the Item, so citations distinguish 10-K Item 7 from 10-Q Part I Item 2.

### Idempotency

- Before downloading a filing, check whether its `accessionNumber` already has chunks in the store, and skip it if so. A restart against an ingested database does no EDGAR downloads and no embedding work.
- Use the simplest presence check `PgVectorEmbeddingStore` allows (e.g. a metadata-filtered search on `accessionNumber`). Don't add a separate bookkeeping table.

### Unusual layouts (accepted gaps)

- If a section heading isn't found, log a `warn` event (ticker, form, accession, section) and skip that section. Don't add special handling. The eval in ticket 03 will show whether a gap matters.
- One ticker's failure (an EDGAR error, say) is logged at `error` and doesn't stop the other tickers.

### "Not indexed"

- `searchFilings` for a ticker that is in the CIK map but has no chunks (not in the RAG list, or ingestion still running) returns a model-readable error: "Filings for <TICKER> are not indexed for search. Indexed tickers: AAPL, MSFT, NVDA, AMZN, GOOGL." Use the configured list.

### Filings analyst prompt

- Tell the agent to use `getCompanyFacts` (XBRL) for numbers and `searchFilings` for narrative (FR-15), and to call `searchFilings` again with a narrower query when the first results don't answer the question.

## Tests (synthetic data only)

- Section extraction for all four sections, from synthetic 10-K and 10-Q HTML.
- Missing section → that section is skipped, the others are ingested, and a warning is logged.
- Idempotency: a second ingestion run with the same fake `EdgarClient` downloads nothing and adds no chunks.
- One failing ticker doesn't stop the others.
- `searchFilings` for an un-ingested configured ticker returns the "not indexed" message.

## Acceptance criteria

- [ ] `./gradlew build` passes.
- [ ] After `docker compose up`, ingestion logs show 10 filings (5 tickers × 2 forms) with chunk counts per section.
- [ ] A restart logs every filing as skipped, with no downloads.
- [ ] "What did NVIDIA's latest 10-Q MD&A say about data-center revenue drivers?" returns an answer citing a 10-Q Part I Item 2 chunk (manual check).
- [ ] "What risk factors did Tesla's 10-K list?" gets the "not indexed" message relayed by the agent (TSLA is in the CIK map but not the RAG list).

## Comments
