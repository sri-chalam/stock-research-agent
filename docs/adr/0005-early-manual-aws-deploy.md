# 0005. Deploy to AWS early, manually and restricted

## Status

Accepted

## Context

The full deployment work (CI/CD, nightly destroy, tracing, Bifrost) is planned for Iteration 9. Deploying only then risks finding container, networking and ECS problems late. Bifrost is deferred to Iteration 6 (ADR 0004), and the agent app already connects to the MCP servers through configurable URLs, so an early deployment does not depend on it. The Finnhub free tier is personal use only (spec R-5), and WorkOS authentication arrives in Iteration 5.

## Decision

A manual AWS deployment is done after Iteration 2 (Iteration 2b):

- Two Fargate Spot tasks: the agent app, and the MCP servers as containers in one task (ADR 0003). Bifrost is added later as the third task.
- A minimal CDK ephemeral stack, with images pushed to ECR by hand.
- The load balancer security group allows only the developer's IP address. The deployment is not public.
- The full GitHub Actions workflows, nightly destroy, OpenTelemetry tracing and budget alert remain in Iteration 9, which extends this stack instead of replacing it.

## Consequences

- Container, networking, service discovery and Spot behavior are proven early.
- The deployment has no user authentication until Iteration 5, so the IP restriction is the only access control. It must not be loosened before then.
- Licensed provider data is not exposed to third parties, in line with R-5.
- Every working session ends by running `scripts/aws-teardown.sh`. The stack is never left running overnight, because the load balancer, Fargate tasks and any public IPv4 addresses are billed while they exist.
- The script runs `cdk destroy` on the ephemeral stack, then checks for leftovers CDK may not remove (ALBs, target groups, ECS services and tasks, Elastic IPs, orphaned network interfaces, log groups). It exits non-zero and lists anything still present.
- This script is the safety net until the nightly-destroy workflow exists (Iteration 9).
- An AWS Budget alert with a low threshold (for example $5) is created in the persistent stack before the first deployment, to catch a forgotten teardown.
