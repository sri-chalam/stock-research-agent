package info.saagar.stockagent.filings.tool;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.config.RagProperties;
import info.saagar.stockagent.filings.provider.EdgarClient;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FilingsException;
import info.saagar.stockagent.filings.provider.XbrlFact;
import info.saagar.stockagent.filings.rag.FilingChunkMetadata;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// SEC filings tools served over MCP.
///
/// `listFilings` and `getCompanyFacts` call EDGAR; `searchFilings` reads only the chunk store that
/// ingestion fills. Only the tickers configured in `filings.tickers` are served. Errors are thrown,
/// never returned: the MCP server turns a thrown exception into a tool result with `isError: true`
/// and the exception message as its text. A returned error string would be reported to the agent as
/// a success.
@Component
public class FilingsTools {

  private static final Logger log = LoggerFactory.getLogger(FilingsTools.class);

  static final int MAX_FILINGS = 10;
  private static final String SEARCH_UNAVAILABLE_MESSAGE =
      "Filing search is unavailable; try again shortly.";
  private static final Set<String> SUPPORTED_FORM_TYPES = Set.of("10-K", "10-Q", "8-K");
  private static final String FORM_TYPE_MESSAGE =
      "formType is required and must be one of: 10-K, 10-Q, 8-K.";
  static final int MAX_FISCAL_YEARS = 3;
  private static final long MIN_FISCAL_YEAR_DAYS = 300;
  private static final Set<String> ALL_CONCEPT_NAMES =
      Arrays.stream(HeadlineMetric.values())
          .flatMap(headlineMetric -> headlineMetric.conceptNames().stream())
          .collect(Collectors.toUnmodifiableSet());

  private final EdgarClient edgarClient;
  private final EmbeddingModel embeddingModel;
  private final EmbeddingStore<TextSegment> filingChunkStore;
  private final int searchMaxResults;
  private final Map<String, String> ciksByTicker;
  private final String unknownTickerMessage;

