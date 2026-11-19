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
- Theme (Pico CSS variables), light mode only, soft blush/mauve with no blue: off-white page (`#FBF7F9`) and white cards, plum-grey text (`#3A2E39`), mauve primary buttons and "cached: yes" badge (`#EBD5E6`), plum links (`#8E4F86`), green gains (`#2F7356`), red errors and drops (`#A8404F`). Inter for text, JetBrains Mono for tool names. Two columns on wide screens (answer; trace and examples), one on phones; rounded cards, sticky prompt bar.

## Acceptance criteria

- [ ] `/` serves `index.html` without a token; `/api/**` still requires one (Spring test).
- [ ] Signed in as demo user 1, "What stocks do I hold?" shows user 1's holdings and the trace panel shows Portfolio agent → `getHoldings`.
- [ ] After sign-out and sign-in as demo user 2, the same question shows user 2's holdings.
- [ ] A reworded filings question asked twice shows "cached: yes" the second time.
- [ ] Sign-out clears the page and the token; the signed-in user's email is shown while signed in.
- [ ] `./gradlew build` passes.
