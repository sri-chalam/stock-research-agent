package info.saagar.stockagent.filings.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FakeEdgarClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FilingsToolsTest {

  private static final String MSFT_CIK = "0000789019";

  private static FilingsTools filingsTools(FakeEdgarClient fakeEdgarClient) {
    Map<String, String> ciksByTicker = new LinkedHashMap<>();
    ciksByTicker.put("AAPL", "0000320193");
    ciksByTicker.put("MSFT", MSFT_CIK);
    return new FilingsTools(fakeEdgarClient, new FilingsProperties(null, null, null, ciksByTicker));
  }

  private static EdgarFiling syntheticFiling(String form, int filingNumber) {
    return new EdgarFiling(
        form,
        "2026-%02d-01".formatted(filingNumber),
        "2026-%02d-30".formatted(filingNumber),
        "0000950170-26-%06d".formatted(filingNumber),
        "syn-%d.htm".formatted(filingNumber));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"10-K/A", "S-1", "annual"})
  void missingOrUnsupportedFormTypeIsAnError(String formType) {
    FakeEdgarClient fakeEdgarClient = new FakeEdgarClient(List.of());

    assertThatThrownBy(() -> filingsTools(fakeEdgarClient).listFilings("MSFT", formType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("formType is required and must be one of: 10-K, 10-Q, 8-K.");
    assertThat(fakeEdgarClient.lastRequestedCik()).isNull();
  }

  @Test
  void unknownTickerIsAnErrorListingTheConfiguredTickers() {
    FakeEdgarClient fakeEdgarClient = new FakeEdgarClient(List.of());

    assertThatThrownBy(() -> filingsTools(fakeEdgarClient).listFilings("ZZZZ", "10-K"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Filings are available only for: AAPL, MSFT.");
    assertThat(fakeEdgarClient.lastRequestedCik()).isNull();
  }

  @Test
  void returnsAtMostTenFilingsOfTheRequestedFormNewestFirst() {
    List<EdgarFiling> recentFilings = new ArrayList<>();
    IntStream.rangeClosed(1, 12)
        .forEach(
            filingNumber -> {
              recentFilings.add(syntheticFiling("10-K", filingNumber));
              recentFilings.add(syntheticFiling("8-K", filingNumber));
            });
    FakeEdgarClient fakeEdgarClient = new FakeEdgarClient(recentFilings);

    List<FilingSummary> filings = filingsTools(fakeEdgarClient).listFilings(" msft ", "10-k");

    assertThat(fakeEdgarClient.lastRequestedCik()).isEqualTo(MSFT_CIK);
    assertThat(filings).hasSize(10).extracting(FilingSummary::form).containsOnly("10-K");
    assertThat(filings.getFirst().accessionNumber()).isEqualTo("0000950170-26-000001");
    assertThat(filings.getLast().accessionNumber()).isEqualTo("0000950170-26-000010");
  }

  @Test
  void buildsTheSecGovDocumentUrl() {
    FakeEdgarClient fakeEdgarClient =
        new FakeEdgarClient(
            List.of(
                new EdgarFiling(
                    "10-K", "2025-07-30", "2025-06-30", "0000950170-25-100235", "syn-10k.htm")));

    List<FilingSummary> filings = filingsTools(fakeEdgarClient).listFilings("MSFT", "10-K");

    assertThat(filings)
        .containsExactly(
            new FilingSummary(
                "10-K",
                "2025-07-30",
                "2025-06-30",
                "0000950170-25-100235",
                "https://www.sec.gov/Archives/edgar/data/789019/000095017025100235/syn-10k.htm"));
  }
}
