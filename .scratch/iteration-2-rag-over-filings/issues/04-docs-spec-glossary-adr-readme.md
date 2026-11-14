# 04: Update spec, glossary, ADR 0011, README and AGENTS.md for Iteration 2

**What to build:** Bring the docs in line with what Iteration 2 decided and built. A reader of the spec, glossary, ADRs, README and AGENTS.md then sees the same RAG design the code implements, with the reasons for dropping hybrid search, reranking and `getFilingSection`.

**Blocked by:** 03

**Status:** ready-for-agent

## Spec (`docs/requirements/stock-research-agent-spec.md`)

- **§7.1, filings-mcp row:** Iteration 2 adds `searchFilings(ticker, query)` only. Remove `getFilingSection` (a whole section can run 20–50K tokens, which conflicts with FR-16 and MCP-2).
- **MCP-2:** add the `searchFilings` limit: the top 5 chunks with metadata and score, and no raw HTML.
- **FR-13:**
  - Scope: the latest 10-K and the latest 10-Q for the configured tickers (5 initially).
  - Sections: 10-K Items 1A and 7; 10-Q Part I Item 2 and Part II Item 1A.
  - Chunking: about 400 tokens (1600 characters with 200 characters of overlap), because the embedding model's input limit is 512 tokens.
  - Embeddings: in-process (bge-small, 384 dimensions).
  - Ingestion: runs in the background on filings-mcp startup and skips accessions already stored.
- **FR-14:** vector top-k only (k = 5). Drop hybrid search and reranking, with a pointer to ADR 0011.
- **§4 tech stack:** add the in-process embedding model to the vector-store or AI-framework row.
- **NFR-14:** Iteration 2 has a retrieval eval (recall@5, about 10–15 cases). The answer-level golden set (~40 cases) starts in Iteration 3.
- **§14, Iteration 2 row:** update scope and acceptance criteria to match tickets 01–03.
- **§8.11 API table:** no change. `/api/filings/chat` stays until Iteration 3.

## Glossary (`GLOSSARY.md`)

Follow the existing format, with no implementation details:

- **Filing section**: one of the narrative parts of a SEC filing that the assistant searches. The four used are 10-K Risk Factors (Item 1A), 10-K MD&A (Item 7), 10-Q MD&A (Part I Item 2) and 10-Q Risk Factors (Part II Item 1A). Answers cite the section.
- **Ingestion**: loading the latest 10-K and 10-Q for the indexed tickers into the searchable filings store, ahead of any question. It isn't triggered by a question, and searching never fetches filings live.
- Update **SEC filing** and **XBRL** if their wording now conflicts (e.g. "from RAG").

## ADR 0011 (`docs/adr/`)

Title along the lines of "RAG in filings-mcp: in-process embeddings, vector-only retrieval". Follow the format of the existing ADRs. Cover:

- Retrieval is exposed as the `searchFilings` MCP tool inside filings-mcp, instead of a LangChain4j `RetrievalAugmentor` in agent-app. This keeps the domain boundary (ADR 0002), lets the agent decide when to search, and sets up Bifrost tool allowlists.
- In-process ONNX embeddings instead of Voyage/OpenAI: no new vendor, key or cost. You can swap the model through the `EmbeddingModel` interface plus re-ingestion.
- Hybrid search and reranking are dropped. The reranker's model files (~90 MB) need packaging, and neither piece is core to the learning goals. Revisit if the recall@5 eval shows misses.
- `getFilingSection` is dropped (context size).
- Consequences: retrieval quality is lower than a hybrid or reranked pipeline, so exact-term queries may miss.

## README and AGENTS.md

- **README:** an Iteration 2 section covering the RAG flow (ingestion offline, retrieval as a tool), how to run the eval, and the recall@5 baseline from ticket 03.
- **AGENTS.md:**
  - Add Postgres + pgvector to the stack and Compose notes.
  - Update the filings-mcp package table with the new ingestion and search classes.
  - Add the eval Gradle task to §4 build commands.
  - Note the schema-per-app rule.
- **`docs/learning-notes.md`:** add Iteration 2 notes if this iteration produced lessons worth keeping (e.g. the 512-token embedding limit).

## Acceptance criteria

- [ ] The spec no longer mentions hybrid search, reranking or `getFilingSection` as Iteration 2 requirements.
- [ ] The glossary has Filing section and Ingestion, with no implementation details.
- [ ] ADR 0011 exists and is referenced from FR-14.
- [ ] The README shows the recall@5 baseline.
- [ ] AGENTS.md matches the code as built (packages, Compose, commands).

## Comments
