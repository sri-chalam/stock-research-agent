package info.saagar.stockagent.filings.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.config.RagProperties;
import info.saagar.stockagent.filings.provider.FakeEdgarClient;
import info.saagar.stockagent.filings.rag.FilingChunkMetadata;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// `searchFilings` over the real embedding model and an in-memory store of synthetic chunks.
class SearchFilingsTest {

  private static final EmbeddingModel EMBEDDING_MODEL = new BgeSmallEnV15QuantizedEmbeddingModel();
  private static final String AAPL_ACCESSION = "0000320193-26-000001";
  private static final String MSFT_ACCESSION = "0000789019-26-000001";
  private static final String AAPL_SUPPLY_CHAIN_CHUNK =
      "The Company depends on single-source component suppliers in Asia. A disruption in the"
          + " supply chain could delay shipments and raise costs.";
  private static final List<String> OTHER_AAPL_CHUNKS =
      List.of(
          "Competition in the synthetic widget market is intense.",
          "The Company's stock price may be volatile.",
          "Changes in tax laws could increase the Company's effective tax rate.",
          "The Company is subject to litigation over its intellectual property.",
          "Foreign exchange rates affect the Company's reported revenue.",
          "Cyber attacks could disrupt the Company's online services.");

  private final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
  private final Tracer tracer =
      SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
          .build()
          .get("search-filings-test");
  private final InMemoryEmbeddingStore<TextSegment> filingChunkStore =
      new InMemoryEmbeddingStore<>();
  private final FakeEdgarClient fakeEdgarClient = new FakeEdgarClient(List.of());
  private final FilingsTools filingsTools =
      new FilingsTools(
          fakeEdgarClient,
          new FilingsProperties(
              null, null, null, Map.of("AAPL", "0000320193", "MSFT", "0000789019")),
          EMBEDDING_MODEL,
          filingChunkStore,
          new RagProperties(List.of(), 1600, 200, 5, null),
          tracer);

  private void storeChunk(String ticker, String accessionNumber, String chunkText) {
    TextSegment filingChunk =
        TextSegment.from(
            chunkText,
            new Metadata()
                .put(FilingChunkMetadata.TICKER, ticker)
                .put(FilingChunkMetadata.FORM, "10-K")
                .put(FilingChunkMetadata.FISCAL_PERIOD, "2026-09-26")
                .put(FilingChunkMetadata.SECTION, "Item 1A")
                .put(FilingChunkMetadata.ACCESSION_NUMBER, accessionNumber)
                .put(
                    FilingChunkMetadata.URL,
                    "https://www.sec.gov/Archives/edgar/data/1/" + accessionNumber + "/syn.htm"));
    filingChunkStore.add(EMBEDDING_MODEL.embed(filingChunk).content(), filingChunk);
  }

  private void storeSyntheticChunks() {
    storeChunk("AAPL", AAPL_ACCESSION, AAPL_SUPPLY_CHAIN_CHUNK);
    OTHER_AAPL_CHUNKS.forEach(chunkText -> storeChunk("AAPL", AAPL_ACCESSION, chunkText));
    storeChunk("MSFT", MSFT_ACCESSION, "Supply chain disruptions could delay MSFT hardware.");
  }

  @Test
  void returnsTheTopFiveChunksOfTheTickerMostRelevantFirst() {
    storeSyntheticChunks();

    List<FilingChunk> filingChunks =
        filingsTools.searchFilings(" aapl ", "What supply-chain risks does the company face?");

    assertThat(filingChunks).hasSize(5);
    assertThat(filingChunks.getFirst().text()).isEqualTo(AAPL_SUPPLY_CHAIN_CHUNK);
    assertThat(filingChunks).extracting(FilingChunk::accessionNumber).containsOnly(AAPL_ACCESSION);
    assertThat(filingChunks)
        .extracting(FilingChunk::score)
        .isSortedAccordingTo((higher, lower) -> Double.compare(lower, higher));
  }

  @Test
  void eachChunkCarriesEveryCitationField() {
    storeSyntheticChunks();

    FilingChunk topChunk = filingsTools.searchFilings("AAPL", "supply chain").getFirst();

    assertThat(topChunk.form()).isEqualTo("10-K");
    assertThat(topChunk.fiscalPeriod()).isEqualTo("2026-09-26");
    assertThat(topChunk.section()).isEqualTo("Item 1A");
    assertThat(topChunk.accessionNumber()).isEqualTo(AAPL_ACCESSION);
    assertThat(topChunk.url())
        .isEqualTo("https://www.sec.gov/Archives/edgar/data/1/" + AAPL_ACCESSION + "/syn.htm");
    assertThat(topChunk.score()).isBetween(0.0, 1.0);
  }

  @Test
  void neverCallsEdgar() {
    storeSyntheticChunks();

    filingsTools.searchFilings("MSFT", "supply chain");

    assertThat(fakeEdgarClient.lastRequestedCik()).isNull();
    assertThat(fakeEdgarClient.fetchedDocumentUrls()).isEmpty();
  }

  @Test
  void queryEmbeddingIsASpanUnderTheCurrentSpan() {
    storeSyntheticChunks();
    Span toolCallSpan = tracer.spanBuilder("searchFilings").startSpan();

    try (Scope toolCallScope = toolCallSpan.makeCurrent()) {
      filingsTools.searchFilings("AAPL", "supply chain");
    } finally {
      toolCallSpan.end();
    }

    SpanData embeddingSpan = spanExporter.getFinishedSpanItems().getFirst();
    assertThat(embeddingSpan.getName()).isEqualTo(FilingsTools.EMBEDDING_SPAN_NAME);
    assertThat(embeddingSpan.getParentSpanId())
        .isEqualTo(toolCallSpan.getSpanContext().getSpanId());
  }

  @Test
  void unknownTickerIsTheSameErrorAsTheOtherTools() {
    assertThatThrownBy(() -> filingsTools.searchFilings("ZZZZ", "supply chain"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Filings are available only for: ");
  }
}
