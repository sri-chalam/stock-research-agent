package info.saagar.stockagent.marketdata.provider;

import java.math.BigDecimal;

/// One scheduled or reported earnings release.
///
/// @param symbol ticker symbol
/// @param date release date, ISO-8601, e.g. `2026-05-01`
/// @param hour release time: `bmo` (before market open), `amc` (after market close), `dmh` (during
///   market hours), or blank when unknown
/// @param fiscalYear fiscal year of the reported quarter
/// @param fiscalQuarter fiscal quarter, 1 to 4
/// @param epsEstimate consensus EPS estimate
/// @param epsActual reported EPS; `null` before the release
/// @param revenueEstimate consensus revenue estimate in USD
/// @param revenueActual reported revenue in USD; `null` before the release
public record EarningsEvent(
    String symbol,
    String date,
    String hour,
    Integer fiscalYear,
    Integer fiscalQuarter,
    BigDecimal epsEstimate,
    BigDecimal epsActual,
    BigDecimal revenueEstimate,
    BigDecimal revenueActual) {}
