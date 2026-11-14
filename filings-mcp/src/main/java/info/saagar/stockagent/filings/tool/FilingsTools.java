package info.saagar.stockagent.filings.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.provider.EdgarClient;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FilingsException;
import info.saagar.stockagent.filings.provider.XbrlFact;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/// SEC filings tools served over MCP.
///
/// Only the tickers configured in `filings.tickers` are served. Errors are thrown, never returned:
/// the MCP server turns a thrown exception into a tool result with `isError: true` and the
/// exception message as its text. A returned error string would be reported to the agent as a
/// success.
@Component
public class FilingsTools {

  static final int MAX_FILINGS = 10;
  private static final Set<String> SUPPORTED_FORM_TYPES = Set.of("10-K", "10-Q", "8-K");
  private static final String FORM_TYPE_MESSAGE =
      "formType is required and must be one of: 10-K, 10-Q, 8-K.";
  private static final String ARCHIVES_URL = "https://www.sec.gov/Archives/edgar/data/";
  static final int MAX_FISCAL_YEARS = 3;
  private static final long MIN_FISCAL_YEAR_DAYS = 300;
  private static final Set<String> ALL_CONCEPT_NAMES =
      Arrays.stream(HeadlineMetric.values())
          .flatMap(headlineMetric -> headlineMetric.conceptNames().stream())
          .collect(Collectors.toUnmodifiableSet());

  private final EdgarClient edgarClient;
  private final Map<String, String> ciksByTicker;
  private final String unknownTickerMessage;

  /// Creates the tools.
  ///
  /// @param edgarClient source of filings
  /// @param filingsProperties holds the supported tickers and their CIKs
  public FilingsTools(EdgarClient edgarClient, FilingsProperties filingsProperties) {
    this.edgarClient = edgarClient;
    this.ciksByTicker = new LinkedHashMap<>();
    filingsProperties
        .tickers()
        .forEach((ticker, cik) -> ciksByTicker.put(ticker.toUpperCase(Locale.ROOT), cik));
    this.unknownTickerMessage =
        "Filings are available only for: " + String.join(", ", ciksByTicker.keySet()) + ".";
  }

  /// Lists a company's 10 most recent filings of one form type.
  ///
  /// @param ticker a configured ticker, any case, e.g. `msft`
  /// @param formType `10-K`, `10-Q` or `8-K`
  /// @return at most 10 filings, newest first
  /// @throws IllegalArgumentException when the form type is missing or unsupported, or the ticker
  ///   is not configured
  /// @throws FilingsException when EDGAR fails
  @Tool(
      "List a US company's 10 most recent SEC filings of one form type, newest first. Each has the"
          + " form, filingDate, reportPeriod (end of the period covered), accessionNumber and a"
          + " sec.gov url. formType is required: 10-K (annual), 10-Q (quarterly) or 8-K (current"
          + " report). Only a fixed set of 10 large US companies is supported; another"
          + " ticker returns an error listing the supported ones.")
  public List<FilingSummary> listFilings(
      @P("Ticker symbol, e.g. MSFT") String ticker,
      @P("Form type: 10-K, 10-Q or 8-K") String formType) {
    String normalizedFormType = normalizeFormType(formType);
    String cik = cikFor(ticker);
    String cikWithoutLeadingZeros = Long.toString(Long.parseLong(cik));
    return edgarClient.fetchRecentFilings(cik).stream()
        .filter(edgarFiling -> normalizedFormType.equals(edgarFiling.form()))
        .limit(MAX_FILINGS)
        .map(edgarFiling -> toFilingSummary(edgarFiling, cikWithoutLeadingZeros))
        .toList();
  }

  /// Returns a company's six headline numbers for its last 3 fiscal years, from XBRL company facts.
  ///
  /// Only values reported in a 10-K for the full fiscal year are used. For each metric, the
  /// company's tag with the most recent fiscal year wins, so a tag the company stopped using is not
  /// picked. A value reported again in later 10-Ks cites the 10-K that first reported it.
  ///
  /// @param ticker a configured ticker, any case, e.g. `aapl`
  /// @return up to 3 values per metric, newest first; metrics the company does not report are left
  ///   out
  /// @throws IllegalArgumentException when the ticker is not configured
  /// @throws FilingsException when EDGAR fails
  @Tool(
      "Get a US company's headline financial numbers from SEC XBRL data, for its last 3 fiscal"
          + " years, as reported in its 10-K filings. Metrics: revenue, netIncome, grossProfit,"
          + " epsDiluted (diluted earnings per share), totalAssets and cashAndEquivalents. Each"
          + " value has the metric, fiscalPeriodEnd, value, unit (USD or USD/shares), form,"
          + " filedDate and accessionNumber of the 10-K that reported it. A metric the company"
          + " does not report is left out. Only a fixed set of 10 large US companies is"
          + " supported; another ticker returns an error listing the supported ones.")
  public CompanyFacts getCompanyFacts(@P("Ticker symbol, e.g. AAPL") String ticker) {
    String cik = cikFor(ticker);
    List<XbrlFact> companyFacts = edgarClient.fetchCompanyFacts(cik, ALL_CONCEPT_NAMES);
    List<MetricValue> metricValues = new ArrayList<>();
    for (HeadlineMetric headlineMetric : HeadlineMetric.values()) {
      metricValues.addAll(latestFiscalYearValues(headlineMetric, companyFacts));
    }
    return new CompanyFacts(normalizeTicker(ticker), metricValues);
  }

