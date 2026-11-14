package info.saagar.stockagent.filings.rag;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import info.saagar.stockagent.common.logging.LoggingContextFilter;
import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.config.RagProperties;
import info.saagar.stockagent.filings.provider.EdgarClient;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FilingsException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/// Fills the chunk store on startup: for each ticker in `filings.rag.ingest-tickers`, downloads the
/// latest 10-K, extracts Item 1A, splits it into chunks and embeds them.
///
/// Runs on a virtual thread so the server answers `listFilings` and `getCompanyFacts` while it
/// works. A filing's old chunks are removed before its new ones are added, so restarting re-ingests
/// without duplicates. One ticker's failure is logged and the next ticker is ingested.
@Component
public class FilingIngestion implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(FilingIngestion.class);

  private static final String TEN_K_FORM = "10-K";

  private final EdgarClient edgarClient;
  private final Map<String, String> ciksByTicker;
  private final RagProperties ragProperties;
  private final EmbeddingModel embeddingModel;
  private final EmbeddingStore<TextSegment> filingChunkStore;
  private final DocumentSplitter sectionSplitter;

  /// Creates the ingestion.
  ///
  /// @param edgarClient source of filings and their documents
  /// @param filingsProperties holds the supported tickers and their CIKs
  /// @param ragProperties tickers to ingest and the chunk size and overlap
  /// @param embeddingModel embeds the chunks
  /// @param filingChunkStore where the chunks are stored
  public FilingIngestion(
      EdgarClient edgarClient,
      FilingsProperties filingsProperties,
      RagProperties ragProperties,
      EmbeddingModel embeddingModel,
      EmbeddingStore<TextSegment> filingChunkStore) {
    this.edgarClient = edgarClient;
    this.ciksByTicker = new LinkedHashMap<>();
    filingsProperties
        .tickers()
        .forEach((ticker, cik) -> ciksByTicker.put(ticker.toUpperCase(Locale.ROOT), cik));
    this.ragProperties = ragProperties;
    this.embeddingModel = embeddingModel;
    this.filingChunkStore = filingChunkStore;
    this.sectionSplitter =
        DocumentSplitters.recursive(
            ragProperties.chunkSizeChars(), ragProperties.chunkOverlapChars());
  }

  /// Starts ingestion in the background and returns at once.
  ///
  /// @param applicationArguments unused
  @Override
  public void run(ApplicationArguments applicationArguments) {
    Thread.ofVirtual().name("filing-ingestion").start(this::ingestInNewLoggingContext);
  }

  /// Gives the run its own correlation and trace IDs: a virtual thread inherits no MDC, and startup
  /// has no request whose IDs it could copy.
  private void ingestInNewLoggingContext() {
    MDC.put(LoggingContextFilter.CORRELATION_ID_KEY, UUID.randomUUID().toString());
    MDC.put(LoggingContextFilter.TRACE_ID_KEY, UUID.randomUUID().toString());
    try {
      ingestConfiguredTickers();
    } finally {
      MDC.clear();
    }
  }

  /// Ingests the latest 10-K of every configured ticker, one after another, on the calling thread.
  ///
  /// @return the number of chunks stored
  public int ingestConfiguredTickers() {
    long startNanos = System.nanoTime();
    List<String> ingestTickers = ragProperties.ingestTickers();
    log.atInfo()
        .addKeyValue("eventName", "filingIngestionStarted")
        .addKeyValue(
            "eventData",
            Map.of(
                "ingestTickers",
                ingestTickers,
                "chunkSizeChars",
                ragProperties.chunkSizeChars(),
                "chunkOverlapChars",
                ragProperties.chunkOverlapChars()))
        .log("Filing ingestion started");
    int totalChunkCount = 0;
    int failedTickerCount = 0;
    for (String ingestTicker : ingestTickers) {
      String normalizedTicker = ingestTicker.strip().toUpperCase(Locale.ROOT);
      try {
        totalChunkCount += ingestLatest10K(normalizedTicker);
      } catch (RuntimeException ingestionException) {
        failedTickerCount++;
        logTickerFailed(normalizedTicker, ingestionException);
      }
    }
    log.atInfo()
        .addKeyValue("eventName", "filingIngestionCompleted")
        .addKeyValue(
            "eventData",
            Map.of(
                "tickerCount",
                ingestTickers.size(),
                "failedTickerCount",
                failedTickerCount,
                "totalChunkCount",
                totalChunkCount,
                "completeExecDurationTimeMs",
                elapsedMillis(startNanos)))
        .log("Filing ingestion completed");
    return totalChunkCount;
  }

  private int ingestLatest10K(String ticker) {
    long startNanos = System.nanoTime();
    String cik = ciksByTicker.get(ticker);
    if (cik == null) {
      throw new IllegalArgumentException("Ingest ticker is not in filings.tickers.");
    }
    Optional<EdgarFiling> latest10K =
        edgarClient.fetchRecentFilings(cik).stream()
            .filter(edgarFiling -> TEN_K_FORM.equals(edgarFiling.form()))
            .findFirst();
    if (latest10K.isEmpty()) {
      logTickerSkipped(ticker, null, "noRecent10K");
      return 0;
    }
    EdgarFiling filing = latest10K.get();
    String documentUrl = filing.documentUrl(cik);
    String filingHtml = edgarClient.fetchFilingDocument(documentUrl);
    long downloadDurationTimeMs = elapsedMillis(startNanos);

    Optional<String> riskFactors = FilingSections.riskFactors(filingHtml);
    if (riskFactors.isEmpty()) {
      logTickerSkipped(ticker, filing.accessionNumber(), "sectionNotFound");
      return 0;
    }
    Metadata filingMetadata =
        new Metadata()
            .put(FilingChunkMetadata.TICKER, ticker)
            .put(FilingChunkMetadata.FORM, filing.form())
            .put(FilingChunkMetadata.FISCAL_PERIOD, filing.reportDate())
            .put(FilingChunkMetadata.SECTION, FilingSections.RISK_FACTORS_SECTION)
            .put(FilingChunkMetadata.ACCESSION_NUMBER, filing.accessionNumber())
            .put(FilingChunkMetadata.URL, documentUrl);
    List<TextSegment> filingChunks =
        sectionSplitter.split(Document.from(riskFactors.get(), filingMetadata));

    long embedStartNanos = System.nanoTime();
    List<Embedding> chunkEmbeddings = embeddingModel.embedAll(filingChunks).content();
    long embedDurationTimeMs = elapsedMillis(embedStartNanos);

    filingChunkStore.removeAll(
        metadataKey(FilingChunkMetadata.ACCESSION_NUMBER).isEqualTo(filing.accessionNumber()));
    filingChunkStore.addAll(chunkEmbeddings, filingChunks);

    Map<String, Object> filingData = new LinkedHashMap<>();
    filingData.put("ticker", ticker);
    filingData.put("form", filing.form());
    filingData.put("accessionNumber", filing.accessionNumber());
    filingData.put("fiscalPeriod", filing.reportDate());
    filingData.put("section", FilingSections.RISK_FACTORS_SECTION);
    filingData.put("sectionChars", riskFactors.get().length());
    filingData.put("chunkCount", filingChunks.size());
    filingData.put("downloadDurationTimeMs", downloadDurationTimeMs);
    filingData.put("embedDurationTimeMs", embedDurationTimeMs);
    filingData.put("completeExecDurationTimeMs", elapsedMillis(startNanos));
    log.atInfo()
        .addKeyValue("eventName", "filingIngested")
        .addKeyValue("eventData", filingData)
        .log("Filing section ingested");
    return filingChunks.size();
  }

  private static void logTickerSkipped(String ticker, String accessionNumber, String skipReason) {
    Map<String, Object> skipData = new LinkedHashMap<>();
    skipData.put("ticker", ticker);
    skipData.put("accessionNumber", accessionNumber);
    skipData.put("section", FilingSections.RISK_FACTORS_SECTION);
    skipData.put("skipReason", skipReason);
    log.atWarn()
        .addKeyValue("eventName", "filingIngestionSkipped")
        .addKeyValue("eventData", skipData)
        .log("Filing ingestion skipped a ticker");
  }

  /// Logs a ticker's failure. Only this class's and EDGAR's fixed messages are logged: a store
  /// exception's message can carry SQL and vectors.
  private static void logTickerFailed(String ticker, RuntimeException ingestionException) {
    Map<String, Object> failureData = new LinkedHashMap<>();
    failureData.put("ticker", ticker);
    failureData.put("exceptionType", ingestionException.getClass().getName());
    if (ingestionException instanceof FilingsException
        || ingestionException instanceof IllegalArgumentException) {
      failureData.put("exceptionMessage", ingestionException.getMessage());
    } else {
      failureData.put("exceptionMessageSuppressed", true);
    }
    log.atError()
        .addKeyValue("eventName", "filingIngestionFailed")
        .addKeyValue("eventData", failureData)
        .log("Filing ingestion failed for a ticker");
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
