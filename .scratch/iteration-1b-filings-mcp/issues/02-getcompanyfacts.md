# 02: `getCompanyFacts(ticker)` returns fixed headline XBRL numbers

**What to build:** `POST /api/filings/chat` "What was Apple's revenue for the last 3 fiscal years?" returns numbers from `getCompanyFacts`, each citing form, period and accession number.

**Blocked by:** 01

**Status:** ready-for-agent

## Decisions

- **Signature:** `getCompanyFacts(ticker)`. There's no `concept` parameter (it differs from the spec's original `getCompanyFacts(ticker, concept)`; ticket 04 updates the spec).
- **Upstream:** one call to `GET /api/xbrl/companyfacts/CIK{cik10}.json`, added to `EdgarClient`. The payload is large (often several MB), so map only the `facts.us-gaap` entries this tool needs and ignore the rest. It's one HTTP call per tool call, with no caching (MCP-3).
- **Fixed metrics.** Each metric has a hard-coded, ordered fallback list of US-GAAP tags, and the first tag the company reports wins:

  | Metric | Tags (in order) | Unit |
  |---|---|---|
  | `revenue` | `Revenues`, `RevenueFromContractWithCustomerExcludingAssessedTax`, `SalesRevenueNet` | USD |
  | `netIncome` | `NetIncomeLoss` | USD |
  | `grossProfit` | `GrossProfit` | USD |
  | `epsDiluted` | `EarningsPerShareDiluted` | USD/shares |
  | `totalAssets` | `Assets` | USD |
  | `cashAndEquivalents` | `CashAndCashEquivalentsAtCarryingValue` | USD |

- **Selection (keep it simple):** use only facts with `form` = `10-K` and `fp` = `FY`. For each metric, take the 3 most recent distinct period `end` dates. For flow metrics (revenue, net income, gross profit, EPS), use only facts whose `start` is roughly a year before `end` (> 300 days), so quarterly values reported inside a 10-K are skipped.
- **Missing metric:** a metric the company doesn't report under any of its tags is left out of the result, with no error.
- **Output:** `CompanyFacts` record: `ticker`, plus a list of `MetricValue(metric, fiscalPeriodEnd, value, unit, form, filedDate, accessionNumber)`. Values are numbers, not formatted strings.
- An unknown ticker gets the same error as ticket 01.
- The tool description lists the six metrics, so the model knows what it can ask for.

## Tests (synthetic data only)

- `FilingsTools` against a fake `EdgarClient`:
  - picks the 3 latest fiscal years;
  - skips quarterly facts inside a 10-K;
  - falls back to the second revenue tag when `Revenues` is absent;
  - leaves out a metric that has no tag.
- One `MockRestServiceServer` test: a small synthetic `companyfacts` JSON maps correctly.

## Acceptance criteria

- [ ] `./gradlew build` passes.
- [ ] Manual check: the Apple revenue question returns three fiscal years of revenue, each citing form, period and accession number, with `getCompanyFacts` in `toolsCalled`.
- [ ] The tool result stays small (6 metrics × 3 years at most), with no raw XBRL dump.

## Comments
