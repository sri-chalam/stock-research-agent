package info.saagar.stockagent.filings.tool;

import java.math.BigDecimal;

/// One fiscal year's value of one headline metric, as `getCompanyFacts` returns it.
///
/// @param metric metric name, e.g. `revenue`
/// @param fiscalPeriodEnd last day of the fiscal year, `YYYY-MM-DD`
/// @param value the reported number
/// @param unit `USD` or `USD/shares`
/// @param form form of the filing that first reported it, `10-K`
/// @param filedDate date that filing was filed, `YYYY-MM-DD`
/// @param accessionNumber accession number of that filing, with dashes
public record MetricValue(
    String metric,
    String fiscalPeriodEnd,
    BigDecimal value,
    String unit,
    String form,
    String filedDate,
    String accessionNumber) {}
