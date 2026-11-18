# 01: Input guardrails

**What to build:** prompts that try to inject instructions or ask for buy/sell advice ("Should I buy NVDA?") get a polite refusal before the cache or any agent runs, and overlong messages are rejected.

**Blocked by:** Iteration 5b ticket 01

**Status:** ready-for-agent

## Decisions

- The existing prompt classifier gains two booleans, `injectionSuspected` and `adviceRequest`; no extra LLM call.
- Either flag → fixed refusal explaining what the assistant can do; no cache lookup, no agents, nothing stored; `cached: false`, empty `toolsCalled`.
- Log `guardrailBlocked` with reason `INJECTION` or `ADVICE`; the block shows in `answerProvenance`.
- Classifier failure → **fail open**: agents run, `guardrailSkipped` is logged (caching still falls back to `REAL_TIME`).
- Length limit on `ChatRequest.message` → 400.

## Acceptance criteria

- [ ] JUnit (fake classifier): `injectionSuspected` → refusal; `adviceRequest` → refusal; in both, no agent runs, no cache lookup, nothing stored.
- [ ] JUnit (fake model): classifier failure → agents run and `guardrailSkipped` is logged.
- [ ] A message over the length limit → 400.
- [ ] `./gradlew build` passes.
