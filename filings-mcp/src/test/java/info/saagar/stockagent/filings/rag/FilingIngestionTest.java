package info.saagar.stockagent.filings.rag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.config.RagProperties;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FakeEdgarClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Ingestion from a fake EDGAR into an in-memory store, with the real embedding model.
class FilingIngestionTest {

  private static final EmbeddingModel EMBEDDING_MODEL = new BgeSmallEnV15QuantizedEmbeddingModel();
  private static final String AAPL_CIK = "0000320193";
  private static final int CHUNK_SIZE_CHARS = 1600;
  private static final EdgarFiling LATEST_10K =
      new EdgarFiling("10-K", "2026-10-30", "2026-09-26", "0000320193-26-000101", "syn-10k.htm");
  private static final EdgarFiling OLDER_10K =
      new EdgarFiling("10-K", "2025-10-31", "2025-09-27", "0000320193-25-000079", "old-10k.htm");
  private static final List<EdgarFiling> RECENT_FILINGS =
      List.of(
          new EdgarFiling("8-K", "2026-11-01", "2026-11-01", "0000320193-26-000102", "8k.htm"),
          LATEST_10K,
          new EdgarFiling("10-Q", "2026-08-01", "2026-06-27", "0000320193-26-000090", "10q.htm"),
          OLDER_10K);
  private static final String LATEST_10K_URL = LATEST_10K.documentUrl(AAPL_CIK);

  /// About 6,000 characters of Item 1A, so the section splits into several chunks.
  private static final String[] LONG_RISK_FACTORS =
      Collections.nCopies(
              40,
              "Global economic conditions could materially adversely affect demand for the"
                  + " Company's synthetic widgets.")
          .toArray(String[]::new);

  private final InMemoryEmbeddingStore<TextSegment> filingChunkStore =
      new InMemoryEmbeddingStore<>();

  private FilingIngestion filingIngestion(
      FakeEdgarClient fakeEdgarClient, List<String> ingestTickers) {
    Map<String, String> ciksByTicker = new LinkedHashMap<>();
    ciksByTicker.put("AAPL", AAPL_CIK);
    ciksByTicker.put("MSFT", "0000789019");
    return new FilingIngestion(
        fakeEdgarClient,
        new FilingsProperties(null, null, null, ciksByTicker),
        new RagProperties(ingestTickers, CHUNK_SIZE_CHARS, 200, 5, null),
        EMBEDDING_MODEL,
        filingChunkStore);
  }

  private static FakeEdgarClient fakeEdgarServingTheLatest10K() {
    return new FakeEdgarClient(
        RECENT_FILINGS, Map.of(LATEST_10K_URL, SyntheticFilings.tenKHtml(LONG_RISK_FACTORS)));
  }

  private List<TextSegment> storedChunks() {
    return filingChunkStore
        .search(
            EmbeddingSearchRequest.builder()
                .queryEmbedding(EMBEDDING_MODEL.embed("any query").content())
                .maxResults(1_000)
                .build())
        .matches()
        .stream()
        .map(EmbeddingMatch::embedded)
        .toList();
  }

  @Test
  void storesItem1AOfTheLatest10KAsChunksWithCitationMetadata() {
    FakeEdgarClient fakeEdgarClient = fakeEdgarServingTheLatest10K();

    filingIngestion(fakeEdgarClient, List.of("aapl")).ingestConfiguredTickers();

    List<TextSegment> storedChunks = storedChunks();
    assertThat(fakeEdgarClient.fetchedDocumentUrls()).containsExactly(LATEST_10K_URL);
    assertThat(storedChunks).hasSizeGreaterThan(1);
    for (TextSegment storedChunk : storedChunks) {
      Metadata chunkMetadata = storedChunk.metadata();
      assertThat(chunkMetadata.getString(FilingChunkMetadata.TICKER)).isEqualTo("AAPL");
      assertThat(chunkMetadata.getString(FilingChunkMetadata.FORM)).isEqualTo("10-K");
      assertThat(chunkMetadata.getString(FilingChunkMetadata.FISCAL_PERIOD))
          .isEqualTo("2026-09-26");
      assertThat(chunkMetadata.getString(FilingChunkMetadata.SECTION)).isEqualTo("Item 1A");
      assertThat(chunkMetadata.getString(FilingChunkMetadata.ACCESSION_NUMBER))
          .isEqualTo("0000320193-26-000101");
      assertThat(chunkMetadata.getString(FilingChunkMetadata.URL))
          .isEqualTo(
              "https://www.sec.gov/Archives/edgar/data/320193/000032019326000101/syn-10k.htm");
    }
  }

  @Test
  void noChunkExceedsTheChunkSizeAndOnlyItem1ATextIsStored() {
    filingIngestion(fakeEdgarServingTheLatest10K(), List.of("AAPL")).ingestConfiguredTickers();

    List<TextSegment> storedChunks = storedChunks();
    assertThat(storedChunks)
        .allSatisfy(
            storedChunk ->
                assertThat(storedChunk.text()).hasSizeLessThanOrEqualTo(CHUNK_SIZE_CHARS));
    assertThat(storedChunks)
        .anySatisfy(
            storedChunk ->
                assertThat(storedChunk.text()).contains(SyntheticFilings.SUPPLY_CHAIN_RISK));
    assertThat(storedChunks)
        .noneSatisfy(
            storedChunk ->
                assertThat(storedChunk.text()).contains(SyntheticFilings.MANAGEMENT_DISCUSSION));
  }

  @Test
  void ingestingAgainReplacesTheFilingsChunksInsteadOfDuplicatingThem() {
    FilingIngestion filingIngestion =
        filingIngestion(fakeEdgarServingTheLatest10K(), List.of("AAPL"));
    filingIngestion.ingestConfiguredTickers();
    int firstRunChunkCount = storedChunks().size();

    filingIngestion.ingestConfiguredTickers();

    assertThat(storedChunks()).hasSize(firstRunChunkCount);
  }

  @Test
  void aFailedTickerDoesNotStopTheOthers() {
    filingIngestion(fakeEdgarServingTheLatest10K(), List.of("MSFT", "AAPL"))
        .ingestConfiguredTickers();

    assertThat(storedChunks())
        .extracting(storedChunk -> storedChunk.metadata().getString(FilingChunkMetadata.TICKER))
        .isNotEmpty()
        .containsOnly("AAPL");
  }
}
