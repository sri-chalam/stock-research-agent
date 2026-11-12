package info.saagar.stockagent.marketdata.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/// [FinnhubClient] over Spring's `RestClient`.
///
/// The API key is sent in the `X-Finnhub-Token` header, never as a query parameter, so it stays out
/// of URLs and access logs. Results are trimmed here (MCP-2): only the fields in the result records
/// are read, news is cut to the 10 newest articles with short summaries, and the earnings calendar
/// to 25 rows. Each failure is logged once here as `finnhubCallFailed` and rethrown as a
/// [MarketDataException] with a fixed message. The MCP library turns that exception into a tool
/// error without logging it, so this is the only place it is logged.
public class RestClientFinnhubClient implements FinnhubClient {

  private static final Logger log = LoggerFactory.getLogger(RestClientFinnhubClient.class);

  private static final String TOKEN_HEADER = "X-Finnhub-Token";
  private static final String QUOTE_ENDPOINT = "/quote";
  private static final String COMPANY_PROFILE_ENDPOINT = "/stock/profile2";
  private static final String COMPANY_NEWS_ENDPOINT = "/company-news";
  private static final String MARKET_NEWS_ENDPOINT = "/news";
  private static final String EARNINGS_CALENDAR_ENDPOINT = "/calendar/earnings";
  private static final String BASIC_FINANCIALS_ENDPOINT = "/stock/metric";

  static final int MAX_NEWS_ARTICLES = 10;
  static final int MAX_SUMMARY_LENGTH = 300;
  static final int MAX_EARNINGS_EVENTS = 25;
  private static final String SUMMARY_CUT_MARKER = "…";

  static final String AUTHORIZATION_MESSAGE =
      "Market data provider rejected the request (authorization).";
  static final String RATE_LIMIT_MESSAGE = "Market data rate limit reached; try again in a minute.";
  static final String REJECTED_MESSAGE = "Market data provider rejected the request.";
  static final String UNAVAILABLE_MESSAGE = "Market data provider is unavailable.";
  static final String TIMED_OUT_MESSAGE = "Market data provider timed out.";

  private static final Comparator<FinnhubNewsItem> NEWEST_FIRST =
      Comparator.comparingLong(FinnhubNewsItem::datetime).reversed();
  private static final Comparator<FinnhubEarningsRow> EARLIEST_FIRST =
      Comparator.comparing(FinnhubEarningsRow::date, Comparator.nullsLast(String::compareTo))
          .thenComparing(FinnhubEarningsRow::symbol, Comparator.nullsLast(String::compareTo));

  private final RestClient finnhubRestClient;
  private final String finnhubApiKey;

  /// Creates the client.
  ///
  /// @param finnhubRestClient client with the Finnhub base URL and timeouts already set
  /// @param finnhubApiKey Finnhub API key
  public RestClientFinnhubClient(RestClient finnhubRestClient, String finnhubApiKey) {
    this.finnhubRestClient = finnhubRestClient;
    this.finnhubApiKey = finnhubApiKey;
  }

  /// {@inheritDoc}
  @Override
  public Optional<Quote> fetchQuote(String tickerSymbol) {
    long startNanos = System.nanoTime();
    FinnhubQuoteResponse quoteResponse =
        get(
            QUOTE_ENDPOINT,
            uriBuilder ->
                uriBuilder.path(QUOTE_ENDPOINT).queryParam("symbol", tickerSymbol).build(),
            FinnhubQuoteResponse.class,
            startNanos);
    boolean quoteAvailable = quoteResponse != null && quoteResponse.t() != 0;
    logCallCompleted(
        QUOTE_ENDPOINT,
        Map.of("tickerSymbol", tickerSymbol, "quoteAvailable", quoteAvailable),
        startNanos);
    return quoteAvailable ? Optional.of(quoteResponse.toQuote(tickerSymbol)) : Optional.empty();
  }

  /// {@inheritDoc}
  @Override
  public Optional<CompanyProfile> fetchCompanyProfile(String tickerSymbol) {
    long startNanos = System.nanoTime();
    FinnhubProfileResponse profileResponse =
        get(
            COMPANY_PROFILE_ENDPOINT,
            uriBuilder ->
                uriBuilder
                    .path(COMPANY_PROFILE_ENDPOINT)
                    .queryParam("symbol", tickerSymbol)
                    .build(),
            FinnhubProfileResponse.class,
            startNanos);
    boolean profileAvailable = profileResponse != null && profileResponse.name() != null;
    logCallCompleted(
        COMPANY_PROFILE_ENDPOINT,
        Map.of("tickerSymbol", tickerSymbol, "profileAvailable", profileAvailable),
        startNanos);
    return profileAvailable
        ? Optional.of(profileResponse.toCompanyProfile(tickerSymbol))
        : Optional.empty();
  }

