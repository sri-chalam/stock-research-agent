# 04: Learning notes: Portfolio agent and user-scoped tools

**What to build:** a very short section in `docs/learning-notes.md` that a developer can reread to recall how user identity reaches an MCP tool and how the Supervisor chains one specialist's result into another, based on what the running code did.

**Blocked by:** 03

**Status:** ready-for-agent

## Acceptance criteria

- [ ] One section, a handful of bullets at most, no subsections beyond one flow sketch if needed, covering:
  - The user ID path: `/api/chat` header → MDC → MCP request header → `WHERE user_id = ?`. Never in the prompt, and what Iteration 5 changes (token `sub`).
  - The chained question: the planner calls the Portfolio agent, then passes its tickers to the Market data agent, with the token cost seen in ticket 03.
  - Why the News agent and Critic were dropped, in one line each.
- [ ] Matches the existing notes' style; no design rationale beyond those lines.
