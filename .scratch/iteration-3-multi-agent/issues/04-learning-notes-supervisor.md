# 04: Learning notes: how the Supervisor works

**What to build:** a "Supervisor" section in the learning notes that a developer can reread to recall how multi-agent orchestration works in this app, based on what the running code actually did.

**Blocked by:** 02

**Status:** done

## Acceptance criteria

- [x] The learning notes have a "Supervisor" section covering:
  - The Supervisor runs a loop: it picks a specialist agent, reads its answer, then picks another or stops. One prompt can reach several specialist agents.
  - Each specialist agent gets the planner's sub-question, not the user's original prompt, and the risk that the planner drops details.
  - How the answers are merged: `SUMMARY` makes one more LLM call that combines every specialist's output; `LAST` (the default) returns only the last specialist's output; `SCORED` produces both and picks one with another LLM call.
  - A runtime-flow sketch for a combined question, in the style of the existing "Runtime flow of a chat request".
  - The token and latency cost of the Supervisor's extra calls, using the numbers recorded in ticket 02.
- [x] Short points, matching the existing notes' style; design rationale stays out (no ADR was needed).

## Comments

- **2026-11-16, merge strategies differ from the criteria.** The `langchain4j-agentic` `1.23.0-beta33` source (`SupervisorPlanner.result`) shows `SUMMARY` makes no extra LLM call: the recap is the `response` argument of the planner's `done` call, which runs under every strategy. Ticket 02's measurements agree. Only `SCORED` adds a call (`ResponseAgent.scoreResponses`). The notes follow the code. The app uses `SUMMARY`; `LAST` is only the library default.
