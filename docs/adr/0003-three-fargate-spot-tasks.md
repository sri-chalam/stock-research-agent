# 0003. Deploy as three Fargate Spot tasks

## Status

Accepted

## Context

The ephemeral stack runs on ECS Fargate Spot without a NAT gateway (spec DEP-3, DEP-4) and is cost-sensitive. The system has six runtime components: the agent app, Bifrost and four MCP servers (ADR 0002). Each Fargate task has a minimum size, so one task per component multiplies the baseline cost.

## Decision

Three ECS tasks are deployed, all on the Fargate Spot capacity provider:

1. Agent app.
2. Bifrost gateway.
3. MCP servers: the four servers run as four containers in one task, each on its own port.

Bifrost registers the four server URLs, which differ by port. Each server keeps a distinct audience URL for token validation.

## Consequences

- Baseline cost is roughly half that of six tasks.
- One Spot interruption or out-of-memory kill stops all four MCP servers together.
- The task role is per task, not per container, so the servers cannot hold different AWS permissions. Upstream API keys are still injected per container from SSM, so MCP-1 holds.
- The four servers cannot scale independently. The task is sized for their sum (about 2 vCPU and 4 GB), with a heap limit on each JVM.
- Service discovery without a NAT gateway uses ECS Service Connect or Cloud Map, with tight security groups.
- If `filings-mcp` or `portfolio-mcp` later needs separate scaling or permissions, it is moved to its own task. Only the URLs registered in Bifrost change.

## Alternatives considered

- **Six tasks (one per component):** best isolation and scaling, at the highest cost.
- **Single task for everything:** cheapest, but Bifrost and the agent app would share fate with the MCP servers and could not be scaled or restarted separately.
