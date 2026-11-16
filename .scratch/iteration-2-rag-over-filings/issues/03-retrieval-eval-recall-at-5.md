# 03: Retrieval eval measures recall@5 for `searchFilings`

**What to build:** A small, deterministic retrieval eval that shows whether chunking and top-k retrieval actually find the right filing section. A developer runs one Gradle task against the Compose stack and gets a recall@5 number to record and compare when chunk size or `k` changes. No LLM is involved.

**Blocked by:** 02

**Status:** ready-for-agent

## Decisions

- **Retrieval only.** The eval calls `searchFilings` (or the retrieval code behind it) directly. Answer-level evals (LLM output, trajectory, citations, LLM-as-judge) move to Iteration 3.
- **Cases:** about 10–15, stored as a JSON file in `filings-mcp` test resources. Each case has a `question`, a `ticker` and the expected `form` + `section`, e.g. "NVDA supply-chain risk" → 10-K Item 1A. Cover all five tickers and all four sections. Write the questions by hand. They contain no copied filing text.
- **Metric:** a case passes when at least one of the top 5 results matches the expected form and section. Report recall@5 = passed / total, plus the cases that failed.
- **Harness:** a JUnit 5 test tagged `@Tag("eval")`, excluded from `./gradlew build` and `./gradlew test`, and run by its own Gradle task (e.g. `./gradlew :filings-mcp:evalTest`). It runs against the ingested Postgres from Compose and reads connection settings from the same env vars as the app.
- **Threshold:** the test fails below a configurable recall@5. Set the initial threshold from the first measured baseline, slightly below it, so it catches regressions.
- **Output:** log the result as a structured event (`retrievalEvalCompleted`, with total, passed, recall and failed case IDs). Don't use `System.out`.

## Acceptance criteria

- [ ] `./gradlew build` passes and doesn't run the eval.
- [ ] With Compose running and ingestion complete, the eval task runs and reports recall@5 and any failed cases.
- [ ] The baseline recall@5 is recorded in this ticket's Comments for ticket 04 to put in the README.
- [ ] Temporarily changing the chunk size (e.g. to 400 characters) and re-ingesting changes the reported number (manual check that the eval is sensitive to chunking).

## Comments
