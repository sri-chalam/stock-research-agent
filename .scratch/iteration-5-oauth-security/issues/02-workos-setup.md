# 02: WorkOS setup

**What to build:** a WorkOS AuthKit environment that issues user access tokens the four apps can validate, two demo users to sign in as, and a Bruno collection that obtains a token through Authorization Code + PKCE.

**Blocked by:** None (can start immediately)

**Status:** done

## Acceptance criteria

- [x] WorkOS application with redirect URIs for Bruno's OAuth callback and `http://localhost:8080/`.
- [x] Permissions `agent.chat`, `marketdata.read`, `filings.read`, `portfolio.read` exist and are granted to a role assigned to both demo users.
- [x] Two demo users created; their `sub` values recorded in local env (not in Git).
- [x] Bruno: an environment with OAuth 2.0 Authorization Code + PKCE that signs in and attaches the access token to `POST /api/chat`.
- [x] Recorded finding (in this file's Comments): which claim WorkOS uses for permissions (`scope` or `permissions`), and whether one access token can carry several audiences or only the client ID. Ticket 03 configures `audiences` and `authorities-claim-name` from this.

## Comments

**Bruno collection** (`bruno/`): collection-level OAuth 2.0 Authorization Code + PKCE against the WorkOS User Management endpoints (`/user_management/authorize?provider=authkit`, `/user_management/authenticate`), public client (no secret), callback `http://localhost:8080/callback`. WorkOS redirect URIs: `http://localhost:8080/callback` (default; Bruno) and `http://localhost:8080` (GUI, ticket 5b). Client ID comes from `bruno/.env` (git-ignored, see `bruno/.env.example`). `chat` inherits the token; `chat without token` sends none (expects 401 after ticket 03).

**Demo users:** `sub` values go in `.env` as `WORKOS_DEMO_USER_1_SUB` / `WORKOS_DEMO_USER_2_SUB` (keys in `.env.example`).

**Token findings** (from a decoded real access token, 2026-11-18):

Claims: `iss`, `sub` (`user_…`), `sid`, `jti`, `auth_time`, `client_id`, `org_id`, `role`, `roles`, `permissions`, `exp`, `iat`.

- **Permissions:** in `permissions`, a JSON array (`["agent.chat", "filings.read", "marketdata.read", "portfolio.read"]`). There is no `scope` claim. → `authorities-claim-name: permissions`, `authority-prefix: SCOPE_` (or empty).
- **Organization required:** `role`, `roles` and `permissions` come from the user's organization membership (`org_id`). A user with no membership gets a token without permissions, so every app returns 403.
- **Audience:** the token has **no `aud` claim**. One token cannot carry several audiences, and the per-server audience check of SEC-3 / MCP-6 cannot be done with `audiences`. Spring's audience validator rejects a token that has no `aud` claim, so ticket 03 must leave `audiences` unset. The closest claim is `client_id` (the WorkOS application).
- **Decision (2026-11-18): no audience check.** Each app checks signature, issuer and expiry, and requires its own permission (`agent.chat`, `marketdata.read`, `filings.read`, `portfolio.read`). That permission is the per-server boundary. Only standard properties are used (SEC-2), and no `client_id` validator is written. The "wrong-audience → 401" tests become "wrong-issuer → 401". Gap: any token from this WorkOS environment with the right permission is accepted by every server. Recorded in the learning notes (ticket 05).
- **Issuer:** `https://api.workos.com/user_management/<client_id>`. The client ID in `iss` **differs** from the token's `client_id` claim. Ticket 03 copies `iss` exactly from a real token. Ticket 03 also checks whether WorkOS serves discovery under this issuer. If it doesn't, set `jwk-set-uri` (`https://api.workos.com/sso/jwks/<client_id>`) together with `issuer-uri`.
- **Lifetime:** `exp − iat` = 300 s. Access tokens last 5 minutes, so Bruno must fetch a new one often.
