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

- [x] `./gradlew build` passes.
- [x] `docker compose up` starts Postgres with pgvector, and filings-mcp logs a completed ingestion for AAPL's latest 10-K Item 1A.
- [x] `curl -X POST http://127.0.0.1:8080/api/filings/chat -H 'Content-Type: application/json' -d '{"message":"What supply-chain risks did Apple'\''s last 10-K describe?"}'` returns an answer with inline citations naming 10-K, Item 1A, the accession and a sec.gov link, with `searchFilings` in `toolsCalled` (manual check).
- [x] `searchFilings` makes no EDGAR call (verified by the ingestion and tool tests).
- [x] The Market data agent can't see `searchFilings`.
- [x] The `SEC_USER_AGENT` value and the database password appear in no log line.

## Comments

- 2026-11-13: Implemented, not committed yet (pending review). Verified through `docker compose up --build`: filings-mcp logged `filingIngested` for AAPL 10-K `0000320193-26-000026` (fiscal period 2026-09-26), Item 1A, 86,410 characters into 68 chunks (largest 1,599 characters), in 3.8 s. The table is `filings.filing_chunks`, owned by `filings_mcp`, which gets "permission denied" when it tries to create a table in `public`. The acceptance curl returned an answer with `[10-K FY2026, Item 1A, 0000320193-26-000026](https://www.sec.gov/...)` citations and `toolsCalled` = `searchFilings(AAPL, "supply chain risks")`. The `SEC_USER_AGENT` value and both database passwords appear in none of the 185 Compose log lines. Decisions taken during implementation:
  - Table placement: a schema-qualified table name (`filings.filing_chunks`). PgVectorEmbeddingStore supports it, so no `search_path` change is needed. The init script creates the `vector` extension, and the store runs with `skipCreateVectorExtension(true)` because the app user may not create extensions.
  - The jsoup transformer adds no line breaks for `div`s, and EDGAR iXBRL puts headings in `div`s. The heading regexes therefore match anywhere in the text, not at line starts. The next-Item regex requires a period (`Item 1B.`) so that cross-references such as "Item 7 of this Form 10-K" don't end the section. Accepted gaps: page footers ("Apple Inc. | 2026 Form 10-K | 19") and missing spaces between `div` paragraphs end up in the chunk text.
  - `filings.rag.vector-store: pgvector | in-memory`. The Spring Boot tests use `in-memory` with an empty `ingest-tickers`, so they need neither Postgres nor EDGAR.
  - A Hikari pool backs the pgvector store, so a `searchFilings` call doesn't open a new connection.
  - Re-ingestion first removes a filing's chunks by `accessionNumber`, so restarts don't create duplicates.
  - The filings-mcp image is now `amazoncorretto:25-al2023-headless`, not Alpine, because ONNX Runtime and the DJL tokenizer need glibc. Its healthcheck uses curl, because the image has no wget.
  - The Filings analyst prompt is now `filings-agent-v2`.
  - Open: with `max-tokens: 1024`, the Filings analyst's answer was cut off mid-sentence. All its citations are repeated inline, so the answer runs long.
