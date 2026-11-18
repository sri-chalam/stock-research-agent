# 01: Spec and docs: split Iteration 5, remove SSE, trim guardrails, `X-User-Id` becomes `sub`

**What to build:** the spec and project docs describe Iteration 5 as three iterations (5 OAuth security, 5b minimal GUI, 5c guardrails), with no SSE streaming and only the four kept guardrails, so a reader sees the reduced scope.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Decisions

- **Split.** 5 = OAuth security; 5b = minimal GUI; 5c = guardrails.
- **Token propagation: Option A** (ADR 0001). One WorkOS user token, forwarded unchanged by agent-app on every MCP call; each server validates audience and scope. Trade-off against the MCP spec's no-token-passthrough rule goes in the learning notes. RFC 8693 stays an optional later step.
- **Scopes enforced in 5:** agent-app `agent.chat`; `market-data-mcp` `marketdata.read`; `filings-mcp` `filings.read`; `portfolio-mcp` `portfolio.read`. `portfolio.write` waits for Iteration 7. OD-7 stays open.
- **Identity:** `portfolio-mcp` takes the user from the token's `sub`. Demo users' `sub` values come from env vars, not committed SQL.
- **No unauthenticated mode:** `X-User-Id` and the no-auth local path are removed. JUnit uses test JWTs; manual checks use Bruno (OAuth 2.0, Authorization Code + PKCE).
- **SSE removed:** `POST /api/chat` stays synchronous. `GET /api/chat/{runId}/stream` is dropped, as is NFR-1's time-to-first-token target.
- **GUI (5b):** `authkit-js` + Pico.css from CDN; sign in/out, prompt, plain-text answer with clickable links, trace panel from `toolsCalled` and `cached`; beige/brown/green/red, light mode only. No tokens/cost panel, feedback, pending actions or brief.
- **Guardrails (5c):** classifier flags `injectionSuspected` and `adviceRequest` → refusal; length limit; indirect-injection test; `OutputGuardrail` adds disclaimer and strips non-allowlisted URLs. Fail open when the classifier fails. Dropped: LLM judge for disguised advice, off-topic detection, FR-35 (already in MCP servers).
- No ADR: ADR 0001 already covers Option A; the rest is easy to reverse.

## Acceptance criteria

- [x] Spec §14: row 5 split into 5, 5b and 5c, each with the acceptance criteria of its tickets.
- [x] Spec §4, §5.1, §8.11, §11 (UI-2, UI-3) and NFR-1 no longer mention SSE or the stream endpoint; UI-3 lists only the 5b features.
- [x] Spec §8.9 lists only the four kept guardrails; FR-35 and the LLM-judge and off-topic items are removed or marked as covered elsewhere.
- [x] Spec §7.1 and §9: `portfolio-mcp` identity is the token's `sub`; `X-User-Id` is described as removed in Iteration 5.
- [x] `docs/design.md` scopes table marks `portfolio.write` as Iteration 7.
- [x] `GLOSSARY.md` and README match (no SSE promise, no `X-User-Id` after Iteration 5).
- [x] `./gradlew build` passes.
