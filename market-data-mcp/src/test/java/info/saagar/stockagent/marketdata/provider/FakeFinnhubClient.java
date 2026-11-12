package info.saagar.stockagent.marketdata.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/// In-memory [FinnhubClient] with synthetic data.
///
/// `AAPL` has data from every endpoint, `LIMIT` fails as if Finnhub returned 429, and every other
/// symbol has no data, as Finnhub reports for unknown symbols. The last company-news date range is
/// recorded so tests can check the defaults the tool applies.
public class FakeFinnhubClient implements FinnhubClient {

  /// Symbol that makes the fake fail with Finnhub's rate-limit error.
  public static final String RATE_LIMITED_SYMBOL = "LIMIT";

  /// The synthetic quote returned for `AAPL`.
  public static final Quote AAPL_QUOTE =
      new Quote(
          "AAPL",
          new BigDecimal("189.42"),
          new BigDecimal("-0.7"),
          new BigDecimal("-0.37"),
          new BigDecimal("191.05"),
          new BigDecimal("188.1"),
          new BigDecimal("190.2"),
          new BigDecimal("190.12"),
          "2026-05-01T14:30:00Z");

  /// The synthetic profile returned for `AAPL`.
  public static final CompanyProfile AAPL_PROFILE =
      new CompanyProfile(
          "AAPL",
          "Synthetic Apple Inc",
          "NASDAQ NMS - GLOBAL MARKET",
          "Technology",
          "US",
          new BigDecimal("2900000.5"),
          new BigDecimal("15300.25"),
          "1980-12-12",
          "https://example.com/");

  /// The synthetic article returned by both news methods.
  public static final NewsArticle SYNTHETIC_ARTICLE =
      new NewsArticle(
          "Synthetic headline",
          "Synthetic Wire",
          "2026-04-30T13:00:00Z",
          "Synthetic summary.",
          "https://example.com/news/1");

  /// The synthetic earnings release returned for any range.
  public static final EarningsEvent AAPL_EARNINGS =
      new EarningsEvent(
          "AAPL",
          "2026-05-01",
          "amc",
          2026,
          2,
          new BigDecimal("1.61"),
          null,
          new BigDecimal("94000000000"),
          null);

  /// The synthetic metrics returned for `AAPL`.
  public static final BasicFinancials AAPL_FINANCIALS =
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
          new BigDecimal("1.8"));

  private volatile LocalDate lastCompanyNewsFromDate;
  private volatile LocalDate lastCompanyNewsToDate;

  @Override
  public Optional<Quote> fetchQuote(String tickerSymbol) {
    failIfRateLimited(tickerSymbol);
    return "AAPL".equals(tickerSymbol) ? Optional.of(AAPL_QUOTE) : Optional.empty();
  }

  @Override
  public Optional<CompanyProfile> fetchCompanyProfile(String tickerSymbol) {
    failIfRateLimited(tickerSymbol);
    return "AAPL".equals(tickerSymbol) ? Optional.of(AAPL_PROFILE) : Optional.empty();
  }

  @Override
  public List<NewsArticle> fetchCompanyNews(
      String tickerSymbol, LocalDate fromDate, LocalDate toDate) {
    failIfRateLimited(tickerSymbol);
    lastCompanyNewsFromDate = fromDate;
    lastCompanyNewsToDate = toDate;
    return "AAPL".equals(tickerSymbol) ? List.of(SYNTHETIC_ARTICLE) : List.of();
  }

  @Override
  public List<NewsArticle> fetchMarketNews(MarketNewsCategory newsCategory) {
    return List.of(SYNTHETIC_ARTICLE);
  }

  @Override
  public List<EarningsEvent> fetchEarningsCalendar(
      LocalDate fromDate, LocalDate toDate, String tickerSymbol) {
    failIfRateLimited(tickerSymbol);
    return tickerSymbol == null || "AAPL".equals(tickerSymbol) ? List.of(AAPL_EARNINGS) : List.of();
  }

  @Override
  public Optional<BasicFinancials> fetchBasicFinancials(String tickerSymbol) {
    failIfRateLimited(tickerSymbol);
    return "AAPL".equals(tickerSymbol) ? Optional.of(AAPL_FINANCIALS) : Optional.empty();
  }

  /// Returns the `from` date of the last company-news call.
  ///
  /// @return the date, or `null` before the first call
  public LocalDate lastCompanyNewsFromDate() {
    return lastCompanyNewsFromDate;
  }

  /// Returns the `to` date of the last company-news call.
  ///
  /// @return the date, or `null` before the first call
  public LocalDate lastCompanyNewsToDate() {
    return lastCompanyNewsToDate;
  }

  private static void failIfRateLimited(String tickerSymbol) {
    if (RATE_LIMITED_SYMBOL.equals(tickerSymbol)) {
      throw new MarketDataException(RestClientFinnhubClient.RATE_LIMIT_MESSAGE);
    }
  }
}
