# 01: One ticker's 10-K Risk Factors are searchable through the Filings analyst

**What to build:** The Iteration 2 tracer bullet. `POST /api/filings/chat` "What supply-chain risks did Apple's last 10-K describe?" returns an answer grounded in chunks of AAPL's latest 10-K Item 1A (Risk Factors), retrieved by the new `filings-mcp` tool `searchFilings`, with an inline citation for each fact. The path covers every layer: a Postgres + pgvector container, an in-process embedding model, background ingestion on startup, the `searchFilings` MCP tool, and the Filings analyst's allowlist and prompt.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Guiding rule

AGENTS.md §1 "Project intent": RAG is the lesson, EDGAR is not. Keep parsing and provider handling thin. Unusual filing layouts are accepted gaps, not cases to handle.

## Decisions (agreed in the Iteration 2 grilling session)

### Where RAG lives

- Everything RAG-related (ingestion, vector store, retrieval) lives in `filings-mcp`. agent-app sees only an MCP tool and has no EDGAR or pgvector code.
- RAG is **retrieval as a tool**. The agent decides when to call `searchFilings`. Don't use LangChain4j's `RetrievalAugmentor`/`ContentRetriever` on the agent.

### Postgres

- One `pgvector/pgvector` container in Docker Compose for the whole project, with **one schema per app**. This ticket creates only the `filings` schema and a `filings-mcp` database user that can access only that schema. Later apps (portfolio-mcp, agent-app cache) get their own schemas.
- Connection settings and credentials come from env vars with local-dev defaults in Compose. Don't hard-code credentials.

### Embeddings

- An in-process ONNX model through LangChain4j: bge-small-en-v1.5 quantized (384 dimensions). No API key and no network call.
- The model reads **at most 512 tokens** per input and silently truncates the rest. The chunk size below stays under that limit.
- Expose it as an `EmbeddingModel` bean so a hosted model could be swapped in later through config alone.

### Vector store

- LangChain4j `PgVectorEmbeddingStore` with `createTable(true)` and `dimension(384)`. It owns the table. There's no Flyway and no hand-written SQL for the table. Confirm how to place the table in the `filings` schema (a schema-qualified table name, or the user's `search_path`), and pick the simpler option that works.
- Metadata per chunk: `ticker`, `form`, `fiscalPeriod` (the filing's report period), `section`, `accessionNumber`, `url`.
- Schema changes are handled by dropping the table and re-ingesting. The database is disposable (DEP-6).

### Ingestion (this ticket: AAPL, latest 10-K, Item 1A only)

- Ingestion starts on `filings-mcp` startup from an `ApplicationRunner`, **in the background on a virtual thread**, so the server answers `listFilings`/`getCompanyFacts` immediately. Copy the MDC into the thread if it logs with request context.
- It finds the latest 10-K through the existing EDGAR client and downloads its primary document. The document lives on `www.sec.gov/Archives`, not `data.sec.gov`. Send the same `SEC_USER_AGENT`, and keep the client behind the `EdgarClient` interface.
- HTML → text uses LangChain4j's jsoup `HtmlToTextDocumentTransformer`.
- Section extraction uses heading regexes to find Item 1A and take the text up to the next Item heading. If the heading also appears in the table of contents, take the occurrence followed by the most text.
- Chunking: `DocumentSplitters.recursive(1600, 200)`, in **characters** (about 400 tokens with about 50 tokens of overlap). Put both values in config properties.
- Log ingestion as structured events (AGENTS.md §6): started, per filing (ticker, form, accession, section, chunk count, durations), completed (total chunks, `completeExecDurationTimeMs`), and failures at `error`.

### Tool `searchFilings(ticker, query)`

- Parameters are `ticker` and `query` only. There are no form, section or score filters.
- It embeds the query, searches with a metadata filter `ticker = <ticker>`, and returns the **top 5** by similarity (`k` is a config property). There's no minimum score: the LLM sees the scores.
- Each result is a record with the chunk `text`, `form`, `fiscalPeriod`, `section`, `accessionNumber`, `url` and `score`. The output contains no raw HTML.
- It **never calls EDGAR**. An unconfigured ticker gets the same error as the existing tools.
- The `@Tool` description says it searches the narrative sections of recent 10-K and 10-Q filings and returns cited chunks.

### Filings analyst (agent-app)

- Add `searchFilings` to `stock-agent.agents.filings.allowed-tools`. The Market data agent's allowlist doesn't change.
- System prompt: use `searchFilings` for narrative questions. Cite every fact inline in a fixed format, e.g. `[10-K FY2025, Item 1A, 0000320193-25-000079](<sec.gov url>)`. Answer only from tool output.
- The answer is free text. Structured output (FR-30) is out of scope.

## Tests (synthetic data only; no live EDGAR or Anthropic)

- **Section extraction:** a small synthetic HTML filing with a table of contents and the Item headings. Item 1A's body is extracted, not the table-of-contents line.
- **`searchFilings`:** wire the real tool with LangChain4j's `InMemoryEmbeddingStore` and the real embedding model as the fakes at the store interface. Results are filtered by ticker, at most 5 are returned, and every metadata field is present.
- **Ingestion:** use a fake `EdgarClient` that returns the synthetic filing. Chunks land in the in-memory store with the right metadata, and no chunk exceeds 1600 characters.
- **agent-app:** extend the allowlist test so the Filings analyst sees `searchFilings` and the Market data agent doesn't.

## Acceptance criteria

- [ ] `./gradlew build` passes.
- [ ] `docker compose up` starts Postgres with pgvector, and filings-mcp logs a completed ingestion for AAPL's latest 10-K Item 1A.
- [ ] `curl -X POST http://127.0.0.1:8080/api/filings/chat -H 'Content-Type: application/json' -d '{"message":"What supply-chain risks did Apple'\''s last 10-K describe?"}'` returns an answer with inline citations naming 10-K, Item 1A, the accession and a sec.gov link, with `searchFilings` in `toolsCalled` (manual check).
- [ ] `searchFilings` makes no EDGAR call (verified by the ingestion and tool tests).
- [ ] The Market data agent can't see `searchFilings`.
- [ ] The `SEC_USER_AGENT` value and the database password appear in no log line.

## Comments