  /// Creates the tools.
  ///
  /// @param edgarClient source of filings
  /// @param filingsProperties holds the supported tickers and their CIKs
  /// @param embeddingModel embeds `searchFilings` queries; the model ingestion embedded chunks with
  /// @param filingChunkStore the ingested filing chunks
  /// @param ragProperties holds the number of chunks `searchFilings` returns
  public FilingsTools(
      EdgarClient edgarClient,
      FilingsProperties filingsProperties,
      EmbeddingModel embeddingModel,
      EmbeddingStore<TextSegment> filingChunkStore,
      RagProperties ragProperties) {
    this.edgarClient = edgarClient;
    this.embeddingModel = embeddingModel;
    this.filingChunkStore = filingChunkStore;
    this.searchMaxResults = ragProperties.searchMaxResults();
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
    return edgarClient.fetchRecentFilings(cik).stream()
        .filter(edgarFiling -> normalizedFormType.equals(edgarFiling.form()))
        .limit(MAX_FILINGS)
        .map(edgarFiling -> toFilingSummary(edgarFiling, cik))
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

  /// Searches the ingested narrative sections of a company's filings for the chunks most similar to
  /// a query.
  ///
  /// Never calls EDGAR. There is no minimum score: the model sees each chunk's score.
  ///
  /// @param ticker a configured ticker, any case, e.g. `aapl`
  /// @param query what to look for, e.g. `supply-chain risks`
  /// @return up to `filings.rag.search-max-results` chunks, most similar first; empty when nothing
  ///   is ingested for the ticker
  /// @throws IllegalArgumentException when the ticker is not configured
  /// @throws IllegalStateException when embedding the query or searching the store fails
  @Tool(
      "Search the narrative sections (such as Item 1A Risk Factors) of a US company's recent"
          + " 10-K and 10-Q filings and return the most relevant text chunks. Each chunk has its"
          + " text, form, fiscalPeriod (end of the period the filing covers), section,"
          + " accessionNumber, a sec.gov url, and a similarity score from 0 to 1, so it can be"
          + " cited. Use it for questions about what a filing says, such as risks. Only a fixed"
          + " set of 10 large US companies is supported; another ticker returns an error listing"
          + " the supported ones.")
  public List<FilingChunk> searchFilings(
      @P("Ticker symbol, e.g. AAPL") String ticker,
      @P("What to search for, e.g. supply-chain risks") String query) {
    String normalizedTicker = normalizeTicker(ticker);
    requireConfiguredTicker(normalizedTicker);
    long startNanos = System.nanoTime();
    List<EmbeddingMatch<TextSegment>> chunkMatches;
    long embedDurationTimeMs;
    try {
      Embedding queryEmbedding = embeddingModel.embed(query).content();
      embedDurationTimeMs = elapsedMillis(startNanos);
      EmbeddingSearchRequest searchRequest =
          EmbeddingSearchRequest.builder()
              .queryEmbedding(queryEmbedding)
              .maxResults(searchMaxResults)
              .filter(metadataKey(FilingChunkMetadata.TICKER).isEqualTo(normalizedTicker))
              .build();
      chunkMatches = filingChunkStore.search(searchRequest).matches();
    } catch (RuntimeException searchException) {
      // McpServer turns a thrown exception into a tool error without logging it. A store
      // exception's message can carry SQL and vectors, so neither it nor the cause is passed on.
      log.atError()
          .addKeyValue("eventName", "filingSearchFailed")
          .addKeyValue(
              "eventData",
              Map.of(
                  "ticker",
                  normalizedTicker,
                  "exceptionType",
                  searchException.getClass().getName(),
                  "exceptionMessageSuppressed",
                  true,
                  "durationTimeMs",
                  elapsedMillis(startNanos)))
          .log("Filing search failed");
      throw new IllegalStateException(SEARCH_UNAVAILABLE_MESSAGE);
    }
    log.atInfo()
        .addKeyValue("eventName", "filingSearchCompleted")
        .addKeyValue(
            "eventData",
            Map.of(
                "ticker",
                normalizedTicker,
                "queryChars",
                query == null ? 0 : query.length(),
                "resultCount",
                chunkMatches.size(),
                "topScore",
                chunkMatches.isEmpty() ? 0.0 : chunkMatches.getFirst().score(),
                "embedDurationTimeMs",
                embedDurationTimeMs,
                "completeExecDurationTimeMs",
                elapsedMillis(startNanos)))
        .log("Filing search completed");
    return chunkMatches.stream().map(FilingsTools::toFilingChunk).toList();
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private static FilingChunk toFilingChunk(EmbeddingMatch<TextSegment> chunkMatch) {
    Metadata chunkMetadata = chunkMatch.embedded().metadata();
    return new FilingChunk(
        chunkMatch.embedded().text(),
        chunkMetadata.getString(FilingChunkMetadata.FORM),
        chunkMetadata.getString(FilingChunkMetadata.FISCAL_PERIOD),
        chunkMetadata.getString(FilingChunkMetadata.SECTION),
        chunkMetadata.getString(FilingChunkMetadata.ACCESSION_NUMBER),
        chunkMetadata.getString(FilingChunkMetadata.URL),
        chunkMatch.score());
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

  private void requireConfiguredTicker(String rawTicker) {
    cikFor(rawTicker);
  }

  private String cikFor(String rawTicker) {
    String cik = ciksByTicker.get(normalizeTicker(rawTicker));
    if (cik == null) {
      throw new IllegalArgumentException(unknownTickerMessage);
    }
    return cik;
  }

  private static FilingSummary toFilingSummary(EdgarFiling edgarFiling, String cik) {
    return new FilingSummary(
        edgarFiling.form(),
        edgarFiling.filingDate(),
        edgarFiling.reportDate(),
        edgarFiling.accessionNumber(),
        edgarFiling.documentUrl(cik));
  }
}
