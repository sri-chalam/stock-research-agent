# 02: WorkOS setup

**What to build:** a WorkOS AuthKit environment that issues user access tokens the four apps can validate, two demo users to sign in as, and a Bruno collection that obtains a token through Authorization Code + PKCE.

**Blocked by:** None (can start immediately)

**Status:** ready-for-human

## Acceptance criteria

- [ ] WorkOS application with redirect URIs for Bruno's OAuth callback and `http://localhost:8080/`.
- [ ] Permissions `agent.chat`, `marketdata.read`, `filings.read`, `portfolio.read` exist and are granted to a role assigned to both demo users.
- [ ] Two demo users created; their `sub` values recorded in local env (not in Git).
- [ ] Bruno: an environment with OAuth 2.0 Authorization Code + PKCE that signs in and attaches the access token to `POST /api/chat`.
- [ ] Recorded finding (in this file's Comments): which claim WorkOS uses for permissions (`scope` or `permissions`), and whether one access token can carry several audiences or only the client ID. Ticket 03 configures `audiences` and `authorities-claim-name` from this.
