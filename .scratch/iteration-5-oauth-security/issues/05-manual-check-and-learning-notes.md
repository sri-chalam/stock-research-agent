# 05: Manual end-to-end check and learning notes

**What to build:** proof with real WorkOS tokens that each demo user sees only their own portfolio, and learning notes that record what Iteration 5 taught.

**Blocked by:** 02, 04

**Status:** ready-for-human

## Acceptance criteria

- [ ] Bruno, signed in as demo user 1: "What stocks do I hold?" returns user 1's holdings; `answerProvenance` shows the Portfolio agent.
- [ ] Same as demo user 2 returns user 2's holdings.
- [ ] Bruno without a token → 401.
- [x] Learning notes: the token-passthrough trade-off (Option A versus RFC 8693 token exchange, and why the MCP spec forbids passthrough).
- [x] Learning notes: the WorkOS findings from ticket 02 (audience and permissions claim) and how the apps were configured for them.

## Comments

Learning notes: `docs/learning-notes.md` → "OAuth (WorkOS AuthKit)". Stale `X-User-Id: alice` headers removed from the Bruno Portfolio and Cache requests.

Manual check steps:

1. `docker compose up` with `WORKOS_ISSUER_URI`, `WORKOS_JWK_SET_URI` and both `WORKOS_DEMO_USER_*_SUB` set in `.env`.
2. Bruno, `local` environment → Collection settings → Auth → Get Access Token → sign in as demo user 1. Run `chat` within 5 minutes (token lifetime). Check the holdings and the `answerProvenance` line (`{service="agent-app"} | json | eventName="answerProvenance"` in Grafana): the Portfolio agent with `getHoldings`.
3. Clear the stored credentials, fetch a token as demo user 2, then run `chat` again. Expect user 2's holdings only.
4. Run `chat without token`. Expect 401.