  /// {@inheritDoc}
  @Override
  public List<NewsArticle> fetchCompanyNews(
      String tickerSymbol, LocalDate fromDate, LocalDate toDate) {
    long startNanos = System.nanoTime();
    FinnhubNewsItem[] newsItems =
        get(
            COMPANY_NEWS_ENDPOINT,
            uriBuilder ->
                uriBuilder
                    .path(COMPANY_NEWS_ENDPOINT)
                    .queryParam("symbol", tickerSymbol)
                    .queryParam("from", fromDate)
                    .queryParam("to", toDate)
                    .build(),
            FinnhubNewsItem[].class,
            startNanos);
    List<NewsArticle> newestArticles = newestArticles(newsItems);
    logCallCompleted(
        COMPANY_NEWS_ENDPOINT,
        Map.of(
            "tickerSymbol",
            tickerSymbol,
            "fromDate",
            fromDate.toString(),
            "toDate",
            toDate.toString(),
            "upstreamArticleCount",
            newsItems == null ? 0 : newsItems.length,
            "returnedArticleCount",
            newestArticles.size()),
        startNanos);
    return newestArticles;
  }

  /// {@inheritDoc}
  @Override
  public List<NewsArticle> fetchMarketNews(MarketNewsCategory newsCategory) {
    long startNanos = System.nanoTime();
    FinnhubNewsItem[] newsItems =
        get(
            MARKET_NEWS_ENDPOINT,
            uriBuilder ->
                uriBuilder
                    .path(MARKET_NEWS_ENDPOINT)
                    .queryParam("category", newsCategory.finnhubCategory())
                    .build(),
            FinnhubNewsItem[].class,
            startNanos);
    List<NewsArticle> newestArticles = newestArticles(newsItems);
    logCallCompleted(
        MARKET_NEWS_ENDPOINT,
        Map.of(
            "newsCategory",
            newsCategory.finnhubCategory(),
            "upstreamArticleCount",
            newsItems == null ? 0 : newsItems.length,
            "returnedArticleCount",
            newestArticles.size()),
        startNanos);
    return newestArticles;
  }

  /// {@inheritDoc}
  @Override
  public List<EarningsEvent> fetchEarningsCalendar(
      LocalDate fromDate, LocalDate toDate, String tickerSymbol) {
    long startNanos = System.nanoTime();
    FinnhubEarningsCalendarResponse calendarResponse =
        get(
            EARNINGS_CALENDAR_ENDPOINT,
            uriBuilder -> {
              uriBuilder
                  .path(EARNINGS_CALENDAR_ENDPOINT)
                  .queryParam("from", fromDate)
                  .queryParam("to", toDate);
              if (tickerSymbol != null) {
                uriBuilder.queryParam("symbol", tickerSymbol);
              }
              return uriBuilder.build();
            },
            FinnhubEarningsCalendarResponse.class,
            startNanos);
    List<FinnhubEarningsRow> earningsRows =
        calendarResponse == null || calendarResponse.earningsCalendar() == null
            ? List.of()
            : calendarResponse.earningsCalendar();
    List<EarningsEvent> earliestEvents =
        earningsRows.stream()
            .sorted(EARLIEST_FIRST)
            .limit(MAX_EARNINGS_EVENTS)
            .map(FinnhubEarningsRow::toEarningsEvent)
            .toList();
    Map<String, Object> calendarData = new LinkedHashMap<>();
    calendarData.put("tickerSymbol", tickerSymbol);
    calendarData.put("fromDate", fromDate.toString());
    calendarData.put("toDate", toDate.toString());
    calendarData.put("upstreamEventCount", earningsRows.size());
    calendarData.put("returnedEventCount", earliestEvents.size());
    logCallCompleted(EARNINGS_CALENDAR_ENDPOINT, calendarData, startNanos);
    return earliestEvents;
  }

