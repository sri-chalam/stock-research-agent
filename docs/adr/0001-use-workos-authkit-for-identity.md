# 0001. Use WorkOS AuthKit for identity

## Status

Accepted

## Context

Users must sign in to the agent app, and the app and every MCP server must validate tokens (signature, issuer, expiry, audience, scopes). The scheduled morning-brief job also needs a machine-to-machine credential. The project is a personal, cost-sensitive demo that is deployed and destroyed on demand, so an identity provider that is managed, free at low volume, and standards-based is needed. Running and securing a self-hosted provider would add operational work unrelated to the project's goals.

## Decision

WorkOS AuthKit is used as the identity provider.

- Users are authenticated with OAuth 2.1 Authorization Code + PKCE.
- The morning-brief job is authenticated with a WorkOS M2M application (client credentials) holding minimal scopes.
- The agent app and each MCP server are configured as Spring Security OAuth2 resource servers using only standard properties (`issuer-uri`, authorities claim). No WorkOS-specific code is used, so the provider can be replaced (Cognito, Keycloak) by configuration alone.
- The Bifrost gateway (introduced in Iteration 6, see ADR 0004) is a separate process and not a Spring Boot application, so it is not a Spring Security resource server. It authenticates agents with virtual keys and forwards the user token, which the MCP servers validate.

## Consequences

- Hosted login, session handling and the M2M flow are provided without any infrastructure to operate. The free tier (up to 1M MAU) covers the expected usage at no cost.
- Vendor lock-in is limited by the standards-only resource-server configuration.
- Support for RFC 8693 token exchange is unconfirmed. Until it is verified, a single user token is forwarded to the MCP servers (Option A in the spec, section 9.3). WorkOS access tokens carry no `aud` claim (verified in Iteration 5), so servers check issuer and their own scope, not audience. Keycloak remains the fallback if per-server token exchange is required; only one identity provider is to be used in the project.
- Open-source Bifrost validates only JWTs that Bifrost itself issues. Validation of JWTs from an external identity provider such as WorkOS is an Enterprise feature (enterprise v1.5.3 changelog; "user" identity mode requires SSO or SCIM). Validation of the WorkOS token therefore stays in the MCP servers. This also keeps ownership checks in `portfolio-mcp` possible, since they need the user identity inside the server. This finding is based largely on vendor documentation and is to be re-verified in Iteration 6.
- The decision is revisited if token exchange proves necessary and WorkOS does not support it.

## Alternatives considered

- **Keycloak (self-hosted):** supports token exchange, but requires operating and securing another service in a stack that is destroyed nightly.
- **AWS Cognito:** viable and AWS-native, but the hosted login experience and M2M setup were judged less convenient for this project.
