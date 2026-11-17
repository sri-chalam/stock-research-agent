# 03: Semantic cache: hit, miss and store rules

**What to build:** a `CACHEABLE` research question that is similar enough to an earlier one, names exactly the same tickers and hasn't expired is answered from the semantic cache, with no agents run and `cached: true` on the response. Answers are stored only when they are safe to reuse.

**Blocked by:** 02

**Status:** ready-for-agent

## Decisions

### Storage

- Own table, explicit SQL through `JdbcClient` (not LangChain4j's `PgVectorEmbeddingStore`). One row per stored answer: question, embedding (384 dimensions), sorted ticker set, freshness class, answer, tokens the original run used, expiry time.
- Own `sem_cache` schema and role in the project's single Postgres, created by the Postgres init scripts like the other apps' schemas. Password from `SEM_CACHE_DB_PASSWORD`.
- `agent-app` creates its table on every start with an idempotent schema script, and fails at startup when Postgres is unreachable, like `portfolio-mcp`. Compose starts it after Postgres.
- An HNSW index with cosine ops on the embedding column: approximate nearest-neighbour search instead of scanning every row.
- Behind a `SemanticCacheStore` interface: a JDBC implementation and an in-memory fake for unit tests.

### Embedding

- In-process quantized bge-small-en-v1.5 (ONNX), the same model `filings-mcp` uses. No API key, no network call.
- `agent-app`'s image moves to `amazonlinux:2023` because ONNX Runtime needs glibc (ADR 0012, amended in ticket 01).
- The question is normalized (trimmed, lowercased) before embedding.

### Lookup

- Skipped entirely for `REAL_TIME` or a failed classification (ticket 02), and when `semantic-cache.enabled` is `false` (`skipReason: disabled`).
- Filters: exact sorted ticker set (an empty set matches only an empty set) and not expired. Takes the nearest row and compares its cosine similarity (`1 - cosine distance`) with `semantic-cache.similarity-threshold`.
- One TTL for every row: `semantic-cache.ttl: 1h`. Expired rows are never deleted.

### Store

An answer is stored only when **all** hold:
1. Freshness class is `CACHEABLE`.
2. No portfolio tool ran (`getHoldings`, `getWatchlist`).
3. No tool call in the run failed.
4. The Supervisor returned a non-blank answer without throwing.

Otherwise the store is skipped and logged at info with `storeSkipReason`.

### Threshold calibration

- A JUnit test with the real bge-small model over about 8 hand-written question pairs: about 4 that should hit ("AAPL risk factors?" / "What risks does Apple list in its 10-K?") and about 4 that share a ticker but shouldn't ("AAPL revenue trend" / "AAPL latest news").
- It asserts every should-hit pair scores at or above the configured threshold and every should-miss pair below. The threshold is chosen from the observed gap.

## Log events and response

- `POST /api/chat` response gains `cached: boolean`.
- `semanticCacheLookup` (info): `cacheOutcome` (`HIT` | `MISS` | `SKIPPED`), `freshnessClass`, `cacheSimilarity` (best match, also on a miss; absent when skipped or no candidate), `tickers`, `cacheLookupDurationTimeMs`.
- Store skipped (info): `storeSkipReason`.
- `answerProvenance`: `cacheOutcome`, plus `tokensSaved` (the stored run's total tokens) on a hit. On a hit, `agentCalls` is empty and the only tokens are the classifier's.

## Acceptance criteria

- [ ] "What risks does Apple list in its latest 10-K?" then "What are Apple's 10-K risk factors?": the first returns `cached: false` and logs `MISS`; the second returns `cached: true`, logs `HIT`, runs no agents and logs `tokensSaved` > 0.
- [ ] "Price of Apple" twice returns `cached: false` both times and logs `REAL_TIME` and `SKIPPED`.
- [ ] "AAPL risk factors" then "MSFT risk factors": the second is a `MISS`.
- [ ] "What stocks do I hold?" as `alice` twice: both run agents; the store is skipped with a `storeSkipReason` naming the portfolio tool.
- [ ] With a short TTL set in config, the repeated question is a `MISS` once the TTL passes.
- [ ] A run where a tool call failed is not stored.
- [ ] With `semantic-cache.enabled: false`, every lookup logs `SKIPPED` with `skipReason: disabled`.
- [ ] The threshold calibration test passes with the configured threshold.
- [ ] Unit tests for the lookup and store rules use the in-memory `SemanticCacheStore` fake.
- [ ] `docker compose up` runs all four apps, with `agent-app` on `amazonlinux:2023` and its table created on start; a restart doesn't fail on the existing table.
- [ ] `SEM_CACHE_DB_PASSWORD` appears in no log line.
- [ ] `./gradlew build` passes.
