# 02: Per-agent token counts in `answerProvenance`

**What to build:** after a Supervisor run, the `answerProvenance` log line shows how many tokens each agent used, so a developer can see what the Supervisor's extra LLM calls cost compared with the specialist agents.

**Blocked by:** 01

**Status:** ready-for-agent

## Decisions

- A `ChatModelListener` on each agent's chat model adds up input and output tokens for the current run.
- `answerProvenance` gains `tokensByAgent` (input and output tokens for `supervisor`, `filings` and `market-data`), total input and output tokens, and keeps `completeExecDurationTimeMs`.
- The Supervisor's planner and `SUMMARY` calls count under `supervisor`.
- No dollar cost. That belongs to the Iteration 4 dashboard (NFR-9).
- **Tests:** unit tests for the provenance collection only. Given the listener events of one run (agent invoked, tool executed, tokens used), it builds the right `agentsUsed`, `tokensByAgent` and totals. No test drives the real `supervisorBuilder` with a scripted fake model: that would depend on the beta planner's internal format.

## Acceptance criteria

- [ ] `./gradlew build` passes, including the new provenance unit tests.
- [ ] For each of the three curl prompts from ticket 01, `answerProvenance` shows `tokensByAgent`, total tokens and duration, and the per-agent counts add up to the totals.
- [ ] The token and latency numbers for the three prompts are recorded in this ticket's Comments, for ticket 04.

## Comments