  /// {@inheritDoc}
  @Override
  public Optional<BasicFinancials> fetchBasicFinancials(String tickerSymbol) {
    long startNanos = System.nanoTime();
    FinnhubMetricResponse metricResponse =
        get(
            BASIC_FINANCIALS_ENDPOINT,
            uriBuilder ->
                uriBuilder
                    .path(BASIC_FINANCIALS_ENDPOINT)
                    .queryParam("symbol", tickerSymbol)
                    .queryParam("metric", "all")
                    .build(),
            FinnhubMetricResponse.class,
            startNanos);
    boolean metricsAvailable =
        metricResponse != null
            && metricResponse.metric() != null
            && metricResponse.metric().hasAnyValue();
    logCallCompleted(
        BASIC_FINANCIALS_ENDPOINT,
        Map.of("tickerSymbol", tickerSymbol, "metricsAvailable", metricsAvailable),
        startNanos);
    return metricsAvailable
        ? Optional.of(metricResponse.metric().toBasicFinancials(tickerSymbol))
        : Optional.empty();
  }

  /// Sends one authenticated GET and maps failures to [MarketDataException].
  private <T> T get(
      String finnhubEndpoint,
      Function<UriBuilder, URI> requestUriFunction,
      Class<T> responseType,
      long startNanos) {
    try {
      return finnhubRestClient
          .get()
          .uri(requestUriFunction)
          .header(TOKEN_HEADER, finnhubApiKey)
          .retrieve()
          .body(responseType);
    } catch (RestClientResponseException statusException) {
      int httpStatus = statusException.getStatusCode().value();
      throw callFailed(
          finnhubEndpoint, httpStatus, messageForStatus(httpStatus), statusException, startNanos);
    } catch (ResourceAccessException ioException) {
      throw callFailed(finnhubEndpoint, null, TIMED_OUT_MESSAGE, ioException, startNanos);
    } catch (RestClientException unreadableResponseException) {
      throw callFailed(
          finnhubEndpoint, null, UNAVAILABLE_MESSAGE, unreadableResponseException, startNanos);
    }
  }

  private static List<NewsArticle> newestArticles(FinnhubNewsItem[] newsItems) {
    if (newsItems == null) {
      return List.of();
    }
    return Arrays.stream(newsItems)
        .sorted(NEWEST_FIRST)
        .limit(MAX_NEWS_ARTICLES)
        .map(FinnhubNewsItem::toNewsArticle)
        .toList();
  }

  /// Cuts a summary to at most [#MAX_SUMMARY_LENGTH] characters, at a word boundary when one is
  /// close, ending with `…` when cut.
  static String cutSummary(String fullSummary) {
    if (fullSummary == null) {
      return "";
    }
    String strippedSummary = fullSummary.strip();
    if (strippedSummary.length() <= MAX_SUMMARY_LENGTH) {
      return strippedSummary;
    }
    int maxKeptLength = MAX_SUMMARY_LENGTH - SUMMARY_CUT_MARKER.length();
    int lastSpaceIndex = strippedSummary.lastIndexOf(' ', maxKeptLength);
    int cutIndex = lastSpaceIndex >= maxKeptLength / 2 ? lastSpaceIndex : maxKeptLength;
    return strippedSummary.substring(0, cutIndex).stripTrailing() + SUMMARY_CUT_MARKER;
  }

  private static String messageForStatus(int httpStatus) {
    if (httpStatus == 401 || httpStatus == 403) {
      return AUTHORIZATION_MESSAGE;
    }
    if (httpStatus == 429) {
      return RATE_LIMIT_MESSAGE;
    }
    return httpStatus >= 500 ? UNAVAILABLE_MESSAGE : REJECTED_MESSAGE;
  }

  private static void logCallCompleted(
      String finnhubEndpoint, Map<String, Object> resultData, long startNanos) {
    Map<String, Object> completionData = new LinkedHashMap<>();
    completionData.put("finnhubEndpoint", finnhubEndpoint);
    completionData.putAll(resultData);
    completionData.put("durationTimeMs", elapsedMillis(startNanos));
    log.atInfo()
        .addKeyValue("eventName", "finnhubCallCompleted")
        .addKeyValue("eventData", completionData)
        .log("Finnhub call completed");
  }

