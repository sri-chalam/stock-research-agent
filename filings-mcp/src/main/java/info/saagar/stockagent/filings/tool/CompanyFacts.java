package info.saagar.stockagent.filings.tool;

import java.util.List;

/// A company's headline XBRL numbers, as `getCompanyFacts` returns them.
///
/// @param ticker upper-case ticker, e.g. `AAPL`
/// @param metricValues up to 3 fiscal years per metric, newest first; a metric the company does not
///   report is absent
public record CompanyFacts(String ticker, List<MetricValue> metricValues) {}
