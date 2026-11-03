# 0006. No separate audit log for pending actions

## Status

Accepted

## Context

The spec originally required every paper-trade proposal, approval and rejection to be written to an audit log (FR-24). An audit log matters in a real trading product. This project exists to learn RAG, semantic caching, MCP and gateways, and a separate audit log adds a table, write paths and tests without teaching any of those.

## Decision

There is no separate audit log. Each `PendingAction` row records its status (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`) and decision timestamps, and that row is the only record of the outcome.

The human-in-the-loop rules stay unchanged:

- The agent can only propose a paper trade; deterministic code executes it after approval (FR-21, FR-22).
- Pending actions expire after a configurable time, and an expired action cannot be approved (FR-23).

## Consequences

- Less code and fewer tests in Iteration 7.
- The outcome of each action is still queryable from the `PendingAction` table.
- The history of status changes is lost: only the final status is kept. Adding an audit log later means adding a new table, with no change to the approval flow.
