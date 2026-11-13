# 04: Spec, ADRs, learning notes and README for Iteration 1b

**What to build:** Record the 1b decisions (agreed in the 2026-11-13 grilling session) so the docs match what was built.

**Blocked by:** 02, 03 (spec and ADR edits can be done earlier)

**Status:** ready-for-agent

## Decisions

- **AGENTS.md:**
  - The "Project intent" paragraph in §1 is ✅ already added.
  - Add a `filings-mcp` row to the §3 module table (port 8082) and a `### filings-mcp` package table mirroring `market-data-mcp`'s.
  - Add `./gradlew :filings-mcp:bootRun` to §4.
- **Spec:**
  - §7.1 filings-mcp row: `listFilings(ticker, formType)` (formType required: 10-K/10-Q/8-K) and `getCompanyFacts(ticker)` (fixed metrics) in 1b. `getFilingSection` and `searchFilings` arrive in Iteration 2.
  - MCP-2: add the filings trimming rules (10 most recent filings; about 6 fixed metrics × 3 fiscal years).
  - FR-13: the ingestion ticker list is a subset of the `filings-mcp` configured ticker map.
  - §14 row 1b: drop the `mcp-common` extraction. Scope = two tools, configured ticker→CIK map, Filings analyst behind `POST /api/filings/chat` (temporary until Iteration 3). Replace the acceptance criteria with the six agreed ones (see tickets 01 and 02, plus "`docker compose up` runs all three apps").
  - §8.11 API table: add `POST /api/filings/chat`, marked temporary (removed in Iteration 3).
- **ADR 0010:** replace "Iteration 1b moves it into the shared `mcp-common` module" with: each MCP server keeps its own copy of the controller. Duplication is accepted to keep the build simple (AGENTS.md §1 Project intent).
- **ADR 0008:** add `SEC_USER_AGENT` (not secret, but personal, so it's kept out of Git: env var locally, SSM in AWS).
- **learning-notes.md:** a new "SEC EDGAR" section:
  - EDGAR APIs are keyed by 10-digit zero-padded CIK, not ticker. We configure CIKs per ticker instead of downloading `company_tickers.json`.
  - SEC rejects requests without a `User-Agent` that names the app and a contact email (403).
  - XBRL tags vary by company (e.g., revenue). `getCompanyFacts` hides this behind a small fallback list.
- **README:** ports table marks `filings-mcp` 8082 as Iteration 1b; run instructions now start three apps (both MCP servers before agent-app); a curl example for `/api/filings/chat`; and an Iteration 1b section.

## Acceptance criteria

- [ ] Spec §7.1, MCP-2, FR-13, §8.11 and §14 row 1b match what was built.
- [ ] ADR 0010 no longer mentions `mcp-common`; ADR 0008 lists `SEC_USER_AGENT`.
- [ ] learning-notes.md has the SEC EDGAR section.
- [ ] README and AGENTS.md list filings-mcp and how to run it.

## Comments
