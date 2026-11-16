# 01: Supervisor answers `/api/chat` through both specialist agents

**What to build:** `POST /api/chat` sends every prompt to the Supervisor, which delegates to the Filings analyst and the Market data agent and merges their answers. A filings question reaches the Filings analyst, a price question reaches the Market data agent, and a question that needs both reaches both and comes back as one answer. The `answerProvenance` log line shows which specialist agents ran and which tools each one called. The temporary `POST /api/filings/chat` endpoint is removed.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Decisions

- **Every prompt goes through the Supervisor**, quotes included. The quote bypass (FR-5) needs the Iteration 4 classifier.
- **Synchronous endpoint.** `POST /api/chat` keeps returning the answer in its response. Run IDs and SSE wait for Iteration 5.
- **Supervisor:** `AgenticServices.supervisorBuilder()` from `langchain4j-agentic`, response strategy `SUMMARY`.
- **Settings:** a `supervisor` agent entry next to `market-data` and `filings`: Haiku 5.5, `max-steps: 4`, mapped to the builder's maximum number of agent invocations.
- **Prompt:** a versioned `supervisor-v1` prompt, passed as the supervisor context and logged as `promptId` (AG-1). It routes filings questions to the Filings analyst, prices and company data to the Market data agent, and splits combined questions.
- **Specialist agent inputs:** each specialist gets an `@Agent` description and a single `@V("request")` input holding the planner's sub-question, not the user's original prompt.
- **Provenance:** an `AgentListener` collects, per run, the specialist agents called in order and the tools each one called. `answerProvenance` replaces the flat `toolsUsed` with `agentsUsed`. The chat response's tool list says which agent made each call.
- **No memory between requests.** Each request is a fresh run (memory is FR-18, later).
- **Failures:** a specialist's LLM error fails the whole request through `GlobalExceptionHandler`. No partial answers until Iteration 8.
- **No eval suite.** Routing is checked manually (see acceptance criteria).

## Acceptance criteria

- [ ] `./gradlew build` passes.
- [ ] `POST /api/filings/chat` no longer exists.
- [ ] With Compose running, three curl prompts to `POST /api/chat` produce these `agentsUsed` in `answerProvenance`:
  - "List Microsoft's recent 10-K filings": Filings analyst only, calling `listFilings`.
  - "Price of Apple": Market data agent only, calling `getQuote`.
  - "Apple's revenue for the last 3 fiscal years and its current price": both specialists, and one merged answer containing both parts.
- [ ] No specialist calls a tool outside its allowlist.

## Comments