  private static List<MetricValue> latestFiscalYearValues(
      HeadlineMetric headlineMetric, List<XbrlFact> companyFacts) {
    List<XbrlFact> chosenConceptFacts = List.of();
    String chosenLatestPeriodEnd = "";
    for (String conceptName : headlineMetric.conceptNames()) {
      List<XbrlFact> conceptFacts =
          companyFacts.stream()
              .filter(xbrlFact -> conceptName.equals(xbrlFact.concept()))
              .filter(xbrlFact -> isFullFiscalYearIn10K(headlineMetric, xbrlFact))
              .toList();
      for (XbrlFact conceptFact : conceptFacts) {
        if (conceptFact.end().compareTo(chosenLatestPeriodEnd) > 0) {
          chosenLatestPeriodEnd = conceptFact.end();
          chosenConceptFacts = conceptFacts;
        }
      }
    }
    Map<String, XbrlFact> firstReportedFactByPeriodEnd = new TreeMap<>(Comparator.reverseOrder());
    for (XbrlFact conceptFact : chosenConceptFacts) {
      firstReportedFactByPeriodEnd.merge(
          conceptFact.end(),
          conceptFact,
          (keptFact, otherFact) ->
              otherFact.filedDate().compareTo(keptFact.filedDate()) < 0 ? otherFact : keptFact);
    }
    return firstReportedFactByPeriodEnd.values().stream()
        .limit(MAX_FISCAL_YEARS)
        .map(xbrlFact -> toMetricValue(headlineMetric, xbrlFact))
        .toList();
  }

  /// Keeps 10-K facts in the metric's unit; for a flow metric, also only a period of about a year,
  /// which drops the quarterly values some 10-Ks report.
  private static boolean isFullFiscalYearIn10K(HeadlineMetric headlineMetric, XbrlFact xbrlFact) {
    if (!"10-K".equals(xbrlFact.form())
        || !"FY".equals(xbrlFact.fiscalPeriod())
        || !headlineMetric.unit().equals(xbrlFact.unit())) {
      return false;
    }
    if (!headlineMetric.flowMetric()) {
      return true;
    }
    return xbrlFact.start() != null
        && ChronoUnit.DAYS.between(
                LocalDate.parse(xbrlFact.start()), LocalDate.parse(xbrlFact.end()))
            > MIN_FISCAL_YEAR_DAYS;
  }

  private static MetricValue toMetricValue(HeadlineMetric headlineMetric, XbrlFact xbrlFact) {
    return new MetricValue(
        headlineMetric.metricName(),
        xbrlFact.end(),
        xbrlFact.value(),
        xbrlFact.unit(),
        xbrlFact.form(),
        xbrlFact.filedDate(),
        xbrlFact.accessionNumber());
  }

  private static String normalizeFormType(String rawFormType) {
    String normalizedFormType =
        rawFormType == null ? "" : rawFormType.strip().toUpperCase(Locale.ROOT);
    if (!SUPPORTED_FORM_TYPES.contains(normalizedFormType)) {
      throw new IllegalArgumentException(FORM_TYPE_MESSAGE);
    }
    return normalizedFormType;
  }

  private static String normalizeTicker(String rawTicker) {
    return rawTicker == null ? "" : rawTicker.strip().toUpperCase(Locale.ROOT);
  }

  private String cikFor(String rawTicker) {
    String cik = ciksByTicker.get(normalizeTicker(rawTicker));
    if (cik == null) {
      throw new IllegalArgumentException(unknownTickerMessage);
    }
    return cik;
  }

  private static FilingSummary toFilingSummary(
      EdgarFiling edgarFiling, String cikWithoutLeadingZeros) {
    String documentUrl =
        ARCHIVES_URL
            + cikWithoutLeadingZeros
            + "/"
            + edgarFiling.accessionNumber().replace("-", "")
            + "/"
            + edgarFiling.primaryDocument();
    return new FilingSummary(
        edgarFiling.form(),
        edgarFiling.filingDate(),
        edgarFiling.reportDate(),
        edgarFiling.accessionNumber(),
        documentUrl);
  }
}
