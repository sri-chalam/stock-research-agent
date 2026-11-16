# 0008. Shared API keys from environment variables, stored in SSM in AWS

## Status

Accepted

## Context

The application needs an upstream API key (Finnhub) and an Anthropic key. The spec first said each user supplies their own data-provider keys. Per-user keys would need encrypted key storage, passing the key from user to agent to MCP server, and per-user rate limits. None of that helps the learning goals.

## Decision

The person deploying the application supplies the keys, and all users share them. The keys stay inside the MCP servers (MCP-1) and never reach the agent or the LLM.

The code reads each key from an environment variable (for example `FINNHUB_API_KEY`) in every environment:

- **Local:** a git-ignored `.env` file used by Docker Compose, or exported env vars.
- **AWS:** SSM Parameter Store SecureString (standard tier), stored once in the persistent stack so teardown does not delete it (DEP-2). The CDK task definition lists each parameter under the container's `secrets`, and ECS injects it as an environment variable at task start. The task execution role needs `ssm:GetParameters` on those parameters. The default AWS-managed key needs no extra KMS permission.

`SEC_USER_AGENT` (used by `filings-mcp`) follows the same path. SEC EDGAR needs no key, but requires a `User-Agent` with a contact email. The value is not secret, but it is personal, so it stays out of Git: an env var locally and an SSM parameter in AWS.

Secrets Manager is not used (DEP-5).

## Consequences

- One code path for reading keys, whether local or in AWS.
- No cost for key storage. Secrets Manager would charge about $0.40 per secret per month.
- No automatic key rotation. Rotating a key means updating the parameter and restarting the task.
- All users share each provider's rate limit, for example Finnhub's ~60 requests/min. The MCP servers do not rate-limit or cache (MCP-3); exceeding the limit returns a tool error.
- Finnhub's free tier is for personal use, so the app must not be opened to the public.
