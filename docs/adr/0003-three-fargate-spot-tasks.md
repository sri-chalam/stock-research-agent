# 0003. Deploy as three Fargate Spot tasks

## Status

Accepted

## Context

The ephemeral stack runs on ECS Fargate Spot without a NAT gateway (spec DEP-3, DEP-4) and is cost-sensitive. The system has five runtime components: the agent app, Bifrost and three MCP servers (ADR 0002). Each Fargate task has a minimum size, so one task per component multiplies the baseline cost.

## Decision

Three ECS tasks are deployed, all on the Fargate Spot capacity provider:

1. Agent app.
2. Bifrost gateway.
3. MCP servers: the three servers run as three containers in one task, each on its own port.

Bifrost registers the three server URLs, which differ by port. Each server requires its own scope for token validation (WorkOS tokens carry no audience).

## Consequences

- Baseline cost is lower than that of five tasks.
- One Spot interruption or out-of-memory kill stops all three MCP servers together.
- The task role is per task, not per container, so the servers cannot hold different AWS permissions. Upstream API keys are still injected per container from SSM, so MCP-1 holds.
- The three servers cannot scale independently. The task is sized for their sum (about 2 vCPU and 4 GB), with a heap limit on each JVM.
- Service discovery without a NAT gateway uses ECS Service Connect or Cloud Map, with tight security groups.
- If `filings-mcp` or `portfolio-mcp` later needs separate scaling or permissions, it is moved to its own task. Only the URLs registered in Bifrost change.

## Alternatives considered

- **Five tasks (one per component):** best isolation and scaling, at the highest cost.
- **Single task for everything:** cheapest, but Bifrost and the agent app would share fate with the MCP servers and could not be scaled or restarted separately.
