package info.saagar.stockagent.filings.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.provider.EdgarClient;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FilingsException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
          + " report). Only a fixed set of about 20 large US companies is supported; another"
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

  private static String normalizeFormType(String rawFormType) {
    String normalizedFormType =
        rawFormType == null ? "" : rawFormType.strip().toUpperCase(Locale.ROOT);
    if (!SUPPORTED_FORM_TYPES.contains(normalizedFormType)) {
      throw new IllegalArgumentException(FORM_TYPE_MESSAGE);
    }
    return normalizedFormType;
  }

  private String cikFor(String rawTicker) {
    String normalizedTicker = rawTicker == null ? "" : rawTicker.strip().toUpperCase(Locale.ROOT);
    String cik = ciksByTicker.get(normalizedTicker);
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
