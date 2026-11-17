# 04: Learning notes: semantic cache and the Haiku vs Sonnet Supervisor comparison

**What to build:** `docs/learning-notes.md` gains a short "Semantic cache" section and a model-choice section that can be reviewed in a few minutes, including measured numbers from this iteration.

**Blocked by:** 03

**Status:** ready-for-agent

## Decisions

- Notes stay concise: one or two lines per point, tables for numbers.
- The Haiku vs Sonnet comparison is manual and one-off: all other agents and the classifier stay on Haiku throughout.

## Haiku vs Sonnet comparison (manual)

- Agent: the Supervisor only.
- Run the three Iteration 3 prompts (filings only, market data only, both) once with the Supervisor on `claude-haiku-5-5` and once on `claude-sonnet-5-5`, switched in config only, then switch back to Haiku.
- Run with `semantic-cache.enabled: false` so cache hits don't skew the numbers.
- Record from `answerProvenance`: `modelByAgent` (proves which model ran), tokens per agent, `completeExecDurationTimeMs`, cost at list prices, and a one-line quality note per prompt (routed correctly? answer complete?).

## Acceptance criteria

- [x] Semantic cache section covers, briefly:
  - The request flow: classify → skip for `REAL_TIME` → embed → lookup (ticker filter, TTL, threshold) → hit returns stored answer, miss runs the Supervisor → store rules.
  - What the HNSW index does: approximate nearest-neighbour search instead of scanning every row, trading exact recall for speed.
  - Cosine distance (`<=>`) vs cosine similarity (`1 - distance`).
  - Why the ticker filter exists: "AAPL margins" and "MSFT margins" can embed above the threshold.
  - Why portfolio answers are never stored instead of keying the cache by user.
  - The threshold calibration scores (should-hit and should-miss pairs) and the chosen threshold.
  - What a hit still costs: the classifier call and one embedding.
  - Production upgrades deliberately skipped: per-class TTLs, invalidation on a new filing, intent routing.
- [x] Model choice section covers:
  - The app never picks a model at runtime: each agent's model is fixed in config, and every agent runs on Haiku.
  - Under what condition the Supervisor would move to Sonnet: only if the comparison shows Haiku routing to the wrong specialist or merging answers badly, weighed against Sonnet's extra cost and latency.
  - The comparison table and the resulting decision line ("Supervisor stays on Haiku because …").
- [x] No other section of the learning notes is reorganized.