  /// Logs the failure once and builds the exception to throw.
  ///
  /// The cause is not attached: Spring's exception messages carry the URL or the upstream body.
  private static MarketDataException callFailed(
      String finnhubEndpoint,
      Integer httpStatus,
      String modelReadableMessage,
      Exception cause,
      long startNanos) {
    boolean clientSideRejection = httpStatus != null && httpStatus < 500;
    Map<String, Object> failureData = new LinkedHashMap<>();
    failureData.put("httpStatus", httpStatus);
    failureData.put("finnhubEndpoint", finnhubEndpoint);
    failureData.put("exceptionType", cause.getClass().getName());
    failureData.put("durationTimeMs", elapsedMillis(startNanos));
    log.atLevel(clientSideRejection ? Level.WARN : Level.ERROR)
        .addKeyValue("eventName", "finnhubCallFailed")
        .addKeyValue("eventData", failureData)
        .log("Finnhub call failed");
    return new MarketDataException(modelReadableMessage);
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /// Finnhub's `/quote` response, with its one-letter field names.
  private record FinnhubQuoteResponse(
      BigDecimal c,
      BigDecimal d,
      BigDecimal dp,
      BigDecimal h,
      BigDecimal l,
      BigDecimal o,
      BigDecimal pc,
      long t) {

    Quote toQuote(String tickerSymbol) {
      return new Quote(tickerSymbol, c, d, dp, h, l, o, pc, Instant.ofEpochSecond(t).toString());
    }
  }

  /// The fields read from Finnhub's `/stock/profile2` response; it is `{}` for an unknown symbol.
  private record FinnhubProfileResponse(
      String name,
      String exchange,
      String finnhubIndustry,
      String country,
      BigDecimal marketCapitalization,
      BigDecimal shareOutstanding,
      String ipo,
      String weburl) {

    CompanyProfile toCompanyProfile(String tickerSymbol) {
      return new CompanyProfile(
          tickerSymbol,
          name,
          exchange,
          finnhubIndustry,
          country,
          marketCapitalization,
          shareOutstanding,
          ipo,
          weburl);
    }
  }

  /// The fields read from one article of `/company-news` or `/news`.
  private record FinnhubNewsItem(
      String headline, String source, long datetime, String summary, String url) {

    NewsArticle toNewsArticle() {
      return new NewsArticle(
          headline, source, Instant.ofEpochSecond(datetime).toString(), cutSummary(summary), url);
    }
  }

  /// Finnhub's `/calendar/earnings` response.
  private record FinnhubEarningsCalendarResponse(List<FinnhubEarningsRow> earningsCalendar) {}

  /// The fields read from one row of `/calendar/earnings`.
  private record FinnhubEarningsRow(
      String symbol,
      String date,
      String hour,
      Integer year,
      Integer quarter,
      BigDecimal epsEstimate,
      BigDecimal epsActual,
      BigDecimal revenueEstimate,
      BigDecimal revenueActual) {

    EarningsEvent toEarningsEvent() {
      return new EarningsEvent(
          symbol,
          date,
          hour,
          year,
          quarter,
          epsEstimate,
          epsActual,
          revenueEstimate,
          revenueActual);
    }
  }

  /// Finnhub's `/stock/metric` response; only `metric` is read, never the `series` time series.
  private record FinnhubMetricResponse(FinnhubMetrics metric) {}

  /// The twelve metrics read from `/stock/metric`; it has about 130 more.
  private record FinnhubMetrics(
      @JsonProperty("52WeekHigh") BigDecimal week52High,
      @JsonProperty("52WeekLow") BigDecimal week52Low,
      @JsonProperty("peTTM") BigDecimal peTtm,
      @JsonProperty("pb") BigDecimal priceToBook,
      @JsonProperty("epsTTM") BigDecimal epsTtm,
      @JsonProperty("dividendYieldIndicatedAnnual") BigDecimal dividendYieldIndicatedAnnual,
      @JsonProperty("beta") BigDecimal beta,
      @JsonProperty("grossMarginTTM") BigDecimal grossMarginTtm,
      @JsonProperty("operatingMarginTTM") BigDecimal operatingMarginTtm,
      @JsonProperty("netProfitMarginTTM") BigDecimal netProfitMarginTtm,
      @JsonProperty("roeTTM") BigDecimal roeTtm,
      @JsonProperty("totalDebt/totalEquityQuarterly") BigDecimal totalDebtToEquityQuarterly) {

    boolean hasAnyValue() {
      return Stream.of(
              week52High,
              week52Low,
              peTtm,
              priceToBook,
              epsTtm,
              dividendYieldIndicatedAnnual,
              beta,
              grossMarginTtm,
              operatingMarginTtm,
              netProfitMarginTtm,
              roeTtm,
              totalDebtToEquityQuarterly)
          .anyMatch(Objects::nonNull);
    }

    BasicFinancials toBasicFinancials(String tickerSymbol) {
      return new BasicFinancials(
          tickerSymbol,
          week52High,
          week52Low,
          peTtm,
          priceToBook,
          epsTtm,
          dividendYieldIndicatedAnnual,
          beta,
          grossMarginTtm,
          operatingMarginTtm,
          netProfitMarginTtm,
          roeTtm,
          totalDebtToEquityQuarterly);
    }
  }
}
