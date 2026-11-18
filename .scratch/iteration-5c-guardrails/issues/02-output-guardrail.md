# 02: Output guardrail

**What to build:** every research answer ends with "Not investment advice", and links in answers point only to known sources (sec.gov and Finnhub news), using LangChain4j's `OutputGuardrail` on the Supervisor.

**Blocked by:** Iteration 5b ticket 01

**Status:** ready-for-agent

## Decisions

- Disallowed URLs are stripped and the text kept (rewrite), not reprompted.
- The disclaimer is added once, never duplicated.

## Acceptance criteria

- [ ] JUnit: the disclaimer is added exactly once, including when the model already wrote it.
- [ ] JUnit: sec.gov and Finnhub news links are kept; other links are removed with their surrounding text intact.
- [ ] Rewrites are logged with the count of removed links (no URLs logged).
- [ ] `./gradlew build` passes.
