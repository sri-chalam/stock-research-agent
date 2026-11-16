package info.saagar.stockagent.filings.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import info.saagar.stockagent.filings.config.FilingsProperties;
import info.saagar.stockagent.filings.config.RagProperties;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FakeEdgarClient;
import info.saagar.stockagent.filings.provider.XbrlFact;
import java.math.BigDecimal;
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
    return new FilingsTools(
        fakeEdgarClient,
        new FilingsProperties(null, null, null, ciksByTicker),
        null,
        new InMemoryEmbeddingStore<>(),
        new RagProperties(List.of(), 1600, 200, 5, null));
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

  /// A full-fiscal-year (Oct-Sep) 10-K fact, filed in November of `filedFiscalYear`.
  private static XbrlFact annualFact(
      String concept, int fiscalYear, long value, int filedFiscalYear) {
    return new XbrlFact(
        concept,
        "USD",
        (fiscalYear - 1) + "-10-01",
        fiscalYear + "-09-30",
        BigDecimal.valueOf(value),
        "10-K",
        "FY",
        filedFiscalYear + "-11-01",
        accessionNumberFor(filedFiscalYear));
  }

  private static String accessionNumberFor(int filedFiscalYear) {
    return "0000320193-%d-000001".formatted(filedFiscalYear % 100);
  }

  private static CompanyFacts companyFactsFor(List<XbrlFact> companyFacts) {
    return filingsTools(new FakeEdgarClient(List.of(), companyFacts)).getCompanyFacts(" aapl ");
  }

  @Test
  void picksTheThreeLatestFiscalYearsCitingThe10KThatFirstReportedEach() {
    List<XbrlFact> companyFacts = new ArrayList<>();
    for (int fiscalYear = 2022; fiscalYear <= 2025; fiscalYear++) {
      companyFacts.add(annualFact("NetIncomeLoss", fiscalYear, fiscalYear * 10L, fiscalYear));
      companyFacts.add(annualFact("NetIncomeLoss", fiscalYear, fiscalYear * 10L, fiscalYear + 1));
    }

    CompanyFacts facts = companyFactsFor(companyFacts);

    assertThat(facts.ticker()).isEqualTo("AAPL");
    assertThat(facts.metricValues())
        .containsExactly(
            new MetricValue(
                "netIncome",
                "2025-09-30",
                BigDecimal.valueOf(20250),
                "USD",
                "10-K",
                "2025-11-01",
                accessionNumberFor(2025)),
            new MetricValue(
                "netIncome",
                "2024-09-30",
                BigDecimal.valueOf(20240),
                "USD",
                "10-K",
                "2024-11-01",
                accessionNumberFor(2024)),
            new MetricValue(
                "netIncome",
                "2023-09-30",
                BigDecimal.valueOf(20230),
                "USD",
                "10-K",
                "2023-11-01",
                accessionNumberFor(2023)));
  }

  @Test
  void skipsQuarterlyValuesReportedInA10KAndValuesFrom10Qs() {
    XbrlFact fourthQuarterIn10K =
        new XbrlFact(
            "NetIncomeLoss",
            "USD",
            "2025-07-01",
            "2025-09-30",
            BigDecimal.valueOf(9),
            "10-K",
            "FY",
            "2025-11-01",
            accessionNumberFor(2025));
    XbrlFact laterQuarterIn10Q =
        new XbrlFact(
            "NetIncomeLoss",
            "USD",
            "2025-10-01",
            "2025-12-31",
            BigDecimal.valueOf(7),
            "10-Q",
            "Q1",
            "2026-02-01",
            "0000320193-26-000002");

    CompanyFacts facts =
        companyFactsFor(
            List.of(
                fourthQuarterIn10K,
                annualFact("NetIncomeLoss", 2025, 40, 2025),
                laterQuarterIn10Q));

    assertThat(facts.metricValues())
        .extracting(MetricValue::fiscalPeriodEnd, MetricValue::value)
        .containsExactly(tuple("2025-09-30", BigDecimal.valueOf(40)));
  }

  @Test
  void fallsBackToTheSecondRevenueTagWhenRevenuesIsAbsent() {
    CompanyFacts facts =
        companyFactsFor(
            List.of(
                annualFact(
                    "RevenueFromContractWithCustomerExcludingAssessedTax", 2025, 100, 2025)));

    assertThat(facts.metricValues())
        .extracting(MetricValue::metric, MetricValue::value)
        .containsExactly(tuple("revenue", BigDecimal.valueOf(100)));
  }

  @Test
  void prefersTheRevenueTagWithTheLatestFiscalYearOverAStaleEarlierTag() {
    CompanyFacts facts =
        companyFactsFor(
            List.of(
                annualFact("Revenues", 2018, 1, 2018),
                annualFact(
                    "RevenueFromContractWithCustomerExcludingAssessedTax", 2025, 100, 2025)));

    assertThat(facts.metricValues())
        .extracting(MetricValue::fiscalPeriodEnd, MetricValue::value)
        .containsExactly(tuple("2025-09-30", BigDecimal.valueOf(100)));
  }

  @Test
  void leavesOutMetricsTheCompanyDoesNotReport() {
    XbrlFact totalAssets =
        new XbrlFact(
            "Assets",
            "USD",
            null,
            "2025-09-30",
            BigDecimal.valueOf(500),
            "10-K",
            "FY",
            "2025-11-01",
            accessionNumberFor(2025));

    CompanyFacts facts =
        companyFactsFor(List.of(annualFact("NetIncomeLoss", 2025, 40, 2025), totalAssets));

    assertThat(facts.metricValues())
        .extracting(MetricValue::metric)
        .containsExactly("netIncome", "totalAssets");
  }

  @Test
  void getCompanyFactsForAnUnknownTickerIsAnErrorListingTheConfiguredTickers() {
    FakeEdgarClient fakeEdgarClient = new FakeEdgarClient(List.of());

    assertThatThrownBy(() -> filingsTools(fakeEdgarClient).getCompanyFacts("ZZZZ"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Filings are available only for: AAPL, MSFT.");
    assertThat(fakeEdgarClient.lastRequestedCik()).isNull();
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
