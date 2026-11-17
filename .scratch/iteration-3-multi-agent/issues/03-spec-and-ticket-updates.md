# 03: Spec and ticket updates for the Iteration 3 decisions

**What to build:** the requirements spec and earlier tickets match the Iteration 3 decisions, so nobody plans work against the old eval requirement.

**Blocked by:** None (can start immediately)

**Status:** done

## Decisions

- The eval suite is dropped for Iteration 3, and evals have no iteration for now.
- Iteration 3 acceptance is a manual check: three curl prompts (filings only, market data only, both), verified through `answerProvenance`, which records per-agent tokens and latency.

## Acceptance criteria

- [x] Spec §14, Iteration 3: the acceptance criterion "Supervisor routes correctly per eval trajectory checks; cost/latency recorded" is replaced by the three-prompt check, with per-agent tokens and latency from `answerProvenance`.
- [x] Spec NFR-14 to NFR-16 and the spec's purpose line say evals are deferred with no iteration assigned.
- [x] Iteration 2 ticket 03 (retrieval eval) no longer says answer-level evals move to Iteration 3; it says they are deferred.
- [x] The edits use "Supervisor" and "specialist agent" as defined in the glossary.

## Comments
