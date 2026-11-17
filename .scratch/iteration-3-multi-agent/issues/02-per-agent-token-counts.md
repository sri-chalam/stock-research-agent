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

- **2026-11-16, measurements for ticket 04.** One run of each ticket 01 curl prompt, all agents on `claude-haiku-5-5`, Compose stack. Tokens are input / output. Supervisor tokens are the planner calls, including the final `done` call that carries the `SUMMARY` answer (`SUMMARY` makes no separate LLM call). Single runs, so treat these as rough numbers.

  | Prompt | Agents and tools | `supervisor` | `filings` | `market-data` | Total | Duration |
  |---|---|---|---|---|---|---|
  | "List Microsoft's recent 10-K filings" | filings → `listFilings` | 2,501 / 600 | 4,084 / 593 | — | 6,585 / 1,193 | 6,061 ms |
  | "Price of Apple" | market-data → `getQuote` | 2,070 / 337 | — | 3,661 / 160 | 5,731 / 497 | 5,783 ms |
  | "Apple's revenue for the last 3 fiscal years and its current price" | filings → `getCompanyFacts`, then market-data → `getQuote` | 3,886 / 528 | 5,068 / 357 | 3,669 / 158 | 12,623 / 1,043 | 9,657 ms |

  The Supervisor accounts for 31–38% of each run's input tokens. `tokensByAgent` lists only agents that called their model in the run, so an absent agent means zero.
- **2026-11-16, how tokens reach the right run.** Each agent's chat model gets a `ChatModelListener` from `AgentProvenanceListener.tokenListener(agentName)`. The listener reads the run's `AgenticScope` from `LangChain4jManaged.current(...)`, the thread-local that `langchain4j-agentic` sets around every planner and specialist LLM call; chat model listeners run on the same thread. `LangChain4jManaged` is marked `@Internal` in the beta module, so an upgrade could break this silently: a run would log zero tokens for an agent. The live curl check above is the only verification, as this ticket rules out a test through the real `supervisorBuilder`.
