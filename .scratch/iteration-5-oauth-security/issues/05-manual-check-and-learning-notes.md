# 05: Manual end-to-end check and learning notes

**What to build:** proof with real WorkOS tokens that each demo user sees only their own portfolio, and learning notes that record what Iteration 5 taught.

**Blocked by:** 02, 04

**Status:** ready-for-human

## Acceptance criteria

- [ ] Bruno, signed in as demo user 1: "What stocks do I hold?" returns user 1's holdings; `answerProvenance` shows the Portfolio agent.
- [ ] Same as demo user 2 returns user 2's holdings.
- [ ] Bruno without a token → 401.
- [ ] Learning notes: the token-passthrough trade-off (Option A versus RFC 8693 token exchange, and why the MCP spec forbids passthrough).
- [ ] Learning notes: the WorkOS findings from ticket 02 (audience and permissions claim) and how the apps were configured for them.
