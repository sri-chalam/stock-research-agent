# 02: Prompt classifier, with `REAL_TIME` skipping the cache, and `modelByAgent`

**What to build:** every `POST /api/chat` prompt is classified before the Supervisor runs, into a freshness class and the tickers it names. The logs show the classification, the cache decision (always `SKIPPED` until ticket 03 adds the cache) and which model each agent used. A classifier failure never fails the request.

**Blocked by:** None (can start immediately)

**Status:** done

## Decisions

- The prompt classifier is a single LLM call, not an agent: a LangChain4j AI service with no tools and no memory, returning a record through structured output:
  `Classification(FreshnessClass freshness, List<String> tickers)`, with `FreshnessClass` = `REAL_TIME` | `CACHEABLE`.
- Its model (`claude-haiku-5-5`) and prompt ID (`classifier-v1`) are configured in its own `application.yml` entry, like the agents.
- The classifier maps company names to tickers ("Apple" → `AAPL`). Tickers are uppercased and validated against `^[A-Z]{1,5}$`.
- **Failure skips the cache.** If the classifier call throws, or any ticker is invalid, the request is treated as `REAL_TIME`: no cache, Supervisor runs as usual. No retry (Iteration 8).
- Classifier tokens count toward the run's totals in `answerProvenance`, so the cost of every request (including future cache hits) is honest.
- `modelByAgent` is logged for every agent and the classifier, taken from the model name in the response metadata the existing chat-model listener already receives, not from config.

## Log events

- `promptClassified` (info): `freshnessClass`, `tickers`, `classifyDurationTimeMs`.
- `semanticCacheSkipped` (warn): when classification fails or a ticker is invalid; `skipReason`, `exceptionType`, `exceptionMessage`.
- `semanticCacheLookup` (info): `cacheOutcome` (`SKIPPED` in this ticket), `freshnessClass`, `tickers`. Ticket 03 adds `HIT`/`MISS`, `cacheSimilarity` and `cacheLookupDurationTimeMs`.
- `answerProvenance` gains `modelByAgent` and `cacheOutcome`.

## Acceptance criteria

- [ ] "Price of Apple" logs `promptClassified` with `REAL_TIME` and `["AAPL"]`.
- [ ] "What risks does Apple list in its latest 10-K?" logs `CACHEABLE` and `["AAPL"]`.
- [ ] "Compare Apple and Microsoft revenue" logs both tickers.
- [ ] A classifier that throws (fake chat model in a test) logs `semanticCacheSkipped` at warn, and the request still returns the Supervisor's answer.
- [ ] An invalid ticker from the classifier (e.g. `APPLE1`) skips the cache the same way.
- [ ] `answerProvenance` shows `modelByAgent` with an entry for the classifier and each agent that ran, all `claude-haiku-5-5`, and the classifier's tokens.
- [ ] Unit tests use a fake chat model at the boundary, not `@Mock` on classes with logic.
- [ ] `./gradlew build` passes.
