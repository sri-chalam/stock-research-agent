# 01: Spec and docs: reduce Iteration 4 to the prompt classifier and semantic cache

**What to build:** the spec and project docs describe the reduced Iteration 4, so a reader sees a prompt classifier and a semantic cache, with intent routing, per-class TTLs, the token/cost dashboard and eval-based model routing marked deferred.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Decisions

- **Scope:** Iteration 4 is the prompt classifier plus the semantic cache. No dashboard: cache and token fields go in log events and are queried in Grafana/Loki.
- **No intent enum.** The classifier returns only a freshness class and tickers. The `QUOTE` bypass (FR-5), `TRADE` and `OUT_OF_SCOPE` handling (FR-6) are deferred.
- **Freshness class has two values:** `REAL_TIME` (never cached, cache skipped in code) and `CACHEABLE`. `DAILY` and `STATIC` are dropped.
- **One TTL for every cached answer** (1h, in config). No end-of-trading-day expiry, no invalidation on a new filing, no cleanup of expired rows.
- **No user scoping in the cache key.** An answer from a run that called a portfolio tool is never stored, so one user's portfolio answer can't reach another user.
- **Ticker filter:** a match needs exactly the same ticker set as the stored answer.
- **Similarity threshold** is set from a JUnit calibration test over hand-written question pairs, not from evals. 0.92 is only the starting value.
- **Model routing:** model IDs stay fixed per agent in config, all on Haiku. One manual Supervisor comparison (Haiku vs Sonnet) replaces the eval-backed decision.
- **Storage:** a custom table in its own `sem_cache` schema, queried with explicit SQL, instead of LangChain4j's `PgVectorEmbeddingStore`.
- **Embeddings:** in-process bge-small in `agent-app`, which moves `agent-app` to `amazonlinux:2023` (ONNX Runtime needs glibc). This amends ADR 0012.

## Acceptance criteria

- [ ] Spec FR-4 lists the freshness classes `REAL_TIME` and `CACHEABLE` and no intent enum; FR-5 and FR-6 are marked deferred.
- [ ] Spec FR-8, FR-9 and FR-10 match the decisions above (ticker-set filter, no user scope, portfolio answers never stored, single TTL, threshold from a calibration test).
- [ ] Spec §5.2 request flow and §6.2 classifier row match: no `QUOTE` bypass or trade branch in Iteration 4.
- [ ] Spec §14 Iteration 4 row lists the reduced scope and these acceptance criteria:
  1. A filings question asked twice, reworded the second time: the first is a cache miss, the second returns `cached: true`, runs no agents and logs `tokensSaved` > 0.
  2. "Price of Apple" twice: never cached, logged as `REAL_TIME` and `SKIPPED`.
  3. "AAPL risk factors" then "MSFT risk factors": the second is a miss.
  4. "What stocks do I hold?" as `alice` twice: both run agents; the store is skipped with a reason naming the portfolio tool.
  5. After the TTL, the repeated question is a miss again.
  6. The threshold calibration test passes, and its scores are in the learning notes.
  7. `answerProvenance` shows `modelByAgent`, and the Supervisor Haiku/Sonnet comparison is in the learning notes.
  8. `docker compose up` runs all four apps, with `agent-app` on `amazonlinux:2023`.
- [ ] NFR-9 (dashboard) and NFR-17 (eval-backed model routing) are marked deferred or reworded to the manual comparison.
- [ ] ADR 0012 is amended: `agent-app` runs on `amazonlinux:2023` for the in-process embedding model.
- [ ] The learning-notes line saying the quote bypass arrives in Iteration 4 is corrected.
- [ ] `GLOSSARY.md` "Freshness class" and "Semantic cache" match the decisions above (already edited during grilling; review and keep).
- [ ] README and `docs/design.md` don't contradict the reduced scope.
