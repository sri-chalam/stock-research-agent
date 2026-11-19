# 01: Minimal chat page

**What to build:** a tester opens `http://localhost:8080/`, signs in through WorkOS, asks a question, and sees the answer plus which agents and tools produced it, without using a REST client.

**Blocked by:** Iteration 5 ticket 05

**Status:** ready-for-agent

## Decisions

- One `index.html` served by agent-app; plain JavaScript; `authkit-js` and Pico.css from a CDN; no npm.
- Synchronous `POST /api/chat` with a "Thinking…" state; no SSE.
- Answer rendered as plain text with URLs turned into links (no Markdown library, no HTML injection).
- Trace panel from the existing `toolsCalled` and `cached` fields only (e.g. "Market data agent → `getQuote(AAPL)`", "cached: yes"). `ChatResponse` is not extended.
- Latest question and answer only; no history.
- A "Try asking" card, always visible in the right column (below the trace once there is an answer), lists five example prompts, one per agent path (portfolio, quote, filings, portfolio + market, news). A click fills the prompt box without sending.
- Theme (Pico CSS variables), light mode only: beige page and card backgrounds, brown text, headings and borders, green primary buttons, links, "cached: yes" and gains, red errors, Sign out and drops. Centred single column, rounded cards, sticky prompt bar.

## Acceptance criteria

- [ ] `/` serves `index.html` without a token; `/api/**` still requires one (Spring test).
- [ ] Signed in as demo user 1, "What stocks do I hold?" shows user 1's holdings and the trace panel shows Portfolio agent → `getHoldings`.
- [ ] After sign-out and sign-in as demo user 2, the same question shows user 2's holdings.
- [ ] A reworded filings question asked twice shows "cached: yes" the second time.
- [ ] Sign-out clears the page and the token; the signed-in user's email is shown while signed in.
- [ ] `./gradlew build` passes.
