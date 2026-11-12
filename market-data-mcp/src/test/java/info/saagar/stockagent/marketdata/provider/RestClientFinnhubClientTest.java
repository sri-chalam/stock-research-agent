package info.saagar.stockagent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientFinnhubClientTest {

  private static final String FINNHUB_BASE_URL = "https://finnhub.test/api/v1";
  private static final String AAPL_QUOTE_URL = FINNHUB_BASE_URL + "/quote?symbol=AAPL";
  private static final String FINNHUB_API_KEY = "synthetic-finnhub-key";

  private final RestClient.Builder restClientBuilder =
      RestClient.builder().baseUrl(FINNHUB_BASE_URL);
  private final MockRestServiceServer finnhubServer =
      MockRestServiceServer.bindTo(restClientBuilder).build();
  private final RestClientFinnhubClient finnhubClient =
      new RestClientFinnhubClient(restClientBuilder.build(), FINNHUB_API_KEY);

  @Test
  void sendsKeyInHeaderOnlyAndReturnsTrimmedQuote() {
    finnhubServer
        .expect(requestTo(AAPL_QUOTE_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Finnhub-Token", FINNHUB_API_KEY))
        .andExpect(
            finnhubRequest ->
                assertThat(finnhubRequest.getURI().toString()).doesNotContain(FINNHUB_API_KEY))
        .andRespond(
            withSuccess(
                """
                {"c":189.42,"d":-0.7,"dp":-0.37,"h":191.05,"l":188.1,"o":190.2,"pc":190.12,\
                "t":1777645800}""",
                MediaType.APPLICATION_JSON));

    Optional<Quote> quote = finnhubClient.fetchQuote("AAPL");

    assertThat(quote)
        .contains(
            new Quote(
                "AAPL",
                new BigDecimal("189.42"),
                new BigDecimal("-0.7"),
                new BigDecimal("-0.37"),
                new BigDecimal("191.05"),
                new BigDecimal("188.1"),
                new BigDecimal("190.2"),
                new BigDecimal("190.12"),
                "2026-05-01T14:30:00Z"));
    finnhubServer.verify();
  }

  @Test
  void returnsEmptyWhenFinnhubHasNoQuoteForSymbol() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/quote?symbol=ZZZZ"))
        .andRespond(
            withSuccess(
                """
                {"c":0,"d":null,"dp":null,"h":0,"l":0,"o":0,"pc":0,"t":0}""",
                MediaType.APPLICATION_JSON));

    assertThat(finnhubClient.fetchQuote("ZZZZ")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "401, Market data provider rejected the request (authorization).",
    "403, Market data provider rejected the request (authorization).",
    "429, Market data rate limit reached; try again in a minute.",
    "500, Market data provider is unavailable.",
    "503, Market data provider is unavailable."
  })
  void mapsHttpErrorStatusToFixedMessage(int httpStatus, String expectedMessage) {
    finnhubServer
        .expect(requestTo(AAPL_QUOTE_URL))
        .andRespond(
            withStatus(HttpStatus.valueOf(httpStatus))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"upstream body " + FINNHUB_API_KEY + "\"}"));

    assertThatThrownBy(() -> finnhubClient.fetchQuote("AAPL"))
        .isInstanceOf(MarketDataException.class)
        .hasMessage(expectedMessage);
  }

  @Test
  void mapsTimeoutToFixedMessage() {
    finnhubServer
        .expect(requestTo(AAPL_QUOTE_URL))
        .andRespond(withException(new SocketTimeoutException("Read timed out")));

    assertThatThrownBy(() -> finnhubClient.fetchQuote("AAPL"))
        .isInstanceOf(MarketDataException.class)
        .hasMessage("Market data provider timed out.");
  }

  @Test
  void returnsTrimmedCompanyProfileAndIgnoresExtraFields() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/stock/profile2?symbol=AAPL"))
        .andExpect(header("X-Finnhub-Token", FINNHUB_API_KEY))
        .andRespond(
            withSuccess(
                """
                {"country":"US","currency":"USD","exchange":"NASDAQ","finnhubIndustry":"Technology",\
                "ipo":"1980-12-12","logo":"https://example.com/logo.png","marketCapitalization":2900000.5,\
                "name":"Synthetic Apple Inc","phone":"555-0100","shareOutstanding":15300.25,\
                "ticker":"AAPL","weburl":"https://example.com/"}""",
                MediaType.APPLICATION_JSON));

    assertThat(finnhubClient.fetchCompanyProfile("AAPL"))
        .contains(
            new CompanyProfile(
                "AAPL",
                "Synthetic Apple Inc",
                "NASDAQ",
                "Technology",
                "US",
                new BigDecimal("2900000.5"),
                new BigDecimal("15300.25"),
                "1980-12-12",
                "https://example.com/"));
    finnhubServer.verify();
  }

  @Test
  void returnsEmptyProfileWhenFinnhubHasNoneForSymbol() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/stock/profile2?symbol=ZZZZ"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(finnhubClient.fetchCompanyProfile("ZZZZ")).isEmpty();
  }

  @Test
  void returnsTenNewestCompanyNewsArticlesWithTrimmedFields() {
    finnhubServer
        .expect(
            requestTo(FINNHUB_BASE_URL + "/company-news?symbol=AAPL&from=2026-04-01&to=2026-04-30"))
        .andExpect(header("X-Finnhub-Token", FINNHUB_API_KEY))
        .andRespond(withSuccess(newsItemsJson(12), MediaType.APPLICATION_JSON));

    List<NewsArticle> articles =
        finnhubClient.fetchCompanyNews(
            "AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"));

    assertThat(articles).hasSize(10);
    assertThat(articles.getFirst())
        .isEqualTo(
            new NewsArticle(
                "Headline 12",
                "Synthetic Wire",
                "2026-05-01T14:30:12Z",
                "Summary 12",
                "https://example.com/news/12"));
    assertThat(articles.getLast().headline()).isEqualTo("Headline 3");
    finnhubServer.verify();
  }

  @Test
  void cutsLongNewsSummaryToAtMostThreeHundredCharacters() {
    String longSummary = "word ".repeat(100).strip();
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/news?category=general"))
        .andRespond(
            withSuccess(
                """
                [{"category":"top news","datetime":1777645800,"headline":"Long","id":1,\
                "image":"https://example.com/i.png","related":"","source":"Synthetic Wire",\
                "summary":"%s","url":"https://example.com/news/1"}]"""
                    .formatted(longSummary),
                MediaType.APPLICATION_JSON));

    String cutSummary =
        finnhubClient.fetchMarketNews(MarketNewsCategory.GENERAL).getFirst().summary();

    assertThat(cutSummary).hasSizeLessThanOrEqualTo(300).endsWith("word…");
    assertThat(longSummary).startsWith(cutSummary.substring(0, cutSummary.length() - 1));
  }

  @Test
  void returnsTenNewestMarketNewsArticles() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/news?category=merger"))
        .andRespond(withSuccess(newsItemsJson(15), MediaType.APPLICATION_JSON));

    List<NewsArticle> articles = finnhubClient.fetchMarketNews(MarketNewsCategory.MERGER);

    assertThat(articles)
        .extracting(NewsArticle::headline)
        .hasSize(10)
        .first()
        .isEqualTo("Headline 15");
  }

  @Test
  void returnsAtMostTwentyFiveEarliestEarningsEventsWithTrimmedFields() {
    String earningsRowsJson =
        IntStream.rangeClosed(1, 30)
            .mapToObj(
                rowNumber ->
                    """
                    {"date":"2026-05-%02d","epsActual":null,"epsEstimate":1.5,"hour":"amc",\
                    "quarter":2,"revenueActual":null,"revenueEstimate":1000000,"symbol":"SYN%d",\
                    "year":2026,"extraField":"ignored"}"""
                        .formatted(31 - rowNumber, rowNumber))
            .collect(Collectors.joining(",", "{\"earningsCalendar\":[", "]}"));
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/calendar/earnings?from=2026-05-01&to=2026-05-07"))
        .andRespond(withSuccess(earningsRowsJson, MediaType.APPLICATION_JSON));

    List<EarningsEvent> earningsEvents =
        finnhubClient.fetchEarningsCalendar(
            LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"), null);

    assertThat(earningsEvents).hasSize(25);
    assertThat(earningsEvents.getFirst())
        .isEqualTo(
            new EarningsEvent(
                "SYN30",
                "2026-05-01",
                "amc",
                2026,
                2,
                new BigDecimal("1.5"),
                null,
                new BigDecimal("1000000"),
                null));
    assertThat(earningsEvents.getLast().date()).isEqualTo("2026-05-25");
  }

  @Test
  void sendsSymbolToEarningsCalendarWhenGiven() {
    finnhubServer
        .expect(
            requestTo(
                FINNHUB_BASE_URL + "/calendar/earnings?from=2026-01-01&to=2026-06-30&symbol=AAPL"))
        .andRespond(withSuccess("{\"earningsCalendar\":[]}", MediaType.APPLICATION_JSON));

    assertThat(
            finnhubClient.fetchEarningsCalendar(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-30"), "AAPL"))
        .isEmpty();
    finnhubServer.verify();
  }

  @Test
  void returnsTheFixedMetricsAndIgnoresSeriesAndOtherMetrics() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/stock/metric?symbol=AAPL&metric=all"))
        .andExpect(header("X-Finnhub-Token", FINNHUB_API_KEY))
        .andRespond(
            withSuccess(
                """
                {"metric":{"10DayAverageTradingVolume":50.1,"52WeekHigh":199.62,\
                "52WeekHighDate":"2026-01-02","52WeekLow":164.08,"peTTM":29.5,"pb":45.1,\
                "epsTTM":6.4,"dividendYieldIndicatedAnnual":0.52,"beta":1.25,\
                "grossMarginTTM":45.2,"operatingMarginTTM":30.1,"netProfitMarginTTM":25.3,\
                "roeTTM":150.2,"totalDebt/totalEquityQuarterly":1.8},\
                "metricType":"all","series":{"annual":{"eps":[{"period":"2025-09-30","v":6.1}]}},\
                "symbol":"AAPL"}""",
                MediaType.APPLICATION_JSON));

    assertThat(finnhubClient.fetchBasicFinancials("AAPL"))
        .contains(
            new BasicFinancials(
                "AAPL",
                new BigDecimal("199.62"),
                new BigDecimal("164.08"),
                new BigDecimal("29.5"),
                new BigDecimal("45.1"),
                new BigDecimal("6.4"),
                new BigDecimal("0.52"),
                new BigDecimal("1.25"),
                new BigDecimal("45.2"),
                new BigDecimal("30.1"),
                new BigDecimal("25.3"),
                new BigDecimal("150.2"),
                new BigDecimal("1.8")));
    finnhubServer.verify();
  }

  @Test
  void returnsEmptyFinancialsWhenFinnhubHasNoMetrics() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/stock/metric?symbol=ZZZZ&metric=all"))
        .andRespond(
            withSuccess(
                """
                {"metric":{},"metricType":"all","series":{},"symbol":"ZZZZ"}""",
                MediaType.APPLICATION_JSON));

    assertThat(finnhubClient.fetchBasicFinancials("ZZZZ")).isEmpty();
  }

  @Test
  void mapsRateLimitOnNewEndpointsToFixedMessage() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/news?category=general"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(() -> finnhubClient.fetchMarketNews(MarketNewsCategory.GENERAL))
        .isInstanceOf(MarketDataException.class)
        .hasMessage("Market data rate limit reached; try again in a minute.");
  }

  /// Builds `articleCount` synthetic articles out of time order; article N is published N seconds
  /// after 2026-05-01T14:30:00Z. `articleCount` must not be a multiple of 7.
  private static String newsItemsJson(int articleCount) {
    return IntStream.range(0, articleCount)
        .map(position -> position * 7 % articleCount + 1)
        .mapToObj(
            articleNumber ->
                """
                {"category":"company","datetime":%d,"headline":"Headline %d","id":%d,\
                "image":"https://example.com/i.png","related":"AAPL","source":"Synthetic Wire",\
                "summary":"Summary %d","url":"https://example.com/news/%d"}"""
                    .formatted(
                        1777645800L + articleNumber,
                        articleNumber,
                        articleNumber,
                        articleNumber,
                        articleNumber))
        .collect(Collectors.joining(",", "[", "]"));
  }
}
