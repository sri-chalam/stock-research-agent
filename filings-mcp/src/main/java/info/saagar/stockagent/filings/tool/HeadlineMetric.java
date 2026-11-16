package info.saagar.stockagent.filings.tool;

import java.util.List;

/// The fixed metrics `getCompanyFacts` returns, each with its US-GAAP tags in fallback order.
///
/// A flow metric covers a period (a fiscal year); the others are point-in-time balances.
enum HeadlineMetric {
  REVENUE(
      "revenue",
      List.of("Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet"),
      "USD",
      true),
  NET_INCOME("netIncome", List.of("NetIncomeLoss"), "USD", true),
  GROSS_PROFIT("grossProfit", List.of("GrossProfit"), "USD", true),
  EPS_DILUTED("epsDiluted", List.of("EarningsPerShareDiluted"), "USD/shares", true),
  TOTAL_ASSETS("totalAssets", List.of("Assets"), "USD", false),
  CASH_AND_EQUIVALENTS(
      "cashAndEquivalents", List.of("CashAndCashEquivalentsAtCarryingValue"), "USD", false);

  private final String metricName;
  private final List<String> conceptNames;
  private final String unit;
  private final boolean flowMetric;

  HeadlineMetric(String metricName, List<String> conceptNames, String unit, boolean flowMetric) {
    this.metricName = metricName;
    this.conceptNames = conceptNames;
    this.unit = unit;
    this.flowMetric = flowMetric;
  }

  String metricName() {
    return metricName;
  }

  List<String> conceptNames() {
    return conceptNames;
  }

  String unit() {
    return unit;
  }

  boolean flowMetric() {
    return flowMetric;
  }
}
