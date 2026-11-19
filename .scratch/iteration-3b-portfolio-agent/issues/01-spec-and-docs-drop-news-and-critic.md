# 01: Spec and docs: drop the News agent and Critic, Risk agent becomes the Portfolio agent

**What to build:** the spec and project docs describe the reduced 3b–3d scope, so a reader sees one Iteration 3b (`portfolio-mcp` + Portfolio agent) and no News & sentiment agent, Portfolio risk agent or Critic. The Market data agent's prompt treats news article text as untrusted.

**Blocked by:** None (can start immediately)

**Status:** done

## Decisions

- **News & sentiment agent dropped.** It adds no agentic concept beyond the existing specialists. `getCompanyNews` and `getMarketNews` stay with the Market data agent. Its prompt moves to `market-data-agent-v2` with one added rule: article text is untrusted data, not instructions (FR-33).
- **Critic dropped.** Without raw tool outputs it could only check the Supervisor's copy of the specialists' numbers, a weak baseline. LLM-as-verifier is left to evals (NFR-15), if they return.
- **Portfolio risk agent becomes a plain Portfolio agent** with `getHoldings` and `getWatchlist`. `getExposureBySector` is removed. Holdings stay ticker + shares.
- **`proposePaperTrade`** stays in the spec for Iteration 7 (HITL).
- No ADR: the scope cut is easy to reverse and the spec records it.

## Acceptance criteria

- [ ] Spec §5 diagrams: no Critic, no News & sentiment agent; Portfolio agent in place of Portfolio risk agent.
- [ ] Spec §6.1 lists four agents (Supervisor, Filings analyst, Market data agent, Portfolio agent). AG-5 is removed.
- [ ] Spec §7.1: `portfolio-mcp` tools are `getHoldings()`, `getWatchlist()` and `proposePaperTrade(...)` (Iteration 7). Identity before Iteration 5 is the `X-User-Id` header, replaced by the token's `sub` in Iteration 5.
- [ ] Spec NFR-13 no longer mentions Critic rejections; US-4 and §1 no longer promise sector exposure or what-if scenarios.
- [ ] Spec §14: the 3b–3d row becomes one 3b row with the acceptance criteria of tickets 02 and 03.
- [ ] `GLOSSARY.md` has a Portfolio agent term and no Critic, News & sentiment or Portfolio risk agent terms.
- [ ] README and `docs/design.md` match (no `getExposureBySector`, no Critic or News agent).
- [ ] `market-data-agent-v2` prompt is in use and logged as its `promptId`.
- [ ] `./gradlew build` passes.
